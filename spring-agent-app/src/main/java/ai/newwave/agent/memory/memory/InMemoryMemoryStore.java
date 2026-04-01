package ai.newwave.agent.memory.memory;

import ai.newwave.agent.memory.model.Memory;
import ai.newwave.agent.memory.spi.MemoryStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory memory store for development and testing.
 */
public class InMemoryMemoryStore implements MemoryStore {

    private final ConcurrentHashMap<String, Memory> memories = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> save(Memory memory) {
        return Mono.fromRunnable(() -> memories.put(memory.key(), memory));
    }

    @Override
    public Mono<Memory> findByKey(String key) {
        return Mono.justOrEmpty(memories.get(key));
    }

    @Override
    public Flux<Memory> findByTags(Set<String> tags) {
        return Flux.fromIterable(memories.values())
                .filter(m -> m.tags().stream().anyMatch(tags::contains));
    }

    @Override
    public Flux<Memory> listAll() {
        return Flux.fromIterable(memories.values());
    }

    @Override
    public Mono<Void> delete(String key) {
        return Mono.fromRunnable(() -> memories.remove(key));
    }
}
