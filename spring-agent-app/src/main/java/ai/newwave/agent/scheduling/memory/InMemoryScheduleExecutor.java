package ai.newwave.agent.scheduling.memory;

import ai.newwave.agent.scheduling.ScheduleDispatcher;
import ai.newwave.agent.scheduling.model.ScheduleType;
import ai.newwave.agent.scheduling.model.ScheduledEvent;
import ai.newwave.agent.scheduling.spi.ScheduleExecutor;
import ai.newwave.agent.scheduling.spi.ScheduleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * In-memory schedule executor using ScheduledExecutorService.
 * For development and testing only — not distributed-safe.
 */
public class InMemoryScheduleExecutor implements ScheduleExecutor {

    private static final Logger log = LoggerFactory.getLogger(InMemoryScheduleExecutor.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final ScheduleStore store;
    private final ScheduleDispatcher dispatcher;

    public InMemoryScheduleExecutor(ScheduleStore store, ScheduleDispatcher dispatcher) {
        this.store = store;
        this.dispatcher = dispatcher;
    }

    @Override
    public Mono<String> schedule(ScheduledEvent event) {
        return store.save(event).map(saved -> {
            switch (saved.type()) {
                case IMMEDIATE -> scheduleImmediate(saved);
                case ONE_SHOT -> scheduleOneShot(saved);
                case PERIODIC -> schedulePeriodic(saved);
            }
            return saved.id();
        });
    }

    @Override
    public Mono<Void> cancel(String eventId) {
        return Mono.fromRunnable(() -> {
            ScheduledFuture<?> future = futures.remove(eventId);
            if (future != null) {
                future.cancel(false);
            }
        }).then(store.delete(eventId));
    }

    @Override
    public Mono<Void> pause(String eventId) {
        return Mono.fromRunnable(() -> {
            ScheduledFuture<?> future = futures.remove(eventId);
            if (future != null) {
                future.cancel(false);
            }
        });
    }

    @Override
    public Mono<Void> resume(String eventId) {
        return store.findById(eventId)
                .flatMap(event -> schedule(event).then());
    }

    @Override
    public Flux<ScheduledEvent> listActive() {
        return Flux.fromIterable(futures.keySet())
                .flatMap(store::findById);
    }

    private void scheduleImmediate(ScheduledEvent event) {
        ScheduledFuture<?> future = scheduler.schedule(
                () -> fire(event), 0, TimeUnit.MILLISECONDS);
        futures.put(event.id(), future);
    }

    private void scheduleOneShot(ScheduledEvent event) {
        Instant fireTime = event.nextFireTime() != null
                ? event.nextFireTime()
                : Instant.parse(event.scheduleExpression());

        long delay = Math.max(0, Duration.between(Instant.now(), fireTime).toMillis());
        ScheduledFuture<?> future = scheduler.schedule(
                () -> fire(event), delay, TimeUnit.MILLISECONDS);
        futures.put(event.id(), future);
    }

    private void schedulePeriodic(ScheduledEvent event) {
        // Simple cron-like: parse as fixed interval for in-memory impl
        // Real cron parsing would use a library; for dev/test we use a fixed 60s interval
        // if expression looks like a duration, parse it; otherwise default
        long intervalMs = parsePeriod(event.scheduleExpression());

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> fire(event), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        futures.put(event.id(), future);
    }

    private long parsePeriod(String expression) {
        try {
            return Duration.parse(expression).toMillis();
        } catch (Exception e) {
            // Default to 60 seconds for unparseable expressions
            return 60_000;
        }
    }

    private void fire(ScheduledEvent event) {
        log.info("Firing scheduled event: {} (type: {})", event.id(), event.type());
        try {
            dispatcher.dispatch(event).block();
        } catch (Exception e) {
            log.error("Failed to dispatch scheduled event: {}", event.id(), e);
        }

        // Clean up one-shot and immediate events
        if (event.type() == ScheduleType.IMMEDIATE || event.type() == ScheduleType.ONE_SHOT) {
            futures.remove(event.id());
            store.delete(event.id()).subscribe();
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
