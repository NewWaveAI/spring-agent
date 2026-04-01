package ai.newwave.agent.scheduling;

import ai.newwave.agent.scheduling.model.ScheduledEvent;
import ai.newwave.agent.scheduling.spi.ScheduleExecutor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Public API facade for scheduling operations.
 */
public class ScheduleService {

    private final ScheduleExecutor executor;

    public ScheduleService(ScheduleExecutor executor) {
        this.executor = executor;
    }

    public Mono<String> create(ScheduledEvent event) {
        return executor.schedule(event);
    }

    public Mono<Void> cancel(String eventId) {
        return executor.cancel(eventId);
    }

    public Mono<Void> pause(String eventId) {
        return executor.pause(eventId);
    }

    public Mono<Void> resume(String eventId) {
        return executor.resume(eventId);
    }

    public Flux<ScheduledEvent> listActive() {
        return executor.listActive();
    }
}
