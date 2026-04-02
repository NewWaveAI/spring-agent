package ai.newwave.agent.state.spi;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.state.model.ConversationStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * SPI for managing conversation state across distributed instances.
 * Provides locking, status tracking, and message queuing for steer/followUp.
 */
public interface ConversationStateManager {

    /**
     * Get the current status of a conversation.
     */
    Mono<ConversationStatus> getStatus(String agentId, String conversationId);

    /**
     * Try to acquire a lock for a conversation (set status to RUNNING).
     * Returns true if acquired, false if already RUNNING.
     */
    Mono<Boolean> tryAcquire(String agentId, String conversationId);

    /**
     * Release a conversation lock (set status to IDLE).
     */
    Mono<Void> release(String agentId, String conversationId);

    /**
     * Set status to ABORTING so the running loop can check and stop.
     */
    Mono<Void> requestAbort(String agentId, String conversationId);

    /**
     * Check if the conversation is in ABORTING status.
     */
    Mono<Boolean> isAborting(String agentId, String conversationId);

    /**
     * Enqueue a follow-up message to run after the current loop completes.
     */
    Mono<Void> enqueueFollowUp(String agentId, String conversationId, AgentMessage message);

    /**
     * Dequeue a follow-up message. Returns empty if none.
     */
    Mono<AgentMessage> dequeueFollowUp(String agentId, String conversationId);

    /**
     * Check if there are follow-up messages.
     */
    Mono<Boolean> hasFollowUps(String agentId, String conversationId);

    /**
     * Enqueue a steering message to inject into the current running loop.
     */
    Mono<Void> enqueueSteer(String agentId, String conversationId, AgentMessage message);

    /**
     * Drain all steering messages. Returns empty flux if none.
     */
    Flux<AgentMessage> drainSteering(String agentId, String conversationId);
}
