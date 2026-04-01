package ai.newwave.agent.timeline.tool;

import java.util.List;

/**
 * Parameters for the timeline query tool.
 */
public record TimelineQueryParams(
        List<String> eventTypes,
        String since,
        String until,
        int limit
) {
}
