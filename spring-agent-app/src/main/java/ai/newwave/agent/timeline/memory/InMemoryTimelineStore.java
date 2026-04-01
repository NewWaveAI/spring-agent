package ai.newwave.agent.timeline.memory;

import ai.newwave.agent.timeline.model.TimelineEvent;
import ai.newwave.agent.timeline.model.TimelineQuery;
import ai.newwave.agent.timeline.spi.TimelineStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Stream;

/**
 * In-memory timeline store backed by a bounded deque.
 * Suitable for development and testing.
 */
public class InMemoryTimelineStore implements TimelineStore {

    private final ConcurrentLinkedDeque<TimelineEvent> events = new ConcurrentLinkedDeque<>();
    private final int maxSize;

    public InMemoryTimelineStore(int maxSize) {
        this.maxSize = maxSize;
    }

    public InMemoryTimelineStore() {
        this(10_000);
    }

    @Override
    public Mono<TimelineEvent> append(TimelineEvent event) {
        return Mono.fromCallable(() -> {
            events.addLast(event);
            while (events.size() > maxSize) {
                events.pollFirst();
            }
            return event;
        });
    }

    @Override
    public Flux<TimelineEvent> query(TimelineQuery query) {
        return Flux.defer(() -> Flux.fromStream(buildFilteredStream(query)));
    }

    private Stream<TimelineEvent> buildFilteredStream(TimelineQuery query) {
        Stream<TimelineEvent> stream = events.stream().sorted((a, b) -> b.timestamp().compareTo(a.timestamp()));

        if (query.agentId() != null) {
            stream = stream.filter(e -> query.agentId().equals(e.agentId()));
        }
        if (query.channelId() != null) {
            stream = stream.filter(e -> query.channelId().equals(e.channelId()));
        }
        if (query.eventTypes() != null && !query.eventTypes().isEmpty()) {
            stream = stream.filter(e -> query.eventTypes().contains(e.eventType()));
        }
        if (query.since() != null) {
            stream = stream.filter(e -> !e.timestamp().isBefore(query.since()));
        }
        if (query.until() != null) {
            stream = stream.filter(e -> !e.timestamp().isAfter(query.until()));
        }

        stream = stream.skip(query.offset()).limit(query.limit());
        return stream;
    }

    @Override
    public Mono<Long> count(TimelineQuery query) {
        return Mono.fromCallable(() -> buildFilteredStream(query).count());
    }

    @Override
    public Mono<Void> deleteOlderThan(Instant cutoff) {
        return Mono.fromRunnable(() -> events.removeIf(e -> e.timestamp().isBefore(cutoff)));
    }
}
