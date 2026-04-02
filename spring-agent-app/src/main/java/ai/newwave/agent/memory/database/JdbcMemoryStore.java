package ai.newwave.agent.memory.database;

import ai.newwave.agent.util.Json;

import ai.newwave.agent.memory.model.Memory;
import ai.newwave.agent.memory.spi.MemoryStore;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;

/**
 * JDBC-backed memory store.
 *
 * Required table:
 * <pre>
 * CREATE TABLE agent_memories (
 *     key VARCHAR(255) PRIMARY KEY,
 *     content TEXT NOT NULL,
 *     tags TEXT,
 *     created_at TIMESTAMP NOT NULL,
 *     updated_at TIMESTAMP NOT NULL
 * );
 * </pre>
 */
public class JdbcMemoryStore implements MemoryStore {

    private final JdbcTemplate jdbc;
    

    public JdbcMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Mono<Void> save(Memory memory) {
        return Mono.fromRunnable(() ->
                jdbc.update("""
                        INSERT INTO agent_memories (key, content, tags, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (key) DO UPDATE SET
                            content = EXCLUDED.content, tags = EXCLUDED.tags, updated_at = EXCLUDED.updated_at
                        """,
                        memory.key(), memory.content(), serializeTags(memory.tags()),
                        Timestamp.from(memory.createdAt()), Timestamp.from(memory.updatedAt())));
    }

    @Override
    public Mono<Memory> findByKey(String key) {
        return Mono.defer(() -> {
            List<Memory> results = jdbc.query(
                    "SELECT key, content, tags, created_at, updated_at FROM agent_memories WHERE key = ?",
                    (rs, rowNum) -> mapRow(rs),
                    key);
            return results.isEmpty() ? Mono.empty() : Mono.just(results.getFirst());
        });
    }

    @Override
    public Flux<Memory> findByTags(Set<String> tags) {
        return Flux.defer(() -> {
            // Load all and filter in-memory (simple approach, works for reasonable memory counts)
            List<Memory> all = jdbc.query(
                    "SELECT key, content, tags, created_at, updated_at FROM agent_memories",
                    (rs, rowNum) -> mapRow(rs));
            return Flux.fromIterable(all)
                    .filter(m -> m.tags().stream().anyMatch(tags::contains));
        });
    }

    @Override
    public Flux<Memory> listAll() {
        return Flux.defer(() -> {
            List<Memory> all = jdbc.query(
                    "SELECT key, content, tags, created_at, updated_at FROM agent_memories ORDER BY updated_at DESC",
                    (rs, rowNum) -> mapRow(rs));
            return Flux.fromIterable(all);
        });
    }

    @Override
    public Mono<Void> delete(String key) {
        return Mono.fromRunnable(() ->
                jdbc.update("DELETE FROM agent_memories WHERE key = ?", key));
    }

    private Memory mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Memory(
                rs.getString("key"),
                rs.getString("content"),
                deserializeTags(rs.getString("tags")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String serializeTags(Set<String> tags) {
        try {
            return Json.MAPPER.writeValueAsString(tags);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize tags", e);
        }
    }

    private Set<String> deserializeTags(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try {
            return Json.MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Set.of();
        }
    }
}
