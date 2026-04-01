package ai.newwave.agent.core;

import ai.newwave.agent.config.AgentConfig;
import ai.newwave.agent.event.AgentEvent;
import ai.newwave.agent.event.AgentEventListener;
import ai.newwave.agent.event.EventEmitter;
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.state.AgentState;
import ai.newwave.agent.state.ChannelState;
import ai.newwave.agent.state.AgentStatus;
import ai.newwave.agent.state.spi.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Main public API for the stateful agent.
 * Supports multiple concurrent channels, each with its own conversation context.
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    public static final String DEFAULT_CHANNEL = "default";

    private final AgentState state;
    private final ChatModel chatModel;
    private final EventEmitter emitter = new EventEmitter();

    public Agent(AgentConfig config, ChatModel chatModel, ChannelManager channelManager) {
        this.state = new AgentState(config, channelManager);
        this.chatModel = chatModel;
    }

    // --- Core Operations (with channelId) ---

    /**
     * Start a new conversation turn with a user message on a specific channel.
     */
    public Mono<Void> prompt(String message, String channelId) {
        return prompt(AgentMessage.user(message), channelId);
    }

    /**
     * Start a new conversation turn with a pre-built message on a specific channel.
     */
    public Mono<Void> prompt(AgentMessage message, String channelId) {
        ChannelState channel = state.getChannelManager().getOrCreate(channelId);
        if (channel.isRunning()) {
            return Mono.error(new IllegalStateException("Channel '" + channelId + "' is already running"));
        }

        channel.addMessage(message);
        getConversationStore().appendMessage(channelId, message)
                .doOnError(e -> log.error("Failed to persist user message", e))
                .subscribe();
        return runLoop(channel);
    }

    /**
     * Resume conversation from the current transcript on a specific channel.
     */
    public Mono<Void> continueConversation(String channelId) {
        ChannelState channel = state.getChannelManager().getOrCreate(channelId);
        if (channel.isRunning()) {
            return Mono.error(new IllegalStateException("Channel '" + channelId + "' is already running"));
        }
        if (channel.getMessages().isEmpty()) {
            return Mono.error(new IllegalStateException("No messages to continue from on channel '" + channelId + "'"));
        }

        return runLoop(channel);
    }

    // --- Backward-compatible (default channel) ---

    public Mono<Void> prompt(String message) {
        return prompt(message, DEFAULT_CHANNEL);
    }

    public Mono<Void> prompt(AgentMessage message) {
        return prompt(message, DEFAULT_CHANNEL);
    }

    public Mono<Void> continueConversation() {
        return continueConversation(DEFAULT_CHANNEL);
    }

    // --- Steering & Follow-up ---

    public void steer(String message, String channelId) {
        ChannelState channel = state.getChannelManager().getOrCreate(channelId);
        channel.getSteeringQueue().add(message);
    }

    public void steer(AgentMessage message, String channelId) {
        ChannelState channel = state.getChannelManager().getOrCreate(channelId);
        channel.getSteeringQueue().add(message);
    }

    public void followUp(String message, String channelId) {
        ChannelState channel = state.getChannelManager().getOrCreate(channelId);
        channel.getFollowUpQueue().add(message);
    }

    public void followUp(AgentMessage message, String channelId) {
        ChannelState channel = state.getChannelManager().getOrCreate(channelId);
        channel.getFollowUpQueue().add(message);
    }

    // Backward-compatible (default channel)

    public void steer(String message) {
        steer(message, DEFAULT_CHANNEL);
    }

    public void steer(AgentMessage message) {
        steer(message, DEFAULT_CHANNEL);
    }

    public void followUp(String message) {
        followUp(message, DEFAULT_CHANNEL);
    }

    public void followUp(AgentMessage message) {
        followUp(message, DEFAULT_CHANNEL);
    }

    // --- Event Subscription ---

    public Disposable subscribe(AgentEventListener listener) {
        return emitter.addListener(listener);
    }

    public Flux<AgentEvent> events() {
        return emitter.asFlux();
    }

    /**
     * Get events filtered to a specific channel.
     */
    public Flux<AgentEvent> events(String channelId) {
        return emitter.asFlux()
                .filter(e -> channelId.equals(e.channelId()));
    }

    // --- Control ---

    public void abort(String channelId) {
        ChannelState channel = state.getChannelManager().get(channelId);
        if (channel != null && channel.isRunning()) {
            channel.setStatus(AgentStatus.ABORTING);
            Disposable run = channel.getCurrentRun().get();
            if (run != null && !run.isDisposed()) {
                run.dispose();
            }
        }
    }

    public void abort() {
        abort(DEFAULT_CHANNEL);
    }

    public Mono<Void> waitForIdle(String channelId) {
        ChannelState channel = state.getChannelManager().get(channelId);
        if (channel == null || !channel.isRunning()) {
            return Mono.empty();
        }
        return channel.getIdleSink().asMono();
    }

    public Mono<Void> waitForIdle() {
        return waitForIdle(DEFAULT_CHANNEL);
    }

    public void reset(String channelId) {
        abort(channelId);
        ChannelState channel = state.getChannelManager().get(channelId);
        if (channel != null) {
            channel.reset();
        }
    }

    public void reset() {
        reset(DEFAULT_CHANNEL);
    }

    // --- Channel Management ---

    public List<String> listChannels() {
        return state.getChannelManager().listActive();
    }

    public void deleteChannel(String channelId) {
        abort(channelId);
        state.getChannelManager().delete(channelId);
    }

    // --- State Access ---

    public AgentState getState() {
        return state;
    }

    public List<AgentMessage> getMessages(String channelId) {
        ChannelState channel = state.getChannelManager().get(channelId);
        if (channel == null) {
            return List.of();
        }
        return channel.getMessages();
    }

    public List<AgentMessage> getMessages() {
        return getMessages(DEFAULT_CHANNEL);
    }

    public AgentStatus getStatus(String channelId) {
        ChannelState channel = state.getChannelManager().get(channelId);
        if (channel == null) {
            return AgentStatus.IDLE;
        }
        return channel.getStatus();
    }

    public AgentStatus getStatus() {
        return getStatus(DEFAULT_CHANNEL);
    }

    public EventEmitter getEmitter() {
        return emitter;
    }

    // --- Internal ---

    private ConversationStore getConversationStore() {
        return state.getChannelManager().getConversationStore();
    }

    private Mono<Void> runLoop(ChannelState channel) {
        channel.setStatus(AgentStatus.RUNNING);
        channel.setErrorMessage(null);
        channel.resetIdleSink();

        String agentId = state.getConfig().agentId();
        emitter.emit(new AgentEvent.AgentStart(agentId, channel.getChannelId()));

        AgentLoop loop = new AgentLoop(
                channel, state.getConfig(), chatModel, emitter, getConversationStore());

        Mono<Void> execution = loop.run()
                .doOnSuccess(v -> {
                    channel.setStatus(AgentStatus.IDLE);
                    emitter.emit(new AgentEvent.AgentEnd(agentId, channel.getChannelId()));
                    channel.getIdleSink().tryEmitEmpty();
                })
                .doOnError(e -> {
                    log.error("Agent loop failed on channel {}", channel.getChannelId(), e);
                    channel.setStatus(AgentStatus.IDLE);
                    channel.setErrorMessage(e.getMessage());
                    emitter.emit(new AgentEvent.AgentEnd(agentId, channel.getChannelId(), e.getMessage()));
                    channel.getIdleSink().tryEmitEmpty();
                })
                .onErrorComplete()
                .subscribeOn(Schedulers.boundedElastic());

        Disposable disposable = execution.subscribe();
        channel.getCurrentRun().set(disposable);

        return waitForIdle(channel.getChannelId());
    }
}
