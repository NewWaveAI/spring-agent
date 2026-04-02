package ai.newwave.agent.core;

import ai.newwave.agent.config.AgentConfig;
import ai.newwave.agent.event.AgentEvent;
import ai.newwave.agent.event.AgentEventType;
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.model.ThinkingLevel;
import ai.newwave.agent.state.spi.ConversationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        return createLoop(messages, ThinkingLevel.OFF);
    }

    private AgentLoop createLoop(List<AgentMessage> messages, ThinkingLevel thinkingLevel) {
        AgentConfig config = AgentConfig.builder()
                .thinkingLevel(thinkingLevel)
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
        // Thinking comes first, then text
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(Flux.just(
                thinkingChunk("I should greet"),
                thinkingAndTextChunk("I should greet the user", "Hello!"),
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
}
