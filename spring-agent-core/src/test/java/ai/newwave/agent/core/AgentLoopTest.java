package ai.newwave.agent.core;

import ai.newwave.agent.config.AgentConfig;
import ai.newwave.agent.config.AgentLoopConfig;
import ai.newwave.agent.event.AgentEvent;
import ai.newwave.agent.event.AgentEventType;
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.model.ThinkingLevel;
import ai.newwave.agent.state.spi.ConversationStore;
import ai.newwave.agent.tool.AgentTool;
import ai.newwave.agent.tool.AgentToolResult;
import ai.newwave.agent.tool.ToolCallContext;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentLoopTest {

    private ChatModel chatModel;
    private ConversationStore conversationStore;
    private Sinks.Many<AgentEvent> sink;
    private List<AgentEvent> collectedEvents;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        conversationStore = mock(ConversationStore.class);
        when(conversationStore.appendMessage(any(), any(), any())).thenReturn(Mono.empty());

        sink = Sinks.many().multicast().onBackpressureBuffer();
        collectedEvents = new ArrayList<>();
        sink.asFlux().subscribe(collectedEvents::add);
    }

    private AgentLoop createLoop(List<AgentMessage> messages) {
        return createLoop(messages, ThinkingLevel.OFF, List.of());
    }

    private AgentLoop createLoop(List<AgentMessage> messages, ThinkingLevel thinkingLevel) {
        return createLoop(messages, thinkingLevel, List.of());
    }

    private AgentLoop createLoop(List<AgentMessage> messages, List<AgentTool<?, ?>> tools) {
        return createLoop(messages, ThinkingLevel.OFF, tools);
    }

    private AgentLoop createLoop(List<AgentMessage> messages, ThinkingLevel thinkingLevel, List<AgentTool<?, ?>> tools) {
        AgentConfig config = AgentConfig.builder()
                .thinkingLevel(thinkingLevel)
                .tools(tools)
                .build();
        return new AgentLoop("agent-1", "conv-1", messages, Map.of(), config, chatModel, sink, conversationStore);
    }

    // --- Helpers to build mock ChatResponse stream chunks ---

    private ChatResponse textChunk(String text) {
        AssistantMessage output = AssistantMessage.builder().content(text).build();
        return ChatResponse.builder().generations(List.of(new Generation(output))).build();
    }

    private ChatResponse thinkingChunk(String accumulatedThinking) {
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", accumulatedThinking))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(output))).build();
    }

    private ChatResponse thinkingAndTextChunk(String accumulatedThinking, String text) {
        AssistantMessage output = AssistantMessage.builder()
                .content(text)
                .properties(Map.of("reasoningContent", accumulatedThinking))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(output))).build();
    }

    private ChatResponse toolCallChunk(String toolId, String toolName, String argsJson) {
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(toolId, "function", toolName, argsJson)))
                .build();
        return ChatResponse.builder().generations(List.of(new Generation(output))).build();
    }

    // Simple test tool
    record SimpleParams(@JsonPropertyDescription("input") String input) {}

    static class TestTool implements AgentTool<SimpleParams, String> {
        private final boolean terminates;
        private final boolean excluded;

        TestTool(boolean terminates) { this(terminates, false); }

        TestTool(boolean terminates, boolean excludeFromContext) {
            this.terminates = terminates;
            this.excluded = excludeFromContext;
        }

        @Override public String name() { return "test_tool"; }
        @Override public String label() { return "Test"; }
        @Override public String description() { return "A test tool"; }
        @Override public Class<SimpleParams> parameterType() { return SimpleParams.class; }
        @Override public boolean excludeFromContext() { return excluded; }

        @Override
        public Mono<AgentToolResult<String>> execute(ToolCallContext<SimpleParams> ctx) {
            if (terminates) {
                return Mono.just(AgentToolResult.terminate("terminated"));
            }
            return Mono.just(AgentToolResult.success("ok"));
        }
    }

    // --- Tests ---

    @Test
    void textOnly_emitsMessageUpdateEvents() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                textChunk("Hello"),
                textChunk(" world")
        ));

        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("Hi"));
        AgentLoop loop = createLoop(messages);
        loop.run().block();

        List<AgentEvent.MessageUpdate> updates = collectedEvents.stream()
                .filter(e -> e instanceof AgentEvent.MessageUpdate)
                .map(e -> (AgentEvent.MessageUpdate) e)
                .toList();

        assertEquals(2, updates.size());
        assertEquals("Hello", updates.get(0).delta());
        assertEquals(" world", updates.get(1).delta());

        // No thinking events
        long thinkingCount = collectedEvents.stream()
                .filter(e -> e instanceof AgentEvent.ThinkingUpdate)
                .count();
        assertEquals(0, thinkingCount);
    }

    @Test
    void thinkingOnly_emitsThinkingUpdateEvents() {
        // Spring AI accumulates thinking — each chunk has full thinking so far
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                thinkingChunk("Let me"),
                thinkingChunk("Let me think"),
                thinkingChunk("Let me think about this")
        ));

        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("Hi"));
        AgentLoop loop = createLoop(messages, ThinkingLevel.MEDIUM);
        loop.run().block();

        List<AgentEvent.ThinkingUpdate> thinkingUpdates = collectedEvents.stream()
                .filter(e -> e instanceof AgentEvent.ThinkingUpdate)
                .map(e -> (AgentEvent.ThinkingUpdate) e)
                .toList();

        assertEquals(3, thinkingUpdates.size());
        assertEquals("Let me", thinkingUpdates.get(0).delta());
        assertEquals(" think", thinkingUpdates.get(1).delta());
        assertEquals(" about this", thinkingUpdates.get(2).delta());

        // No text updates
        long textCount = collectedEvents.stream()
                .filter(e -> e instanceof AgentEvent.MessageUpdate)
                .count();
        assertEquals(0, textCount);
    }

    @Test
    void thinkingThenText_separateEventTypes() {
        // Thinking comes first in separate chunks, then text in separate chunks
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                thinkingChunk("I should greet"),
                thinkingChunk("I should greet the user"),
                textChunk("Hello!"),
                textChunk(" How are you?")
        ));

        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("Hi"));
        AgentLoop loop = createLoop(messages, ThinkingLevel.HIGH);
        loop.run().block();

        // Verify thinking deltas
        List<AgentEvent.ThinkingUpdate> thinkingUpdates = collectedEvents.stream()
                .filter(e -> e instanceof AgentEvent.ThinkingUpdate)
                .map(e -> (AgentEvent.ThinkingUpdate) e)
                .toList();

        assertEquals(2, thinkingUpdates.size());
        assertEquals("I should greet", thinkingUpdates.get(0).delta());
        assertEquals(" the user", thinkingUpdates.get(1).delta());

        // Verify text deltas
        List<AgentEvent.MessageUpdate> textUpdates = collectedEvents.stream()
                .filter(e -> e instanceof AgentEvent.MessageUpdate)
                .map(e -> (AgentEvent.MessageUpdate) e)
                .toList();

        assertEquals(2, textUpdates.size());
        assertEquals("Hello!", textUpdates.get(0).delta());
        assertEquals(" How are you?", textUpdates.get(1).delta());
    }

    @Test
    void finalMessage_containsSeparateThinkingAndTextBlocks() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                thinkingChunk("reasoning here"),
                textChunk("visible answer")
        ));

        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("Hi"));
        AgentLoop loop = createLoop(messages, ThinkingLevel.MEDIUM);
        loop.run().block();

        AgentEvent.MessageEnd messageEnd = collectedEvents.stream()
                .filter(e -> e instanceof AgentEvent.MessageEnd)
                .map(e -> (AgentEvent.MessageEnd) e)
                .findFirst()
                .orElseThrow();

        List<ContentBlock> blocks = messageEnd.message().content();
        // Thinking first, then text
        assertInstanceOf(ContentBlock.Thinking.class, blocks.get(0));
        assertInstanceOf(ContentBlock.Text.class, blocks.get(1));
        assertEquals("reasoning here", ((ContentBlock.Thinking) blocks.get(0)).thinking());
        assertEquals("visible answer", ((ContentBlock.Text) blocks.get(1)).text());
    }

    @Test
    void multiTurnContext_includesThinkingInProperties() {
        // First turn: LLM responds with thinking + text
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                thinkingChunk("my reasoning"),
                textChunk("my answer")
        ));

        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("Hi"));
        AgentLoop loop = createLoop(messages, ThinkingLevel.MEDIUM);
        loop.run().block();

        // Now verify the stored assistant message has thinking
        AgentMessage assistantMsg = messages.stream()
                .filter(m -> m.role() == ai.newwave.agent.model.MessageRole.ASSISTANT)
                .findFirst()
                .orElseThrow();

        boolean hasThinking = assistantMsg.content().stream()
                .anyMatch(b -> b instanceof ContentBlock.Thinking);
        boolean hasText = assistantMsg.content().stream()
                .anyMatch(b -> b instanceof ContentBlock.Text);

        assertTrue(hasThinking, "Assistant message should contain thinking block");
        assertTrue(hasText, "Assistant message should contain text block");
    }

    @Test
    void eventOrdering_thinkingBeforeTextBeforeEnd() {
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                thinkingChunk("think"),
                textChunk("respond")
        ));

        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("Hi"));
        AgentLoop loop = createLoop(messages, ThinkingLevel.MEDIUM);
        loop.run().block();

        // Extract event types in order (only message-related)
        List<AgentEventType> messageEventTypes = collectedEvents.stream()
                .map(AgentEvent::type)
                .filter(t -> t == AgentEventType.MESSAGE_START
                        || t == AgentEventType.MESSAGE_UPDATE
                        || t == AgentEventType.THINKING_UPDATE
                        || t == AgentEventType.MESSAGE_END)
                .toList();

        assertEquals(List.of(
                AgentEventType.MESSAGE_START,
                AgentEventType.THINKING_UPDATE,
                AgentEventType.MESSAGE_UPDATE,
                AgentEventType.MESSAGE_END
        ), messageEventTypes);
    }

    @Test
    void terminatesLoop_stopsAfterToolExecution() {
        AtomicInteger llmCallCount = new AtomicInteger(0);

        // First LLM call: returns a tool call
        // Second LLM call: should NOT happen
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            int call = llmCallCount.incrementAndGet();
            if (call == 1) {
                return Flux.just(toolCallChunk("tool-1", "test_tool", "{\"input\":\"hello\"}"));
            }
            // If we get here, the loop didn't terminate
            return Flux.just(textChunk("should not reach here"));
        });

        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("Hi"));
        AgentLoop loop = createLoop(messages, List.of(new TestTool(true)));
        loop.run().block();

        // LLM should only be called once — loop terminated after tool
        assertEquals(1, llmCallCount.get(), "Loop should stop after terminatesLoop tool");

        // Verify tool execution events were emitted
        long toolEndCount = collectedEvents.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionEnd)
                .count();
        assertEquals(1, toolEndCount);
    }

    @Test
    void normalTool_loopContinues() {
        AtomicInteger llmCallCount = new AtomicInteger(0);

        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            int call = llmCallCount.incrementAndGet();
            if (call == 1) {
                // First call: LLM requests a tool
                return Flux.just(toolCallChunk("tool-1", "test_tool", "{\"input\":\"hello\"}"));
            }
            // Second call: LLM responds with text (no more tools)
            return Flux.just(textChunk("done"));
        });

        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("Hi"));
        AgentLoop loop = createLoop(messages, List.of(new TestTool(false)));
        loop.run().block();

        // LLM should be called twice — tool doesn't terminate loop
        assertEquals(2, llmCallCount.get(), "Loop should continue after normal tool");
    }

    @Test
    void maxToolResultsInContext_limitsOlderToolPairs() {
        AtomicInteger llmCallCount = new AtomicInteger(0);

        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            int call = llmCallCount.incrementAndGet();
            if (call == 1) {
                return Flux.just(toolCallChunk("tool-1", "test_tool", "{\"input\":\"first\"}"));
            }
            if (call == 2) {
                return Flux.just(toolCallChunk("tool-2", "test_tool", "{\"input\":\"second\"}"));
            }
            if (call == 3) {
                return Flux.just(toolCallChunk("tool-3", "test_tool", "{\"input\":\"third\"}"));
            }
            return Flux.just(textChunk("done"));
        });

        // maxToolResultsInContext=2 — only last 2 tool pairs in context
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("Hi"));
        AgentConfig config = AgentConfig.builder()
                .tools(List.of(new TestTool(false)))
                .loopConfig(AgentLoopConfig.builder().maxToolResultsInContext(2).build())
                .build();
        AgentLoop loop = new AgentLoop("agent-1", "conv-1", messages, Map.of(), config, chatModel, sink, conversationStore);
        loop.run().block();

        assertEquals(4, llmCallCount.get());

        // All 3 tool uses stored in messages
        long toolUseCount = messages.stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.ToolUse)
                .count();
        assertEquals(3, toolUseCount, "All 3 tool uses should be stored in messages");
    }

    @Test
    void excludeFromContext_toolPairStrippedAcrossSessions() {
        AtomicInteger llmCallCount = new AtomicInteger(0);

        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            int call = llmCallCount.incrementAndGet();
            if (call == 1) {
                return Flux.just(toolCallChunk("tool-1", "test_tool", "{\"input\":\"ask\"}"));
            }
            // Second call: should NOT see tool_use/tool_result from first call
            return Flux.just(textChunk("got it"));
        });

        // First session: tool terminates + excludeFromContext
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("Hi"));
        AgentConfig config = AgentConfig.builder()
                .tools(List.of(new TestTool(true, true)))
                .build();
        AgentLoop loop1 = new AgentLoop("agent-1", "conv-1", messages, Map.of(), config, chatModel, sink, conversationStore);
        loop1.run().block();
        assertEquals(1, llmCallCount.get());

        // tool_use and tool_result are stored in history
        long toolUseInHistory = messages.stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.ToolUse)
                .count();
        assertEquals(1, toolUseInHistory, "Tool use should be stored in message history");

        // Second session: new AgentLoop instance, same messages + user reply
        messages.add(AgentMessage.user("Option 2"));
        AgentLoop loop2 = new AgentLoop("agent-1", "conv-1", messages, Map.of(), config, chatModel, sink, conversationStore);
        // This should work — excludeFromContext is on the tool definition, not per-instance
        loop2.run().block();
        assertEquals(2, llmCallCount.get());
    }

    @Test
    void interleavedUserMessage_reorderedAfterToolResult() {
        AtomicInteger llmCallCount = new AtomicInteger(0);

        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            int call = llmCallCount.incrementAndGet();
            if (call == 1) {
                return Flux.just(toolCallChunk("tool-1", "test_tool", "{\"input\":\"analyze\"}"));
            }
            // Second call after tool completes — should succeed
            return Flux.just(textChunk("done"));
        });

        // Simulate interleaved messages: assistant(tool_use) → user(interleaved) → tool_result
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("start"));
        messages.add(AgentMessage.assistant(List.of(
                new ContentBlock.Text("Let me analyze"),
                new ContentBlock.ToolUse("tool-1", "test_tool",
                        new ObjectMapper().createObjectNode().put("input", "analyze")))));
        messages.add(AgentMessage.user("hello?"));  // interleaved!
        messages.add(AgentMessage.toolResult("tool-1", List.of(new ContentBlock.Text("result")), false));

        AgentLoop loop = createLoop(messages, List.of(new TestTool(false)));
        // Should NOT throw — interleaved user message gets reordered
        loop.run().block();
        assertEquals(2, llmCallCount.get());
    }

    @Test
    void orphanedToolUse_strippedByPairMatching() {
        AtomicInteger llmCallCount = new AtomicInteger(0);

        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            llmCallCount.incrementAndGet();
            return Flux.just(textChunk("done"));
        });

        // Simulate orphaned tool_use (no tool_result — e.g. from terminatesLoop)
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.user("start"));
        messages.add(AgentMessage.assistant(List.of(
                new ContentBlock.Text("Let me check"),
                new ContentBlock.ToolUse("orphan-1", "test_tool",
                        new ObjectMapper().createObjectNode().put("input", "x")))));
        // No tool_result for orphan-1!
        messages.add(AgentMessage.user("continue"));

        AgentLoop loop = createLoop(messages, List.of(new TestTool(false)));
        // Should NOT throw — orphaned tool_use gets stripped
        loop.run().block();
        assertEquals(1, llmCallCount.get());
    }
}
