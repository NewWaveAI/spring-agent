package ai.newwave.agent.scheduling.spi;

import ai.newwave.agent.scheduling.model.ScheduleType;
import ai.newwave.agent.scheduling.model.ScheduledEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * SPI for persisting scheduled events with distributed locking support.
 */
public interface ScheduleStore {

    Mono<ScheduledEvent> save(ScheduledEvent event);

    Mono<ScheduledEvent> findById(String id);

    Flux<ScheduledEvent> findByType(ScheduleType type);

    Flux<ScheduledEvent> findDueEvents(Instant before);

    Mono<Void> delete(String id);

    Mono<ScheduledEvent> updateNextFireTime(String id, Instant nextFireTime);

    /**
     * Try to acquire a distributed lock for the given event.
     * Returns true if the lock was acquired (this instance should process it).
     */
    Mono<Boolean> tryAcquireLock(String eventId, String instanceId, Duration ttl);

    Mono<Void> releaseLock(String eventId, String instanceId);
}
