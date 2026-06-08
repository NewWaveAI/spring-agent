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
import java.util.UUID;

/**
 * JDBC-backed memory store. Memories are partitioned by {@code agent_id}.
 *
 * Required table:
 * <pre>
 * CREATE TABLE agent_memories (
 *     id VARCHAR(255) PRIMARY KEY,
 *     agent_id VARCHAR(255) NOT NULL,
 *     key VARCHAR(255) NOT NULL,
 *     content TEXT NOT NULL,
 *     tags TEXT,
 *     created_at TIMESTAMP NOT NULL,
 *     updated_at TIMESTAMP NOT NULL
 * );
 * CREATE UNIQUE INDEX idx_memories_key ON agent_memories (agent_id, key);
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
                        INSERT INTO agent_memories (id, agent_id, key, content, tags, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (agent_id, key) DO UPDATE SET
                            content = EXCLUDED.content, tags = EXCLUDED.tags, updated_at = EXCLUDED.updated_at
                        """,
                        UUID.randomUUID().toString(), memory.agentId(), memory.key(), memory.content(),
                        serializeTags(memory.tags()),
                        Timestamp.from(memory.createdAt()), Timestamp.from(memory.updatedAt())));
    }

    @Override
    public Mono<Memory> findByKey(String agentId, String key) {
        return Mono.defer(() -> {
            List<Memory> results = jdbc.query(
                    "SELECT agent_id, key, content, tags, created_at, updated_at FROM agent_memories WHERE agent_id = ? AND key = ?",
                    (rs, rowNum) -> mapRow(rs),
                    agentId, key);
            return results.isEmpty() ? Mono.empty() : Mono.just(results.getFirst());
        });
    }

    @Override
    public Flux<Memory> findByTags(String agentId, Set<String> tags) {
        return Flux.defer(() -> {
            // Load this agent's memories and filter in-memory (simple approach, works for reasonable counts)
            List<Memory> all = jdbc.query(
                    "SELECT agent_id, key, content, tags, created_at, updated_at FROM agent_memories WHERE agent_id = ?",
                    (rs, rowNum) -> mapRow(rs),
                    agentId);
            return Flux.fromIterable(all)
                    .filter(m -> m.tags().stream().anyMatch(tags::contains));
        });
    }

    @Override
    public Flux<Memory> listAll(String agentId) {
        return Flux.defer(() -> {
            List<Memory> all = jdbc.query(
                    "SELECT agent_id, key, content, tags, created_at, updated_at FROM agent_memories WHERE agent_id = ? ORDER BY updated_at DESC",
                    (rs, rowNum) -> mapRow(rs),
                    agentId);
            return Flux.fromIterable(all);
        });
    }

    @Override
    public Mono<Void> delete(String agentId, String key) {
        return Mono.fromRunnable(() ->
                jdbc.update("DELETE FROM agent_memories WHERE agent_id = ? AND key = ?", agentId, key));
    }

    private Memory mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Memory(
                rs.getString("agent_id"),
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
