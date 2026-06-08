package ai.newwave.agent.memory.spi;

import ai.newwave.agent.memory.model.Memory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * SPI for persisting agent memories (cross-conversation knowledge).
 *
 * <p>Every operation is scoped to an {@code agentId}: a store instance may be shared by
 * many agents (e.g. one bean per process serving every tenant), so reads, upserts, and
 * deletes must be partitioned by agent. The {@code save} target carries its own
 * {@link Memory#agentId()}.
 */
public interface MemoryStore {

    Mono<Void> save(Memory memory);

    Mono<Memory> findByKey(String agentId, String key);

    Flux<Memory> findByTags(String agentId, Set<String> tags);

    Flux<Memory> listAll(String agentId);

    Mono<Void> delete(String agentId, String key);
}
