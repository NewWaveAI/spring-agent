package ai.newwave.agent.memory.model;

import java.time.Instant;
import java.util.Set;

/**
 * A durable knowledge entry shared across all conversations.
 */
public record Memory(
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

    public static Memory of(String key, String content, Set<String> tags) {
        Instant now = Instant.now();
        return new Memory(key, content, tags, now, now);
    }
}
