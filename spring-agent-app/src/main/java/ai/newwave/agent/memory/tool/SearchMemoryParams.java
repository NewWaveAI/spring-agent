package ai.newwave.agent.memory.tool;

import java.util.Set;

public record SearchMemoryParams(
        Set<String> tags
) {
    public SearchMemoryParams {
        if (tags == null) tags = Set.of();
    }
}
