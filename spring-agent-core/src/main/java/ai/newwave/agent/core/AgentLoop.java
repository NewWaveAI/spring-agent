package ai.newwave.agent.core;

import ai.newwave.agent.util.Json;

import com.fasterxml.jackson.databind.JsonNode;
import ai.newwave.agent.config.AgentConfig;
import ai.newwave.agent.config.AgentHooks;
import ai.newwave.agent.config.AgentLoopConfig;
import ai.newwave.agent.config.HookContext;
import ai.newwave.agent.event.AgentEvent;
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.model.MessageRole;
import ai.newwave.agent.model.ToolExecutionMode;
import ai.newwave.agent.state.spi.ConversationStateManager;
import ai.newwave.agent.state.spi.ConversationStore;
import ai.newwave.agent.tool.AgentTool;
import ai.newwave.agent.tool.AgentToolResult;
import ai.newwave.agent.tool.ToolCallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Core agent loop implementing the inner (tool execution) loop.
 * Operates on a mutable message list — one loop instance per stream() call.
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    

    private final String agentId;
    private final String conversationId;
    private final List<AgentMessage> messages;
    private final Map<String, Object> attributes;
    private final AgentConfig config;
    private final ChatModel chatModel;
    private final Sinks.Many<AgentEvent> sink;
    private final ConversationStore conversationStore;
    private final ConversationStateManager stateManager;
    private volatile boolean shouldTerminate = false;
    private long totalInputTokens = 0;
    private long totalOutputTokens = 0;

    // Messages produced during the current turn (assistant + tool_results), flushed
    // atomically at turn end so their sequence values match logical order on replay.
    // Guarded by intrinsic lock because tool_result appends from PARALLEL mode are
    // dispatched on independent reactor threads.
    private final List<AgentMessage> pendingTurnMessages = new ArrayList<>();

    public AgentLoop(
            String agentId,
            String conversationId,
            List<AgentMessage> messages,
            Map<String, Object> attributes,
            AgentConfig config,
            ChatModel chatModel,
            Sinks.Many<AgentEvent> sink,
            ConversationStore conversationStore
    ) {
        this(agentId, conversationId, messages, attributes, config, chatModel, sink, conversationStore, null);
    }

    public AgentLoop(
            String agentId,
            String conversationId,
            List<AgentMessage> messages,
            Map<String, Object> attributes,
            AgentConfig config,
            ChatModel chatModel,
            Sinks.Many<AgentEvent> sink,
            ConversationStore conversationStore,
            ConversationStateManager stateManager
    ) {
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.messages = messages;
        this.attributes = attributes != null ? attributes : Map.of();
        this.config = config;
        this.chatModel = chatModel;
        this.sink = sink;
        this.conversationStore = conversationStore;
        this.stateManager = stateManager;
    }

    /**
     * Run the agent loop (LLM → tools → recurse until no more tool calls).
     */
    public Mono<Void> run() {
        return innerLoop(0);
    }

    /**
     * Get accumulated token usage across all LLM calls in this loop.
     */
    public AgentEvent.TokenUsage getTokenUsage() {
        return new AgentEvent.TokenUsage(config.model(), totalInputTokens, totalOutputTokens);
    }

    private Mono<Void> innerLoop(int turnNumber) {
        return Mono.defer(() -> {
            AgentLoopConfig loopConfig = config.loopConfig();
            if (turnNumber >= loopConfig.maxTurns()) {
                log.warn("Max turns ({}) reached, stopping agent loop", loopConfig.maxTurns());
                return Mono.empty();
            }

            // Check abort status
            Mono<Void> abortCheck = stateManager != null
                    ? stateManager.isAborting(agentId, conversationId)
                        .flatMap(aborting -> {
                            if (aborting) {
                                log.info("Abort requested for {}:{}, stopping loop", agentId, conversationId);
                                return Mono.<Void>empty();
                            }
                            return Mono.empty();
                        })
                    : Mono.empty();

            // Drain steering messages
            Mono<Void> drainSteering = stateManager != null
                    ? stateManager.drainSteering(agentId, conversationId)
                        .flatMap(steerMsg -> {
                            addMessage(steerMsg);
                            return Mono.<AgentMessage>empty();
                        })
                        .then()
                    : Mono.empty();

            return abortCheck
                    .then(drainSteering)
                    .then(Mono.defer(() -> {

            sink.tryEmitNext(new AgentEvent.TurnStart(agentId, conversationId, turnNumber));

            // Build Spring AI messages from conversation state
            AgentHooks hooks = loopConfig.hooks();
            HookContext hookCtx = new HookContext(agentId, conversationId, attributes);

            return hooks.transformContext(hookCtx, List.copyOf(messages))
                    .flatMap(context -> hooks.convertToLlm(hookCtx, context))
                    .flatMap(llmMessages -> {
                        List<Message> springMessages = toSpringMessages(llmMessages);
                        Prompt prompt = buildPrompt(springMessages);
                        return streamLlmResponse(prompt, turnNumber)
                                .then(Mono.defer(() -> {
                                    List<ContentBlock.ToolUse> toolCalls = extractToolCalls();
                                    if (toolCalls.isEmpty()) {
                                        return flushTurn().doOnSuccess(v ->
                                                sink.tryEmitNext(new AgentEvent.TurnEnd(agentId, conversationId, turnNumber)));
                                    }
                                    return executeTools(toolCalls, hookCtx)
                                            .then(flushTurn())
                                            .then(Mono.defer(() -> {
                                                sink.tryEmitNext(new AgentEvent.TurnEnd(agentId, conversationId, turnNumber));
                                                if (shouldTerminate) {
                                                    return Mono.<Void>empty();
                                                }
                                                return innerLoop(turnNumber + 1);
                                            }));
                                }));
                    });
            }));
        });
    }

    /**
     * Stream LLM response, accumulating content blocks and emitting events.
     */
    private Mono<Void> streamLlmResponse(Prompt prompt, int turnNumber) {
        return Mono.create(monoSink -> {
            try {
                Flux<ChatResponse> stream = chatModel.stream(prompt);

                List<ContentBlock> contentBlocks = new ArrayList<>();
                StringBuilder textAccumulator = new StringBuilder();
                StringBuilder thinkingAccumulator = new StringBuilder();
                boolean[] messageStarted = {false};

                stream.doOnNext(chatResponse -> {
                    // Capture token usage (updated on each chunk, final chunk has totals)
                    if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                        var usage = chatResponse.getMetadata().getUsage();
                        if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                            totalInputTokens += usage.getPromptTokens();
                        }
                        if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0) {
                            totalOutputTokens += usage.getCompletionTokens();
                        }
                    }

                    Generation generation = chatResponse.getResult();
                    if (generation == null) return;

                    AssistantMessage output = generation.getOutput();
                    if (output == null) return;

                    // Emit message_start on first chunk
                    if (!messageStarted[0]) {
                        messageStarted[0] = true;
                        sink.tryEmitNext(new AgentEvent.MessageStart(
                                agentId, conversationId,
                                AgentMessage.assistant(List.of())));
                    }

                    // Process text content — skip if this chunk is thinking content
                    // Spring AI marks thinking chunks with a "signature" metadata key
                    String text = output.getText();
                    Map<String, Object> metadata = output.getMetadata();
                    boolean isThinkingChunk = metadata != null && metadata.containsKey("signature");
                    if (text != null && !text.isEmpty()) {
                        if (isThinkingChunk) {
                            thinkingAccumulator.append(text);
                            sink.tryEmitNext(new AgentEvent.ThinkingUpdate(agentId, conversationId, text));
                        } else {
                            textAccumulator.append(text);
                            sink.tryEmitNext(new AgentEvent.MessageUpdate(agentId, conversationId, text));
                        }
                    }

                    // Check for tool calls in metadata
                    if (output.getToolCalls() != null) {
                        for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                            try {
                                JsonNode inputNode = Json.MAPPER.readTree(toolCall.arguments());
                                contentBlocks.add(new ContentBlock.ToolUse(
                                        toolCall.id(), toolCall.name(), inputNode));
                            } catch (Exception e) {
                                log.error("Failed to parse tool call arguments", e);
                            }
                        }
                    }

                    // Note: thinking deltas are handled above via the "signature" metadata key check
                }).doOnComplete(() -> {
                    // Build final assistant message — thinking first, then text
                    if (thinkingAccumulator.length() > 0) {
                        contentBlocks.addFirst(new ContentBlock.Thinking(thinkingAccumulator.toString()));
                    }
                    int textInsertIndex = contentBlocks.isEmpty() ? 0 :
                            (contentBlocks.getFirst() instanceof ContentBlock.Thinking ? 1 : 0);
                    if (textAccumulator.length() > 0) {
                        contentBlocks.add(textInsertIndex, new ContentBlock.Text(textAccumulator.toString()));
                    }

                    AgentMessage assistantMessage = AgentMessage.assistant(contentBlocks);
                    addMessage(assistantMessage);

                    if (messageStarted[0]) {
                        sink.tryEmitNext(new AgentEvent.MessageEnd(agentId, conversationId, assistantMessage));
                    }

                    monoSink.success();
                }).doOnError(error -> {
                    log.error("LLM streaming error", error);
                    monoSink.error(error);
                }).subscribe();

            } catch (Exception e) {
                log.error("Failed to initiate LLM stream", e);
                monoSink.error(e);
            }
        });
    }

    /**
     * Execute tool calls sequentially or in parallel based on config.
     */
    private Mono<Void> executeTools(List<ContentBlock.ToolUse> toolCalls, HookContext hookCtx) {
        ToolExecutionMode mode = config.loopConfig().toolExecutionMode();
        AgentHooks hooks = config.loopConfig().hooks();

        if (mode == ToolExecutionMode.PARALLEL) {
            return Flux.fromIterable(toolCalls)
                    .flatMap(toolUse -> executeSingleTool(toolUse, hooks, hookCtx))
                    .then();
        } else {
            return Flux.fromIterable(toolCalls)
                    .concatMap(toolUse -> executeSingleTool(toolUse, hooks, hookCtx))
                    .then();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> executeSingleTool(ContentBlock.ToolUse toolUse, AgentHooks hooks, HookContext hookCtx) {
        return hooks.beforeToolCall(hookCtx, toolUse.name(), toolUse)
                .flatMap(beforeResult -> {
                    if (!beforeResult.proceed()) {
                        String reason = beforeResult.reason() != null
                                ? beforeResult.reason()
                                : "Tool execution was blocked";
                        AgentMessage resultMessage = AgentMessage.toolResult(
                                toolUse.id(),
                                List.of(new ContentBlock.Text(reason)),
                                true
                        );
                        addMessage(resultMessage);
                        return Mono.empty();
                    }

                    AgentTool tool = findTool(toolUse.name());
                    if (tool == null) {
                        AgentMessage errorResult = AgentMessage.toolResult(
                                toolUse.id(),
                                List.of(new ContentBlock.Text("Unknown tool: " + toolUse.name())),
                                true
                        );
                        addMessage(errorResult);
                        return Mono.empty();
                    }

                    sink.tryEmitNext(new AgentEvent.ToolExecutionStart(agentId, conversationId, toolUse));

                    Object params;
                    try {
                        params = Json.MAPPER.treeToValue(toolUse.input(), tool.parameterType());
                    } catch (Exception e) {
                        log.error("Failed to deserialize tool parameters for {}", toolUse.name(), e);
                        AgentToolResult<?> errorResult = AgentToolResult.error(
                                "Parameter deserialization failed: " + e.getMessage());
                        return finishToolExecution(toolUse, errorResult, hooks, hookCtx);
                    }

                    ToolCallContext context = new ToolCallContext(toolUse.id(), toolUse.name(), params, agentId, conversationId, attributes);

                    @SuppressWarnings("unchecked")
                    Mono<AgentToolResult<?>> execution = (Mono<AgentToolResult<?>>) tool.execute(context);

                    return execution
                            .onErrorResume(e -> {
                                log.error("Tool execution failed for {}", toolUse.name(), e);
                                return Mono.just(AgentToolResult.error("Tool execution failed: " + e.getMessage()));
                            })
                            .flatMap(result -> finishToolExecution(toolUse, result, hooks, hookCtx));
                });
    }

    private Mono<Void> finishToolExecution(ContentBlock.ToolUse toolUse, AgentToolResult<?> result, AgentHooks hooks, HookContext hookCtx) {
        return hooks.afterToolCall(hookCtx, toolUse.name(), toolUse, result)
                .map(modifiedResult -> {
                    AgentMessage resultMessage = AgentMessage.toolResult(
                            toolUse.id(), modifiedResult.content(), modifiedResult.isError());
                    addMessage(resultMessage);

                    if (modifiedResult.terminatesLoop()) {
                        shouldTerminate = true;
                    }

                    sink.tryEmitNext(new AgentEvent.ToolExecutionEnd(agentId, conversationId, toolUse, modifiedResult));
                    return modifiedResult;
                })
                .then();
    }

    // --- Helpers ---

    /**
     * Accumulate a message produced during the current turn. The in-memory list is updated
     * immediately so the running loop sees it; the durable write is deferred to
     * {@link #flushTurn} so assistant + tool_results persist as one ordered batch.
     */
    private void addMessage(AgentMessage message) {
        synchronized (pendingTurnMessages) {
            messages.add(message);
            pendingTurnMessages.add(message);
        }
    }

    /**
     * Persist the messages accumulated during the current turn in one ordered batch and clear
     * the pending buffer. Must be called at every turn boundary before recursing — otherwise
     * the next turn's writes would race with this turn's and the IDENTITY sequence column
     * would reorder them.
     */
    private Mono<Void> flushTurn() {
        return Mono.defer(() -> {
            List<AgentMessage> toFlush;
            synchronized (pendingTurnMessages) {
                if (pendingTurnMessages.isEmpty()) return Mono.empty();
                toFlush = List.copyOf(pendingTurnMessages);
                pendingTurnMessages.clear();
            }
            return conversationStore.appendMessages(agentId, conversationId, toFlush)
                    .doOnError(e -> log.error("Failed to persist turn messages to conversation store", e));
        });
    }

    private List<ContentBlock.ToolUse> extractToolCalls() {
        if (messages.isEmpty()) return List.of();

        AgentMessage lastMessage = messages.getLast();
        if (lastMessage.role() != MessageRole.ASSISTANT) return List.of();

        return lastMessage.content().stream()
                .filter(block -> block instanceof ContentBlock.ToolUse)
                .map(block -> (ContentBlock.ToolUse) block)
                .toList();
    }

    private AgentTool<?, ?> findTool(String name) {
        return config.tools().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private List<Message> toSpringMessages(List<AgentMessage> agentMessages) {
        // --- Step 1: Determine which tool IDs to skip ---

        // Excluded by tool definition (excludeFromContext)
        Set<String> excludedToolNames = config.tools().stream()
                .filter(AgentTool::excludeFromContext)
                .map(AgentTool::name)
                .collect(Collectors.toSet());
        Set<String> skipIds = new HashSet<>();
        for (AgentMessage msg : agentMessages) {
            for (ContentBlock block : msg.content()) {
                if (block instanceof ContentBlock.ToolUse tu && excludedToolNames.contains(tu.name())) {
                    skipIds.add(tu.id());
                }
            }
        }

        // maxToolResultsInContext — collect non-excluded tool IDs, keep last N
        int maxToolResults = config.loopConfig().maxToolResultsInContext();
        if (maxToolResults > 0) {
            List<String> allToolUseIds = new ArrayList<>();
            for (AgentMessage msg : agentMessages) {
                for (ContentBlock block : msg.content()) {
                    if (block instanceof ContentBlock.ToolUse tu && !skipIds.contains(tu.id())) {
                        allToolUseIds.add(tu.id());
                    }
                }
            }
            if (allToolUseIds.size() > maxToolResults) {
                Set<String> keepIds = new HashSet<>(
                        allToolUseIds.subList(allToolUseIds.size() - maxToolResults, allToolUseIds.size()));
                for (String id : allToolUseIds) {
                    if (!keepIds.contains(id)) skipIds.add(id);
                }
            }
        }

        // --- Step 2: Build messages with tool_result IDs index ---

        // Index: tool_use_id → tool_result AgentMessage (for reordering)
        Map<String, AgentMessage> toolResultByUseId = new java.util.LinkedHashMap<>();
        for (AgentMessage msg : agentMessages) {
            if (msg.role() == MessageRole.TOOL_RESULT) {
                for (ContentBlock block : msg.content()) {
                    if (block instanceof ContentBlock.ToolResult tr) {
                        toolResultByUseId.put(tr.toolUseId(), msg);
                    }
                }
            }
        }

        // Track which tool_result messages have been emitted (for reordering)
        Set<String> emittedToolResultMsgIds = new HashSet<>();
        // Track pending tool_use IDs that need tool_results before user messages
        Set<String> pendingToolUseIds = new HashSet<>();
        List<Message> deferred = new ArrayList<>();

        List<Message> result = new ArrayList<>();
        for (AgentMessage msg : agentMessages) {
            switch (msg.role()) {
                case USER -> {
                    String text = msg.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .collect(Collectors.joining("\n"));
                    Message userMsg = new UserMessage(text);
                    if (!pendingToolUseIds.isEmpty()) {
                        // Defer user message until tool_results arrive
                        deferred.add(userMsg);
                    } else {
                        result.add(userMsg);
                    }
                }
                case ASSISTANT -> {
                    // Flush any deferred messages first
                    result.addAll(deferred);
                    deferred.clear();

                    String text = msg.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .collect(Collectors.joining("\n"));

                    List<AssistantMessage.ToolCall> toolCalls = msg.content().stream()
                            .filter(b -> b instanceof ContentBlock.ToolUse)
                            .map(b -> (ContentBlock.ToolUse) b)
                            .filter(tu -> !skipIds.contains(tu.id()))
                            .map(tu -> new AssistantMessage.ToolCall(
                                    tu.id(), "function", tu.name(), tu.input().toString()))
                            .toList();

                    String thinking = msg.content().stream()
                            .filter(b -> b instanceof ContentBlock.Thinking)
                            .map(b -> ((ContentBlock.Thinking) b).thinking())
                            .collect(Collectors.joining("\n"));

                    var builder = AssistantMessage.builder()
                            .content(text)
                            .toolCalls(toolCalls);

                    if (!thinking.isEmpty()) {
                        builder.properties(Map.of("reasoningContent", thinking));
                    }

                    result.add(builder.build());

                    // Track pending tool_use IDs
                    pendingToolUseIds.clear();
                    for (var tc : toolCalls) {
                        pendingToolUseIds.add(tc.id());
                    }
                }
                case TOOL_RESULT -> {
                    for (ContentBlock block : msg.content()) {
                        if (block instanceof ContentBlock.ToolResult tr && !skipIds.contains(tr.toolUseId())) {
                            String resultText = tr.content().stream()
                                    .filter(b -> b instanceof ContentBlock.Text)
                                    .map(b -> ((ContentBlock.Text) b).text())
                                    .collect(Collectors.joining("\n"));
                            result.add(ToolResponseMessage.builder()
                                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                                            tr.toolUseId(), tr.toolUseId(), resultText)))
                                    .build());
                            pendingToolUseIds.remove(tr.toolUseId());
                            emittedToolResultMsgIds.add(msg.id());
                        }
                    }
                    // If all pending tool_uses resolved, flush deferred
                    if (pendingToolUseIds.isEmpty()) {
                        result.addAll(deferred);
                        deferred.clear();
                    }
                }
            }
        }
        // Flush any remaining deferred messages
        result.addAll(deferred);

        // --- Step 3: Pair matching safety net ---
        Set<String> resultToolUseIds = new HashSet<>();
        Set<String> resultToolResultIds = new HashSet<>();
        for (Message m : result) {
            if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                for (var tc : am.getToolCalls()) {
                    resultToolUseIds.add(tc.id());
                }
            }
            if (m instanceof ToolResponseMessage trm) {
                for (var resp : trm.getResponses()) {
                    resultToolResultIds.add(resp.id());
                }
            }
        }
        Set<String> pairedIds = new HashSet<>(resultToolUseIds);
        pairedIds.retainAll(resultToolResultIds);

        // Remove unpaired tool_use from assistant messages and unpaired tool_results
        List<Message> sanitized = new ArrayList<>();
        for (Message m : result) {
            if (m instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                List<AssistantMessage.ToolCall> paired = am.getToolCalls().stream()
                        .filter(tc -> pairedIds.contains(tc.id()))
                        .toList();
                sanitized.add(AssistantMessage.builder()
                        .content(am.getText())
                        .toolCalls(paired)
                        .properties(am.getMetadata())
                        .build());
            } else if (m instanceof ToolResponseMessage trm) {
                List<ToolResponseMessage.ToolResponse> paired = trm.getResponses().stream()
                        .filter(resp -> pairedIds.contains(resp.id()))
                        .toList();
                if (!paired.isEmpty()) {
                    sanitized.add(ToolResponseMessage.builder().responses(paired).build());
                }
            } else {
                sanitized.add(m);
            }
        }

        return sanitized;
    }

    private Prompt buildPrompt(List<Message> messages) {
        List<ToolCallback> toolCallbacks = config.tools().isEmpty() ? List.of() :
                config.tools().stream()
                        .map(AgentToolCallbackAdapter::new)
                        .map(a -> (ToolCallback) a)
                        .toList();
        return config.promptBuilder().buildPrompt(messages, config, toolCallbacks);
    }
}
