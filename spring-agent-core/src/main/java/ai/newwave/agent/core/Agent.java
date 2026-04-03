package ai.newwave.agent.core;

import ai.newwave.agent.config.AgentConfig;
import ai.newwave.agent.event.AgentEvent;
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.state.model.ConversationStatus;
import ai.newwave.agent.state.spi.ConversationStateManager;
import ai.newwave.agent.state.spi.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent client with optional conversation state management.
 * When a {@link ConversationStateManager} is provided, supports:
 * <ul>
 *   <li>Conversation locking (prevents concurrent interleaving)</li>
 *   <li>{@link #steer} — inject messages into the running loop</li>
 *   <li>{@link #followUp} — queue messages for after the loop completes</li>
 *   <li>{@link #abort} — request the running loop to stop</li>
 * </ul>
 *
 * Without a state manager, behaves as a stateless fire-and-forget client.
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    public static final String DEFAULT_CONVERSATION = "default";

    private final AgentConfig config;
    private final ChatModel chatModel;
    private final ConversationStore conversationStore;
    private final ConversationStateManager stateManager;

    public Agent(AgentConfig config, ChatModel chatModel, ConversationStore conversationStore) {
        this(config, chatModel, conversationStore, null);
    }

    public Agent(AgentConfig config, ChatModel chatModel, ConversationStore conversationStore,
                 ConversationStateManager stateManager) {
        this.config = config;
        this.chatModel = chatModel;
        this.conversationStore = conversationStore;
        this.stateManager = stateManager;
    }

    /**
     * Stream agent events for a conversation turn.
     * If a state manager is configured, acquires a lock first.
     * If the conversation is busy, the message is queued as a follow-up.
     */
    public Flux<AgentEvent> stream(AgentRequest request) {
        String agentId = request.agentId();
        String conversationId = request.conversationId();
        AgentMessage message = request.message();
        var attributes = request.attributes();

        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        if (stateManager != null) {
            stateManager.tryAcquire(agentId, conversationId)
                    .subscribe(acquired -> {
                        if (!acquired) {
                            // Conversation is busy — queue as follow-up
                            log.info("Conversation {}:{} is busy, queuing as follow-up", agentId, conversationId);
                            stateManager.enqueueFollowUp(agentId, conversationId, message)
                                    .doOnError(e -> log.error("Failed to enqueue follow-up", e))
                                    .subscribe();
                            sink.tryEmitNext(new AgentEvent.AgentEnd(agentId, conversationId, "queued"));
                            sink.tryEmitComplete();
                            return;
                        }
                        runWithLock(agentId, conversationId, message, attributes, sink);
                    }, error -> {
                        log.error("Failed to acquire lock for {}:{}", agentId, conversationId, error);
                        runStateless(agentId, conversationId, message, attributes, sink);
                    });
        } else {
            runStateless(agentId, conversationId, message, attributes, sink);
        }

        return sink.asFlux();
    }

    private void runWithLock(String agentId, String conversationId, AgentMessage message,
                             java.util.Map<String, Object> attributes, Sinks.Many<AgentEvent> sink) {
        conversationStore.loadMessages(agentId, conversationId)
                .collectList()
                .subscribe(existingMessages -> {
                    List<AgentMessage> messages = new ArrayList<>(existingMessages);
                    messages.add(message);

                    conversationStore.appendMessage(agentId, conversationId, message)
                            .doOnError(e -> log.error("Failed to persist user message", e))
                            .subscribe();

                    sink.tryEmitNext(new AgentEvent.AgentStart(agentId, conversationId));

                    AgentLoop loop = new AgentLoop(
                            agentId, conversationId, messages, attributes,
                            config, chatModel, sink, conversationStore, stateManager);

                    loop.run()
                            .then(drainFollowUps(agentId, conversationId, messages, attributes, sink))
                            .doOnSuccess(v -> {
                                stateManager.release(agentId, conversationId)
                                        .doOnError(e -> log.error("Failed to release lock", e))
                                        .subscribe();
                                sink.tryEmitNext(new AgentEvent.AgentEnd(agentId, conversationId, loop.getTokenUsage()));
                                sink.tryEmitComplete();
                            })
                            .doOnError(e -> {
                                log.error("Agent loop failed on {}:{}", agentId, conversationId, e);
                                stateManager.release(agentId, conversationId)
                                        .doOnError(re -> log.error("Failed to release lock on error", re))
                                        .subscribe();
                                sink.tryEmitNext(new AgentEvent.AgentEnd(agentId, conversationId, e.getMessage()));
                                sink.tryEmitComplete();
                            })
                            .subscribe();
                }, error -> {
                    log.error("Failed to load messages for {}:{}", agentId, conversationId, error);
                    stateManager.release(agentId, conversationId)
                            .doOnError(re -> log.error("Failed to release lock on error", re))
                            .subscribe();
                    sink.tryEmitNext(new AgentEvent.AgentEnd(agentId, conversationId, error.getMessage()));
                    sink.tryEmitComplete();
                });
    }

    /**
     * Outer loop: drain follow-up queue after the inner loop completes.
     */
    private Mono<Void> drainFollowUps(String agentId, String conversationId,
                                       List<AgentMessage> messages,
                                       java.util.Map<String, Object> attributes,
                                       Sinks.Many<AgentEvent> sink) {
        return stateManager.dequeueFollowUp(agentId, conversationId)
                .flatMap(followUp -> {
                    messages.add(followUp);
                    conversationStore.appendMessage(agentId, conversationId, followUp)
                            .doOnError(e -> log.error("Failed to persist follow-up message", e))
                            .subscribe();

                    AgentLoop loop = new AgentLoop(
                            agentId, conversationId, messages, attributes,
                            config, chatModel, sink, conversationStore, stateManager);

                    return loop.run()
                            .then(drainFollowUps(agentId, conversationId, messages, attributes, sink));
                });
    }

    private void runStateless(String agentId, String conversationId, AgentMessage message,
                              java.util.Map<String, Object> attributes, Sinks.Many<AgentEvent> sink) {
        conversationStore.loadMessages(agentId, conversationId)
                .collectList()
                .subscribe(existingMessages -> {
                    List<AgentMessage> messages = new ArrayList<>(existingMessages);
                    messages.add(message);

                    conversationStore.appendMessage(agentId, conversationId, message)
                            .doOnError(e -> log.error("Failed to persist user message", e))
                            .subscribe();

                    sink.tryEmitNext(new AgentEvent.AgentStart(agentId, conversationId));

                    AgentLoop loop = new AgentLoop(
                            agentId, conversationId, messages, attributes,
                            config, chatModel, sink, conversationStore, null);

                    loop.run()
                            .doOnSuccess(v -> {
                                sink.tryEmitNext(new AgentEvent.AgentEnd(agentId, conversationId, loop.getTokenUsage()));
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
    }

    // --- Stateful operations (require ConversationStateManager) ---

    /**
     * Inject a message into the currently running loop.
     * The message will be appended to the conversation before the next LLM call.
     */
    public Mono<Void> steer(String agentId, String conversationId, String message) {
        if (stateManager == null) return Mono.error(new UnsupportedOperationException("No ConversationStateManager configured"));
        return stateManager.enqueueSteer(agentId, conversationId, AgentMessage.user(message));
    }

    /**
     * Queue a message to run after the current loop completes.
     */
    public Mono<Void> followUp(String agentId, String conversationId, String message) {
        if (stateManager == null) return Mono.error(new UnsupportedOperationException("No ConversationStateManager configured"));
        return stateManager.enqueueFollowUp(agentId, conversationId, AgentMessage.user(message));
    }

    /**
     * Request the running loop to abort.
     */
    public Mono<Void> abort(String agentId, String conversationId) {
        if (stateManager == null) return Mono.error(new UnsupportedOperationException("No ConversationStateManager configured"));
        return stateManager.requestAbort(agentId, conversationId);
    }

    /**
     * Get the current status of a conversation.
     */
    public Mono<ConversationStatus> getStatus(String agentId, String conversationId) {
        if (stateManager == null) return Mono.just(ConversationStatus.IDLE);
        return stateManager.getStatus(agentId, conversationId);
    }

    public AgentConfig getConfig() {
        return config;
    }

    public ConversationStore getConversationStore() {
        return conversationStore;
    }
}
