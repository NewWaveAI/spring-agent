package ai.newwave.agent.memory.spi;

import ai.newwave.agent.memory.model.Memory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * SPI for persisting agent memories (cross-channel knowledge).
 */
public interface MemoryStore {

    Mono<Void> save(Memory memory);

    Mono<Memory> findByKey(String key);

    Flux<Memory> findByTags(Set<String> tags);

    Flux<Memory> listAll();

    Mono<Void> delete(String key);
}
