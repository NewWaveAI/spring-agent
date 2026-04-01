package ai.newwave.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.newwave.agent.config.AgentConfig;
import ai.newwave.agent.config.AgentHooks;
import ai.newwave.agent.config.AgentLoopConfig;
import ai.newwave.agent.event.AgentEvent;
import ai.newwave.agent.event.EventEmitter;
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.model.MessageRole;
import ai.newwave.agent.model.ThinkingLevel;
import ai.newwave.agent.model.ToolExecutionMode;
import ai.newwave.agent.state.ChannelState;
import ai.newwave.agent.state.spi.ConversationStore;
import ai.newwave.agent.tool.AgentTool;
import ai.newwave.agent.tool.AgentToolResult;
import ai.newwave.agent.tool.ToolCallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Core agent loop implementing the outer (follow-up) and inner (tool execution) loops.
 * Operates on a single {@link ChannelState} — one loop instance per channel per run.
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChannelState channel;
    private final AgentConfig config;
    private final ChatModel chatModel;
    private final EventEmitter emitter;
    private final ConversationStore conversationStore;

    public AgentLoop(
            ChannelState channel,
            AgentConfig config,
            ChatModel chatModel,
            EventEmitter emitter,
            ConversationStore conversationStore
    ) {
        this.channel = channel;
        this.config = config;
        this.chatModel = chatModel;
        this.emitter = emitter;
        this.conversationStore = conversationStore;
    }

    /**
     * Run the full agent loop (outer + inner).
     */
    public Mono<Void> run() {
        return outerLoop(0);
    }

    private Mono<Void> outerLoop(int turnNumber) {
        return innerLoop(turnNumber)
                .then(Mono.defer(() -> {
                    if (channel.isAborting()) {
                        return Mono.empty();
                    }
                    if (channel.getFollowUpQueue().hasMessages()) {
                        AgentMessage followUp = channel.getFollowUpQueue().poll();
                        if (followUp != null) {
                            addMessage(followUp);
                            return outerLoop(turnNumber + 1);
                        }
                    }
                    return Mono.empty();
                }));
    }

    private Mono<Void> innerLoop(int turnNumber) {
        return Mono.defer(() -> {
            if (channel.isAborting()) {
                return Mono.empty();
            }

            AgentLoopConfig loopConfig = config.loopConfig();
            if (turnNumber >= loopConfig.maxTurns()) {
                log.warn("Max turns ({}) reached, stopping agent loop", loopConfig.maxTurns());
                return Mono.empty();
            }

            // Drain steering queue
            drainSteeringQueue();

            emitter.emit(new AgentEvent.TurnStart(config.agentId(), channel.getChannelId(), turnNumber));

            // Build Spring AI messages from channel state
            AgentHooks hooks = loopConfig.hooks();
            List<AgentMessage> context = hooks.transformContext(channel.getMessages());
            List<AgentMessage> llmMessages = hooks.convertToLlm(context);

            // Convert to Spring AI messages and call LLM
            List<Message> springMessages = toSpringMessages(llmMessages);
            Prompt prompt = buildPrompt(springMessages);

            return streamLlmResponse(prompt, turnNumber)
                    .then(Mono.defer(() -> {
                        // Check for tool calls in the last assistant message
                        List<ContentBlock.ToolUse> toolCalls = extractToolCalls();
                        if (toolCalls.isEmpty()) {
                            emitter.emit(new AgentEvent.TurnEnd(config.agentId(), channel.getChannelId(), turnNumber));
                            return Mono.empty();
                        }

                        // Execute tools
                        return executeTools(toolCalls)
                                .then(Mono.defer(() -> {
                                    emitter.emit(new AgentEvent.TurnEnd(config.agentId(), channel.getChannelId(), turnNumber));
                                    return innerLoop(turnNumber + 1);
                                }));
                    }));
        });
    }

    /**
     * Stream LLM response, accumulating content blocks and emitting events.
     */
    private Mono<Void> streamLlmResponse(Prompt prompt, int turnNumber) {
        return Mono.create(sink -> {
            try {
                // Use streaming to get incremental responses
                Flux<ChatResponse> stream = chatModel.stream(prompt);

                List<ContentBlock> contentBlocks = new ArrayList<>();
                StringBuilder textAccumulator = new StringBuilder();
                boolean[] messageStarted = {false};

                stream.doOnNext(chatResponse -> {
                    if (channel.isAborting()) return;

                    Generation generation = chatResponse.getResult();
                    if (generation == null) return;

                    AssistantMessage output = generation.getOutput();
                    if (output == null) return;

                    // Emit message_start on first chunk
                    if (!messageStarted[0]) {
                        messageStarted[0] = true;
                        emitter.emit(new AgentEvent.MessageStart(
                                config.agentId(), channel.getChannelId(),
                                AgentMessage.assistant(List.of())));
                    }

                    // Process text content
                    String text = output.getText();
                    if (text != null && !text.isEmpty()) {
                        textAccumulator.append(text);
                        emitter.emit(new AgentEvent.MessageUpdate(config.agentId(), channel.getChannelId(), text));
                    }

                    // Check for tool calls in metadata
                    if (output.getToolCalls() != null) {
                        for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                            try {
                                JsonNode inputNode = objectMapper.readTree(toolCall.arguments());
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
                        emitter.emit(new AgentEvent.MessageEnd(config.agentId(), channel.getChannelId(), assistantMessage));
                    }

                    sink.success();
                }).doOnError(error -> {
                    log.error("LLM streaming error", error);
                    channel.setErrorMessage(error.getMessage());
                    sink.error(error);
                }).subscribe();

            } catch (Exception e) {
                log.error("Failed to initiate LLM stream", e);
                sink.error(e);
            }
        });
    }

    /**
     * Execute tool calls sequentially or in parallel based on config.
     */
    private Mono<Void> executeTools(List<ContentBlock.ToolUse> toolCalls) {
        ToolExecutionMode mode = config.loopConfig().toolExecutionMode();
        AgentHooks hooks = config.loopConfig().hooks();

        if (mode == ToolExecutionMode.PARALLEL) {
            return Flux.fromIterable(toolCalls)
                    .flatMap(toolUse -> executeSingleTool(toolUse, hooks))
                    .then();
        } else {
            return Flux.fromIterable(toolCalls)
                    .concatMap(toolUse -> executeSingleTool(toolUse, hooks))
                    .then();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> executeSingleTool(ContentBlock.ToolUse toolUse, AgentHooks hooks) {
        return hooks.beforeToolCall(toolUse.name(), toolUse)
                .flatMap(beforeResult -> {
                    if (!beforeResult.proceed()) {
                        // Tool blocked - add a tool result message indicating it was blocked
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

                    // Find the tool
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

                    channel.addPendingToolCall(toolUse.id());
                    emitter.emit(new AgentEvent.ToolExecutionStart(config.agentId(), channel.getChannelId(), toolUse));

                    // Deserialize parameters and execute
                    Object params;
                    try {
                        params = objectMapper.treeToValue(toolUse.input(), tool.parameterType());
                    } catch (Exception e) {
                        log.error("Failed to deserialize tool parameters for {}", toolUse.name(), e);
                        AgentToolResult<?> errorResult = AgentToolResult.error(
                                "Parameter deserialization failed: " + e.getMessage());
                        return finishToolExecution(toolUse, errorResult, hooks);
                    }

                    ToolCallContext context = new ToolCallContext(toolUse.id(), toolUse.name(), params);

                    @SuppressWarnings("unchecked")
                    Mono<AgentToolResult<?>> execution = (Mono<AgentToolResult<?>>) tool.execute(context);

                    return execution
                            .onErrorResume(e -> {
                                log.error("Tool execution failed for {}", toolUse.name(), e);
                                return Mono.just(AgentToolResult.error("Tool execution failed: " + e.getMessage()));
                            })
                            .flatMap(result -> finishToolExecution(toolUse, result, hooks));
                });
    }

    private Mono<Void> finishToolExecution(ContentBlock.ToolUse toolUse, AgentToolResult<?> result, AgentHooks hooks) {
        return hooks.afterToolCall(toolUse.name(), toolUse, result)
                .map(modifiedResult -> {
                    channel.removePendingToolCall(toolUse.id());

                    AgentMessage resultMessage = AgentMessage.toolResult(
                            toolUse.id(), modifiedResult.content(), modifiedResult.isError());
                    addMessage(resultMessage);

                    emitter.emit(new AgentEvent.ToolExecutionEnd(config.agentId(), channel.getChannelId(), toolUse, modifiedResult));
                    return modifiedResult;
                })
                .then();
    }

    // --- Helpers ---

    /**
     * Add a message to both in-memory channel state and the conversation store.
     */
    private void addMessage(AgentMessage message) {
        channel.addMessage(message);
        conversationStore.appendMessage(channel.getChannelId(), message)
                .doOnError(e -> log.error("Failed to persist message to conversation store", e))
                .subscribe();
    }

    private void drainSteeringQueue() {
        List<AgentMessage> steering = channel.getSteeringQueue().drainAll();
        for (AgentMessage msg : steering) {
            addMessage(msg);
        }
    }

    private List<ContentBlock.ToolUse> extractToolCalls() {
        List<AgentMessage> messages = channel.getMessages();
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

    /**
     * Convert AgentMessages to Spring AI Message types.
     */
    private List<Message> toSpringMessages(List<AgentMessage> agentMessages) {
        List<Message> messages = new ArrayList<>();
        for (AgentMessage msg : agentMessages) {
            switch (msg.role()) {
                case USER -> {
                    String text = msg.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .collect(Collectors.joining("\n"));
                    messages.add(new UserMessage(text));
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

                    messages.add(AssistantMessage.builder()
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
                            messages.add(ToolResponseMessage.builder()
                                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                                            tr.toolUseId(), tr.toolUseId(), resultText)))
                                    .build());
                        }
                    }
                }
            }
        }
        return messages;
    }

    /**
     * Build a Spring AI Prompt with system message, chat options, and tool definitions.
     */
    private Prompt buildPrompt(List<Message> messages) {
        // Add system message at the beginning
        List<Message> allMessages = new ArrayList<>();
        allMessages.add(new SystemMessage(config.systemPrompt()));
        allMessages.addAll(messages);

        // Build Anthropic-specific options
        var optionsBuilder = AnthropicChatOptions.builder()
                .model(config.model())
                .maxTokens(config.maxTokens());

        // Configure thinking if enabled
        if (config.thinkingLevel() != ThinkingLevel.OFF) {
            optionsBuilder.thinkingEnabled(config.thinkingLevel().getBudgetTokens());
        }

        // Register tools as ToolCallbacks that delegate to our AgentTool framework
        if (!config.tools().isEmpty()) {
            List<ToolCallback> callbacks = config.tools().stream()
                    .map(AgentToolCallbackAdapter::new)
                    .map(a -> (ToolCallback) a)
                    .toList();
            optionsBuilder.toolCallbacks(callbacks);

            // Disable Spring AI's internal tool execution - we handle it ourselves
            optionsBuilder.internalToolExecutionEnabled(false);
        }

        return new Prompt(allMessages, optionsBuilder.build());
    }
}
