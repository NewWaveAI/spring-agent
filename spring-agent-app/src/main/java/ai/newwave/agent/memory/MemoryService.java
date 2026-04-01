package ai.newwave.agent.memory;

import ai.newwave.agent.memory.model.Memory;
import ai.newwave.agent.memory.spi.MemoryStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Set;

/**
 * Public API for agent memory (cross-channel knowledge store).
 */
public class MemoryService {

    private final MemoryStore store;

    public MemoryService(MemoryStore store) {
        this.store = store;
    }

    public Mono<Void> save(String key, String content, Set<String> tags) {
        return store.findByKey(key)
                .map(existing -> new Memory(key, content, tags, existing.createdAt(), Instant.now()))
                .defaultIfEmpty(Memory.of(key, content, tags))
                .flatMap(store::save);
    }

    public Mono<Memory> get(String key) {
        return store.findByKey(key);
    }

    public Flux<Memory> search(Set<String> tags) {
        return store.findByTags(tags);
    }

    public Flux<Memory> listAll() {
        return store.listAll();
    }

    public Mono<Void> delete(String key) {
        return store.delete(key);
    }

    /**
     * Format all memories as a text block for context injection.
     */
    public Mono<String> summarize() {
        return store.listAll()
                .map(m -> "[%s] (tags: %s) %s".formatted(m.key(), String.join(", ", m.tags()), m.content()))
                .collectList()
                .map(lines -> String.join("\n", lines));
    }
}
