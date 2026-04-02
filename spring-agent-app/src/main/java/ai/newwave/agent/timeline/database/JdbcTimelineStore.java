package ai.newwave.agent.timeline.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.newwave.agent.timeline.model.TimelineActor;
import ai.newwave.agent.timeline.model.TimelineEvent;
import ai.newwave.agent.timeline.model.TimelineQuery;
import ai.newwave.agent.timeline.spi.TimelineStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JDBC-backed timeline store.
 *
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
 * CREATE INDEX idx_timeline_timestamp ON timeline_events (timestamp DESC);
 * CREATE INDEX idx_timeline_type ON timeline_events (event_type);
 * CREATE INDEX idx_timeline_agent ON timeline_events (agent_id);
 * </pre>
 */
public class JdbcTimelineStore implements TimelineStore {

    private static final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final JdbcTemplate jdbc;

    public JdbcTimelineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<TimelineEvent> rowMapper = (rs, rowNum) -> mapRow(rs);

    @Override
    public Mono<TimelineEvent> append(TimelineEvent event) {
        return Mono.fromCallable(() -> {
            jdbc.update("""
                INSERT INTO timeline_events (id, timestamp, actor, event_type, summary, metadata, agent_id, conversation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    event.id(),
                    Timestamp.from(event.timestamp()),
                    serialize(event.actor()),
                    event.eventType(),
                    event.summary(),
                    serialize(event.metadata()),
                    event.agentId(),
                    event.conversationId());
            return event;
        });
    }

    @Override
    public Flux<TimelineEvent> query(TimelineQuery query) {
        return Flux.defer(() -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM timeline_events WHERE 1=1");
            List<Object> params = new ArrayList<>();

            if (query.agentId() != null) {
                sql.append(" AND agent_id = ?");
                params.add(query.agentId());
            }
            if (query.conversationId() != null) {
                sql.append(" AND conversation_id = ?");
                params.add(query.conversationId());
            }
            if (query.eventTypes() != null && !query.eventTypes().isEmpty()) {
                sql.append(" AND event_type IN (");
                for (int i = 0; i < query.eventTypes().size(); i++) {
                    sql.append(i > 0 ? ", ?" : "?");
                    params.add(query.eventTypes().get(i));
                }
                sql.append(")");
            }
            if (query.since() != null) {
                sql.append(" AND timestamp >= ?");
                params.add(Timestamp.from(query.since()));
            }
            if (query.until() != null) {
                sql.append(" AND timestamp <= ?");
                params.add(Timestamp.from(query.until()));
            }

            sql.append(" ORDER BY timestamp DESC");
            sql.append(" LIMIT ? OFFSET ?");
            params.add(query.limit());
            params.add(query.offset());

            return Flux.fromIterable(jdbc.query(sql.toString(), rowMapper, params.toArray()));
        });
    }

    @Override
    public Mono<Long> count(TimelineQuery query) {
        return Mono.fromCallable(() -> {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM timeline_events WHERE 1=1");
            List<Object> params = new ArrayList<>();

            if (query.agentId() != null) {
                sql.append(" AND agent_id = ?");
                params.add(query.agentId());
            }
            if (query.eventTypes() != null && !query.eventTypes().isEmpty()) {
                sql.append(" AND event_type IN (");
                for (int i = 0; i < query.eventTypes().size(); i++) {
                    sql.append(i > 0 ? ", ?" : "?");
                    params.add(query.eventTypes().get(i));
                }
                sql.append(")");
            }

            Long result = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
            return result != null ? result : 0L;
        });
    }

    @Override
    public Mono<Void> deleteOlderThan(Instant cutoff) {
        return Mono.fromRunnable(() ->
                jdbc.update("DELETE FROM timeline_events WHERE timestamp < ?", Timestamp.from(cutoff)));
    }

    @SuppressWarnings("unchecked")
    private TimelineEvent mapRow(ResultSet rs) throws SQLException {
        try {
            return TimelineEvent.builder()
                    .id(rs.getString("id"))
                    .timestamp(rs.getTimestamp("timestamp").toInstant())
                    .actor(objectMapper.readValue(rs.getString("actor"), TimelineActor.class))
                    .eventType(rs.getString("event_type"))
                    .summary(rs.getString("summary"))
                    .metadata(rs.getString("metadata") != null
                            ? objectMapper.readValue(rs.getString("metadata"), Map.class)
                            : Map.of())
                    .agentId(rs.getString("agent_id"))
                    .conversationId(rs.getString("conversation_id"))
                    .build();
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to deserialize timeline event", e);
        }
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }
}
