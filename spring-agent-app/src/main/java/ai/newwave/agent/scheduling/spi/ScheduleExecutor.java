package ai.newwave.agent.scheduling.spi;

import ai.newwave.agent.scheduling.model.ScheduledEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * SPI for the execution engine that manages scheduled event lifecycles.
 */
public interface ScheduleExecutor {

    Mono<String> schedule(ScheduledEvent event);

    Mono<Void> cancel(String eventId);

    Mono<Void> pause(String eventId);

    Mono<Void> resume(String eventId);

    Flux<ScheduledEvent> listActive();
}
