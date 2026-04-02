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
import ai.newwave.agent.model.ThinkingLevel;
import ai.newwave.agent.model.ToolExecutionMode;
import ai.newwave.agent.state.spi.ConversationStore;
import ai.newwave.agent.tool.AgentTool;
import ai.newwave.agent.tool.AgentToolResult;
import ai.newwave.agent.tool.ToolCallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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
import java.util.List;
import java.util.Map;
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
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.messages = messages;
        this.attributes = attributes != null ? attributes : Map.of();
        this.config = config;
        this.chatModel = chatModel;
        this.sink = sink;
        this.conversationStore = conversationStore;
    }

    /**
     * Run the agent loop (LLM → tools → recurse until no more tool calls).
     */
    public Mono<Void> run() {
        return innerLoop(0);
    }

    private Mono<Void> innerLoop(int turnNumber) {
        return Mono.defer(() -> {
            AgentLoopConfig loopConfig = config.loopConfig();
            if (turnNumber >= loopConfig.maxTurns()) {
                log.warn("Max turns ({}) reached, stopping agent loop", loopConfig.maxTurns());
                return Mono.empty();
            }

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
                                        sink.tryEmitNext(new AgentEvent.TurnEnd(agentId, conversationId, turnNumber));
                                        return Mono.<Void>empty();
                                    }
                                    return executeTools(toolCalls, hookCtx)
                                            .then(Mono.defer(() -> {
                                                sink.tryEmitNext(new AgentEvent.TurnEnd(agentId, conversationId, turnNumber));
                                                return innerLoop(turnNumber + 1);
                                            }));
                                }));
                    });
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
                boolean[] messageStarted = {false};

                stream.doOnNext(chatResponse -> {
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

                    // Process text content
                    String text = output.getText();
                    if (text != null && !text.isEmpty()) {
                        textAccumulator.append(text);
                        sink.tryEmitNext(new AgentEvent.MessageUpdate(agentId, conversationId, text));
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

                    // Check for thinking content in metadata
                    Map<String, Object> metadata = output.getMetadata();
                    if (metadata != null && metadata.containsKey("reasoningContent")) {
                        Object reasoning = metadata.get("reasoningContent");
                        if (reasoning instanceof String thinkingText) {
                            contentBlocks.add(new ContentBlock.Thinking(thinkingText));
                        }
                    }
                }).doOnComplete(() -> {
                    // Build final assistant message
                    if (textAccumulator.length() > 0) {
                        contentBlocks.addFirst(new ContentBlock.Text(textAccumulator.toString()));
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

                    sink.tryEmitNext(new AgentEvent.ToolExecutionEnd(agentId, conversationId, toolUse, modifiedResult));
                    return modifiedResult;
                })
                .then();
    }

    // --- Helpers ---

    private void addMessage(AgentMessage message) {
        messages.add(message);
        conversationStore.appendMessage(agentId, conversationId, message)
                .doOnError(e -> log.error("Failed to persist message to conversation store", e))
                .subscribe();
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
        List<Message> result = new ArrayList<>();
        for (AgentMessage msg : agentMessages) {
            switch (msg.role()) {
                case USER -> {
                    String text = msg.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .collect(Collectors.joining("\n"));
                    result.add(new UserMessage(text));
                }
                case ASSISTANT -> {
                    String text = msg.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .collect(Collectors.joining("\n"));

                    List<AssistantMessage.ToolCall> toolCalls = msg.content().stream()
                            .filter(b -> b instanceof ContentBlock.ToolUse)
                            .map(b -> {
                                ContentBlock.ToolUse tu = (ContentBlock.ToolUse) b;
                                return new AssistantMessage.ToolCall(
                                        tu.id(), "function", tu.name(), tu.input().toString());
                            })
                            .toList();

                    result.add(AssistantMessage.builder()
                            .content(text)
                            .toolCalls(toolCalls)
                            .build());
                }
                case TOOL_RESULT -> {
                    for (ContentBlock block : msg.content()) {
                        if (block instanceof ContentBlock.ToolResult tr) {
                            String resultText = tr.content().stream()
                                    .filter(b -> b instanceof ContentBlock.Text)
                                    .map(b -> ((ContentBlock.Text) b).text())
                                    .collect(Collectors.joining("\n"));
                            result.add(ToolResponseMessage.builder()
                                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                                            tr.toolUseId(), tr.toolUseId(), resultText)))
                                    .build());
                        }
                    }
                }
            }
        }
        return result;
    }

    private Prompt buildPrompt(List<Message> messages) {
        List<Message> allMessages = new ArrayList<>();
        allMessages.add(new SystemMessage(config.systemPrompt()));
        allMessages.addAll(messages);

        var optionsBuilder = AnthropicChatOptions.builder()
                .model(config.model())
                .maxTokens(config.maxTokens());

        if (config.thinkingLevel() != ThinkingLevel.OFF) {
            optionsBuilder.thinking(AnthropicApi.ThinkingType.ENABLED, config.thinkingLevel().getBudgetTokens());
            optionsBuilder.temperature(1.0);
        }

        if (!config.tools().isEmpty()) {
            List<ToolCallback> callbacks = config.tools().stream()
                    .map(AgentToolCallbackAdapter::new)
                    .map(a -> (ToolCallback) a)
                    .toList();
            optionsBuilder.toolCallbacks(callbacks);
            optionsBuilder.internalToolExecutionEnabled(false);
        }

        return new Prompt(allMessages, optionsBuilder.build());
    }
}
