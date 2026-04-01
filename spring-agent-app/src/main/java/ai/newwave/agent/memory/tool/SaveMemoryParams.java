package ai.newwave.agent.memory.tool;

import java.util.Set;

public record SaveMemoryParams(
        String key,
        String content,
        Set<String> tags
) {
    public SaveMemoryParams {
        if (tags == null) tags = Set.of();
    }
}
