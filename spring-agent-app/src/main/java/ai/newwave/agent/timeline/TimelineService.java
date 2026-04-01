package ai.newwave.agent.timeline;

import ai.newwave.agent.timeline.model.TimelineActor;
import ai.newwave.agent.timeline.model.TimelineEvent;
import ai.newwave.agent.timeline.model.TimelineQuery;
import ai.newwave.agent.timeline.spi.TimelineStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Public API for recording and querying timeline events.
 */
public class TimelineService {

    private final TimelineStore store;

    public TimelineService(TimelineStore store) {
        this.store = store;
    }

    public Mono<TimelineEvent> record(TimelineEvent event) {
        return store.append(event);
    }

    public Mono<TimelineEvent> record(TimelineActor actor, String eventType, String summary) {
        return store.append(TimelineEvent.builder()
                .actor(actor)
                .eventType(eventType)
                .summary(summary)
                .build());
    }

    public Mono<TimelineEvent> record(TimelineActor actor, String eventType, String summary, Map<String, Object> metadata) {
        return store.append(TimelineEvent.builder()
                .actor(actor)
                .eventType(eventType)
                .summary(summary)
                .metadata(metadata)
                .build());
    }

    public Flux<TimelineEvent> query(TimelineQuery query) {
        return store.query(query);
    }

    public Mono<Long> count(TimelineQuery query) {
        return store.count(query);
    }

    /**
     * Generate a text summary of recent timeline events for agent context injection.
     */
    public Mono<String> summarize(TimelineQuery query) {
        return store.query(query)
                .collectList()
                .map(events -> events.stream()
                        .map(e -> "[%s] %s: %s".formatted(e.timestamp(), e.eventType(), e.summary()))
                        .collect(Collectors.joining("\n")));
    }

    public Mono<Void> cleanup(Instant olderThan) {
        return store.deleteOlderThan(olderThan);
    }
}
