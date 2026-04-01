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

    Flux<AgentMessage> loadMessages(String agentId, String conversationId);

    Mono<Void> replaceMessages(String agentId, String conversationId, List<AgentMessage> messages);

    Mono<Void> deleteConversation(String agentId, String conversationId);

    Flux<String> listConversationIds(String agentId);
}
