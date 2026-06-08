package ai.newwave.agent.memory;

import ai.newwave.agent.memory.model.Memory;
import ai.newwave.agent.memory.spi.MemoryStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Set;

/**
 * Public API for agent memory (cross-conversation knowledge store).
 *
 * <p>Every operation is scoped to an {@code agentId} so a single shared store stays
 * partitioned per agent — one agent never sees, overwrites, or deletes another's memories.
 */
public class MemoryService {

    private final MemoryStore store;

    public MemoryService(MemoryStore store) {
        this.store = store;
    }

    public Mono<Void> save(String agentId, String key, String content, Set<String> tags) {
        return store.findByKey(agentId, key)
                .map(existing -> new Memory(agentId, key, content, tags, existing.createdAt(), Instant.now()))
                .defaultIfEmpty(Memory.of(agentId, key, content, tags))
                .flatMap(store::save);
    }

    public Mono<Memory> get(String agentId, String key) {
        return store.findByKey(agentId, key);
    }

    public Flux<Memory> search(String agentId, Set<String> tags) {
        return store.findByTags(agentId, tags);
    }

    public Flux<Memory> listAll(String agentId) {
        return store.listAll(agentId);
    }

    public Mono<Void> delete(String agentId, String key) {
        return store.delete(agentId, key);
    }

    /**
     * Format this agent's memories as a text block for context injection.
     */
    public Mono<String> summarize(String agentId) {
        return store.listAll(agentId)
                .map(m -> "[%s] (tags: %s) %s".formatted(m.key(), String.join(", ", m.tags()), m.content()))
                .collectList()
                .map(lines -> String.join("\n", lines));
    }
}
