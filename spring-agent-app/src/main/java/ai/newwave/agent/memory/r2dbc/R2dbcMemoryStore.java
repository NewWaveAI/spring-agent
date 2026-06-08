package ai.newwave.agent.memory.r2dbc;

import ai.newwave.agent.memory.model.Memory;
import ai.newwave.agent.memory.spi.MemoryStore;
import ai.newwave.agent.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * R2DBC-backed memory store. Fully non-blocking. Memories are partitioned by {@code agent_id}.
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
public class R2dbcMemoryStore implements MemoryStore {

    private final DatabaseClient db;

    public R2dbcMemoryStore(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Void> save(Memory memory) {
        return db.sql("""
                INSERT INTO agent_memories (id, agent_id, key, content, tags, created_at, updated_at)
                VALUES (:id, :agentId, :key, :content, :tags, :createdAt, :updatedAt)
                ON CONFLICT (agent_id, key) DO UPDATE SET
                    content = EXCLUDED.content, tags = EXCLUDED.tags, updated_at = EXCLUDED.updated_at
                """)
                .bind("id", UUID.randomUUID().toString())
                .bind("agentId", memory.agentId())
                .bind("key", memory.key())
                .bind("content", memory.content())
                .bind("tags", serializeTags(memory.tags()))
                .bind("createdAt", memory.createdAt())
                .bind("updatedAt", memory.updatedAt())
                .then();
    }

    @Override
    public Mono<Memory> findByKey(String agentId, String key) {
        return db.sql("""
                SELECT agent_id, key, content, tags, created_at, updated_at
                FROM agent_memories WHERE agent_id = :agentId AND key = :key
                """)
                .bind("agentId", agentId)
                .bind("key", key)
                .map(row -> mapRow(row))
                .one();
    }

    @Override
    public Flux<Memory> findByTags(String agentId, Set<String> tags) {
        return db.sql("""
                SELECT agent_id, key, content, tags, created_at, updated_at
                FROM agent_memories WHERE agent_id = :agentId
                """)
                .bind("agentId", agentId)
                .map(row -> mapRow(row))
                .all()
                .filter(m -> m.tags().stream().anyMatch(tags::contains));
    }

    @Override
    public Flux<Memory> listAll(String agentId) {
        return db.sql("""
                SELECT agent_id, key, content, tags, created_at, updated_at
                FROM agent_memories WHERE agent_id = :agentId ORDER BY updated_at DESC
                """)
                .bind("agentId", agentId)
                .map(row -> mapRow(row))
                .all();
    }

    @Override
    public Mono<Void> delete(String agentId, String key) {
        return db.sql("DELETE FROM agent_memories WHERE agent_id = :agentId AND key = :key")
                .bind("agentId", agentId)
                .bind("key", key)
                .then();
    }

    private Memory mapRow(io.r2dbc.spi.Readable row) {
        return new Memory(
                row.get("agent_id", String.class),
                row.get("key", String.class),
                row.get("content", String.class),
                deserializeTags(row.get("tags", String.class)),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class));
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
