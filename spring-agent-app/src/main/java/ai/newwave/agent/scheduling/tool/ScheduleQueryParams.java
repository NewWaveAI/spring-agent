package ai.newwave.agent.scheduling.tool;

/**
 * Parameters for the schedule query tool.
 */
public record ScheduleQueryParams(
        String action,
        String scheduleId,
        String type
) {
    public ScheduleQueryParams {
        if (action == null) action = "list";
    }
}
