package ai.newwave.agent.timeline.spi;

import ai.newwave.agent.timeline.model.TimelineEvent;
import ai.newwave.agent.timeline.model.TimelineQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * SPI for persisting and querying timeline events.
 */
public interface TimelineStore {

    Mono<TimelineEvent> append(TimelineEvent event);

    Flux<TimelineEvent> query(TimelineQuery query);

    Mono<Long> count(TimelineQuery query);

    Mono<Void> deleteOlderThan(Instant cutoff);
}
