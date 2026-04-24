package ai.newwave.agent.state.spi;

import ai.newwave.agent.model.AgentMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * SPI for persisting conversation message history, scoped by agentId and conversationId.
 */
public interface ConversationStore {

    Mono<Void> appendMessage(String agentId, String conversationId, AgentMessage message);

    /**
     * Persist several messages as one atomic, ordered unit. Implementations must insert
     * the messages in list order within a single transaction so the assigned {@code sequence}
     * values (and thus the {@code ORDER BY sequence} replay order) match the caller's logical
     * order. Used by the agent loop to flush an assistant message together with its tool_results
     * at turn end.
     *
     * <p>The default implementation falls back to per-message {@link #appendMessage}, which is
     * <strong>not</strong> atomic and is <strong>not</strong> safe against concurrent writers.
     * Durable stores should override.
     */
    default Mono<Void> appendMessages(String agentId, String conversationId, List<AgentMessage> messages) {
        return Flux.fromIterable(messages)
                .concatMap(message -> appendMessage(agentId, conversationId, message))
                .then();
    }

    Flux<AgentMessage> loadMessages(String agentId, String conversationId);

    Mono<Void> replaceMessages(String agentId, String conversationId, List<AgentMessage> messages);

    Mono<Void> deleteConversation(String agentId, String conversationId);

    Flux<String> listConversationIds(String agentId);
}
