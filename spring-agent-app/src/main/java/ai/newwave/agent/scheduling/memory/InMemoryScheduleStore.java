package ai.newwave.agent.scheduling.memory;

import ai.newwave.agent.scheduling.model.ScheduleType;
import ai.newwave.agent.scheduling.model.ScheduledEvent;
import ai.newwave.agent.scheduling.spi.ScheduleStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory schedule store for development and testing.
 * Locking is trivial (single instance) but implements the interface.
 */
public class InMemoryScheduleStore implements ScheduleStore {

    private final Map<String, ScheduledEvent> events = new ConcurrentHashMap<>();
    private final Map<String, String> locks = new ConcurrentHashMap<>();

    @Override
    public Mono<ScheduledEvent> save(ScheduledEvent event) {
        return Mono.fromCallable(() -> {
            events.put(event.id(), event);
            return event;
        });
    }

    @Override
    public Mono<ScheduledEvent> findById(String id) {
        return Mono.justOrEmpty(events.get(id));
    }

    @Override
    public Flux<ScheduledEvent> findByType(ScheduleType type) {
        return Flux.fromIterable(events.values())
                .filter(e -> e.type() == type);
    }

    @Override
    public Flux<ScheduledEvent> findDueEvents(Instant before) {
        return Flux.fromIterable(events.values())
                .filter(e -> e.enabled() && e.nextFireTime() != null && !e.nextFireTime().isAfter(before));
    }

    @Override
    public Mono<Void> delete(String id) {
        return Mono.fromRunnable(() -> {
            events.remove(id);
            locks.remove(id);
        });
    }

    @Override
    public Mono<ScheduledEvent> updateNextFireTime(String id, Instant nextFireTime) {
        return Mono.fromCallable(() -> {
            ScheduledEvent existing = events.get(id);
            if (existing == null) return null;
            ScheduledEvent updated = ScheduledEvent.builder()
                    .id(existing.id())
                    .type(existing.type())
                    .scheduleExpression(existing.scheduleExpression())
                    .timezone(existing.timezone())
                    .payload(existing.payload())
                    .retryConfig(existing.retryConfig())
                    .createdAt(existing.createdAt())
                    .nextFireTime(nextFireTime)
                    .enabled(existing.enabled())
                    .build();
            events.put(id, updated);
            return updated;
        });
    }

    @Override
    public Mono<Boolean> tryAcquireLock(String eventId, String instanceId, Duration ttl) {
        return Mono.fromCallable(() -> locks.putIfAbsent(eventId, instanceId) == null);
    }

    @Override
    public Mono<Void> releaseLock(String eventId, String instanceId) {
        return Mono.fromRunnable(() -> locks.remove(eventId, instanceId));
    }
}
