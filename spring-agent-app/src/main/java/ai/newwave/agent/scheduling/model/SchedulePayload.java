package ai.newwave.agent.scheduling.model;

import java.util.Map;

/**
 * What action a scheduled event triggers.
 * Actions target a specific channel via channelId.
 */
public sealed interface SchedulePayload permits
        SchedulePayload.PromptAction,
        SchedulePayload.SteerAction,
        SchedulePayload.FollowUpAction,
        SchedulePayload.CustomAction {

    record PromptAction(String channelId, String message) implements SchedulePayload {}
    record SteerAction(String channelId, String message) implements SchedulePayload {}
    record FollowUpAction(String channelId, String message) implements SchedulePayload {}
    record CustomAction(String actionType, Map<String, Object> metadata) implements SchedulePayload {}
}
