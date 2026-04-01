package ai.newwave.agent.core;

import ai.newwave.agent.config.AgentConfig;
import ai.newwave.agent.event.AgentEvent;
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.state.spi.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateless agent client. Configured once, each call is self-contained.
 * Conversation state lives in {@link ConversationStore}, not in the agent.
 * Safe to use behind a load balancer — any instance can handle any request.
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    public static final String DEFAULT_CONVERSATION = "default";

    private final AgentConfig config;
    private final ChatModel chatModel;
    private final ConversationStore conversationStore;

    public Agent(AgentConfig config, ChatModel chatModel, ConversationStore conversationStore) {
        this.config = config;
        this.chatModel = chatModel;
        this.conversationStore = conversationStore;
    }

    /**
     * Stream agent events for a conversation turn.
     * Loads existing messages from the store, appends the new message,
     * runs the agent loop, and persists all new messages.
     */
    public Flux<AgentEvent> stream(AgentRequest request) {
        String agentId = request.agentId();
        String conversationId = request.conversationId();
        AgentMessage message = request.message();

        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        conversationStore.loadMessages(agentId, conversationId)
                .collectList()
                .subscribe(existingMessages -> {
                    List<AgentMessage> messages = new ArrayList<>(existingMessages);
                    messages.add(message);

                    // Persist the user message
                    conversationStore.appendMessage(agentId, conversationId, message)
                            .doOnError(e -> log.error("Failed to persist user message", e))
                            .subscribe();

                    sink.tryEmitNext(new AgentEvent.AgentStart(agentId, conversationId));

                    AgentLoop loop = new AgentLoop(
                            agentId, conversationId, messages,
                            config, chatModel, sink, conversationStore);

                    loop.run()
                            .doOnSuccess(v -> {
                                sink.tryEmitNext(new AgentEvent.AgentEnd(agentId, conversationId));
                                sink.tryEmitComplete();
                            })
                            .doOnError(e -> {
                                log.error("Agent loop failed on {}:{}", agentId, conversationId, e);
                                sink.tryEmitNext(new AgentEvent.AgentEnd(agentId, conversationId, e.getMessage()));
                                sink.tryEmitComplete();
                            })
                            .subscribe();
                }, error -> {
                    log.error("Failed to load messages for {}:{}", agentId, conversationId, error);
                    sink.tryEmitNext(new AgentEvent.AgentEnd(agentId, conversationId, error.getMessage()));
                    sink.tryEmitComplete();
                });

        return sink.asFlux();
    }

    public AgentConfig getConfig() {
        return config;
    }

    public ConversationStore getConversationStore() {
        return conversationStore;
    }
}
