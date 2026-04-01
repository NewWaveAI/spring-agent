package ai.newwave.agent.scheduling.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.newwave.agent.scheduling.model.RetryConfig;
import ai.newwave.agent.scheduling.model.SchedulePayload;
import ai.newwave.agent.scheduling.model.ScheduleType;
import ai.newwave.agent.scheduling.model.ScheduledEvent;
import ai.newwave.agent.scheduling.spi.ScheduleStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * JDBC-backed schedule store with row-level locking for distributed safety.
 *
 * <pre>
 * CREATE TABLE scheduled_events (
 *     id VARCHAR(255) PRIMARY KEY,
 *     type VARCHAR(50) NOT NULL,
 *     schedule_expression VARCHAR(255),
 *     timezone VARCHAR(100) DEFAULT 'UTC',
 *     payload TEXT NOT NULL,
 *     retry_config TEXT NOT NULL,
 *     created_at TIMESTAMP NOT NULL,
 *     next_fire_time TIMESTAMP,
 *     enabled BOOLEAN DEFAULT TRUE,
 *     lock_owner VARCHAR(255),
 *     lock_expiry TIMESTAMP
 * );
 * CREATE INDEX idx_due_events ON scheduled_events (enabled, next_fire_time);
 * </pre>
 */
public class JdbcScheduleStore implements ScheduleStore {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public JdbcScheduleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<ScheduledEvent> rowMapper = (rs, rowNum) -> mapRow(rs);

    @Override
    public Mono<ScheduledEvent> save(ScheduledEvent event) {
        return Mono.fromCallable(() -> {
            jdbc.update("""
                INSERT INTO scheduled_events (id, type, schedule_expression, timezone, payload, retry_config, created_at, next_fire_time, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    type = EXCLUDED.type, schedule_expression = EXCLUDED.schedule_expression,
                    timezone = EXCLUDED.timezone, payload = EXCLUDED.payload, retry_config = EXCLUDED.retry_config,
                    next_fire_time = EXCLUDED.next_fire_time, enabled = EXCLUDED.enabled
                """,
                    event.id(), event.type().name(), event.scheduleExpression(), event.timezone(),
                    serialize(event.payload()), serialize(event.retryConfig()),
                    Timestamp.from(event.createdAt()),
                    event.nextFireTime() != null ? Timestamp.from(event.nextFireTime()) : null,
                    event.enabled());
            return event;
        });
    }

    @Override
    public Mono<ScheduledEvent> findById(String id) {
        return Mono.fromCallable(() -> {
            var results = jdbc.query("SELECT * FROM scheduled_events WHERE id = ?", rowMapper, id);
            return results.isEmpty() ? null : results.getFirst();
        });
    }

    @Override
    public Flux<ScheduledEvent> findByType(ScheduleType type) {
        return Flux.defer(() -> Flux.fromIterable(
                jdbc.query("SELECT * FROM scheduled_events WHERE type = ?", rowMapper, type.name())));
    }

    @Override
    public Flux<ScheduledEvent> findDueEvents(Instant before) {
        return Flux.defer(() -> Flux.fromIterable(
                jdbc.query("SELECT * FROM scheduled_events WHERE enabled = TRUE AND next_fire_time <= ?",
                        rowMapper, Timestamp.from(before))));
    }

    @Override
    public Mono<Void> delete(String id) {
        return Mono.fromRunnable(() -> jdbc.update("DELETE FROM scheduled_events WHERE id = ?", id));
    }

    @Override
    public Mono<ScheduledEvent> updateNextFireTime(String id, Instant nextFireTime) {
        return Mono.fromCallable(() -> {
            jdbc.update("UPDATE scheduled_events SET next_fire_time = ? WHERE id = ?",
                    Timestamp.from(nextFireTime), id);
            return findById(id).block();
        });
    }

    @Override
    public Mono<Boolean> tryAcquireLock(String eventId, String instanceId, Duration ttl) {
        return Mono.fromCallable(() -> {
            Timestamp expiry = Timestamp.from(Instant.now().plus(ttl));
            Timestamp now = Timestamp.from(Instant.now());
            int updated = jdbc.update("""
                UPDATE scheduled_events SET lock_owner = ?, lock_expiry = ?
                WHERE id = ? AND (lock_owner IS NULL OR lock_expiry < ?)
                """, instanceId, expiry, eventId, now);
            return updated > 0;
        });
    }

    @Override
    public Mono<Void> releaseLock(String eventId, String instanceId) {
        return Mono.fromRunnable(() ->
                jdbc.update("UPDATE scheduled_events SET lock_owner = NULL, lock_expiry = NULL WHERE id = ? AND lock_owner = ?",
                        eventId, instanceId));
    }

    private ScheduledEvent mapRow(ResultSet rs) throws SQLException {
        try {
            return ScheduledEvent.builder()
                    .id(rs.getString("id"))
                    .type(ScheduleType.valueOf(rs.getString("type")))
                    .scheduleExpression(rs.getString("schedule_expression"))
                    .timezone(rs.getString("timezone"))
                    .payload(objectMapper.readValue(rs.getString("payload"), SchedulePayload.class))
                    .retryConfig(objectMapper.readValue(rs.getString("retry_config"), RetryConfig.class))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .nextFireTime(rs.getTimestamp("next_fire_time") != null ? rs.getTimestamp("next_fire_time").toInstant() : null)
                    .enabled(rs.getBoolean("enabled"))
                    .build();
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to deserialize schedule event", e);
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
