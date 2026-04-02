package ai.newwave.agent.timeline.r2dbc;

import ai.newwave.agent.timeline.model.TimelineActor;
import ai.newwave.agent.timeline.model.TimelineEvent;
import ai.newwave.agent.timeline.model.TimelineQuery;
import ai.newwave.agent.timeline.spi.TimelineStore;
import ai.newwave.agent.util.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * R2DBC-backed timeline store. Fully non-blocking.
 *
 * Required table:
 * <pre>
 * CREATE TABLE timeline_events (
 *     id VARCHAR(255) PRIMARY KEY,
 *     timestamp TIMESTAMP NOT NULL,
 *     actor TEXT NOT NULL,
 *     event_type VARCHAR(100) NOT NULL,
 *     summary TEXT NOT NULL,
 *     metadata TEXT,
 *     agent_id VARCHAR(255),
 *     conversation_id VARCHAR(255)
 * );
 * </pre>
 */
public class R2dbcTimelineStore implements TimelineStore {

    private final DatabaseClient db;

    public R2dbcTimelineStore(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<TimelineEvent> append(TimelineEvent event) {
        return db.sql("INSERT INTO timeline_events (id, timestamp, actor, event_type, summary, metadata, agent_id, conversation_id) VALUES (:id, :timestamp, :actor, :eventType, :summary, :metadata, :agentId, :conversationId)")
                .bind("id", event.id())
                .bind("timestamp", event.timestamp())
                .bind("actor", serialize(event.actor()))
                .bind("eventType", event.eventType())
                .bind("summary", event.summary())
                .bind("metadata", serialize(event.metadata()))
                .bind("agentId", event.agentId() != null ? event.agentId() : "")
                .bind("conversationId", event.conversationId() != null ? event.conversationId() : "")
                .then()
                .thenReturn(event);
    }

    @Override
    public Flux<TimelineEvent> query(TimelineQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM timeline_events WHERE 1=1");
        DatabaseClient.GenericExecuteSpec spec;

        if (query.agentId() != null) {
            sql.append(" AND agent_id = :agentId");
        }
        if (query.conversationId() != null) {
            sql.append(" AND conversation_id = :conversationId");
        }
        if (query.since() != null) {
            sql.append(" AND timestamp >= :since");
        }
        if (query.until() != null) {
            sql.append(" AND timestamp <= :until");
        }

        sql.append(" ORDER BY timestamp DESC LIMIT :limit OFFSET :offset");

        spec = db.sql(sql.toString());

        if (query.agentId() != null) spec = spec.bind("agentId", query.agentId());
        if (query.conversationId() != null) spec = spec.bind("conversationId", query.conversationId());
        if (query.since() != null) spec = spec.bind("since", query.since());
        if (query.until() != null) spec = spec.bind("until", query.until());
        spec = spec.bind("limit", query.limit());
        spec = spec.bind("offset", query.offset());

        return spec.map(row -> mapRow(row)).all();
    }

    @Override
    public Mono<Long> count(TimelineQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM timeline_events WHERE 1=1");
        DatabaseClient.GenericExecuteSpec spec;

        if (query.agentId() != null) {
            sql.append(" AND agent_id = :agentId");
        }

        spec = db.sql(sql.toString());
        if (query.agentId() != null) spec = spec.bind("agentId", query.agentId());

        return spec.map(row -> row.get(0, Long.class)).one().defaultIfEmpty(0L);
    }

    @Override
    public Mono<Void> deleteOlderThan(Instant cutoff) {
        return db.sql("DELETE FROM timeline_events WHERE timestamp < :cutoff")
                .bind("cutoff", cutoff)
                .then();
    }

    @SuppressWarnings("unchecked")
    private TimelineEvent mapRow(io.r2dbc.spi.Readable row) {
        try {
            String metadataStr = row.get("metadata", String.class);
            return TimelineEvent.builder()
                    .id(row.get("id", String.class))
                    .timestamp(row.get("timestamp", Instant.class))
                    .actor(Json.MAPPER.readValue(row.get("actor", String.class), TimelineActor.class))
                    .eventType(row.get("event_type", String.class))
                    .summary(row.get("summary", String.class))
                    .metadata(metadataStr != null ? Json.MAPPER.readValue(metadataStr, Map.class) : Map.of())
                    .agentId(row.get("agent_id", String.class))
                    .conversationId(row.get("conversation_id", String.class))
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize timeline event", e);
        }
    }

    private String serialize(Object obj) {
        try {
            return Json.MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }
}
