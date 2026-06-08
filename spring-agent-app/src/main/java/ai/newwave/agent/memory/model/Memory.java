package ai.newwave.agent.memory.model;

import java.time.Instant;
import java.util.Set;

/**
 * A durable knowledge entry, scoped to a single agent. Memories are shared across all
 * of that agent's conversations but never across agents — {@code agentId} partitions the
 * store so two agents can use the same {@code key} without colliding or leaking.
 */
public record Memory(
        String agentId,
        String key,
        String content,
        Set<String> tags,
        Instant createdAt,
        Instant updatedAt
) {
    public Memory {
        if (tags == null) tags = Set.of();
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    public static Memory of(String agentId, String key, String content, Set<String> tags) {
        Instant now = Instant.now();
        return new Memory(agentId, key, content, tags, now, now);
    }
}
