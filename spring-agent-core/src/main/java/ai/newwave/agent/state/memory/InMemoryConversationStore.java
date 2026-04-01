package ai.newwave.agent.state.memory;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.state.spi.ConversationStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory conversation store for development and testing.
 */
public class InMemoryConversationStore implements ConversationStore {

    private final ConcurrentHashMap<String, List<AgentMessage>> channels = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> appendMessage(String channelId, AgentMessage message) {
        return Mono.fromRunnable(() ->
                channels.computeIfAbsent(channelId, k -> new CopyOnWriteArrayList<>()).add(message));
    }

    @Override
    public Flux<AgentMessage> loadMessages(String channelId) {
        List<AgentMessage> messages = channels.get(channelId);
        if (messages == null) {
            return Flux.empty();
        }
        return Flux.fromIterable(messages);
    }

    @Override
    public Mono<Void> replaceMessages(String channelId, List<AgentMessage> messages) {
        return Mono.fromRunnable(() -> {
            List<AgentMessage> list = new CopyOnWriteArrayList<>(messages);
            channels.put(channelId, list);
        });
    }

    @Override
    public Mono<Void> deleteChannel(String channelId) {
        return Mono.fromRunnable(() -> channels.remove(channelId));
    }

    @Override
    public Flux<String> listChannelIds() {
        return Flux.fromIterable(channels.keySet());
    }
}
