package ai.newwave.agent.state.spi;

import ai.newwave.agent.model.AgentMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * SPI for persisting conversation message history per channel.
 */
public interface ConversationStore {

    Mono<Void> appendMessage(String channelId, AgentMessage message);

    Flux<AgentMessage> loadMessages(String channelId);

    Mono<Void> replaceMessages(String channelId, List<AgentMessage> messages);

    Mono<Void> deleteChannel(String channelId);

    Flux<String> listChannelIds();
}
