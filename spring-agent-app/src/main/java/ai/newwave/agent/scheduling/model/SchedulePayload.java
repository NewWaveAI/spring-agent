package ai.newwave.agent.scheduling.model;

import java.util.Map;

/**
 * What action a scheduled event triggers.
 * Actions target a specific agent and conversation via agentId and conversationId.
 */
public sealed interface SchedulePayload permits
        SchedulePayload.PromptAction,
        SchedulePayload.SteerAction,
        SchedulePayload.FollowUpAction,
        SchedulePayload.CustomAction {

    record PromptAction(String agentId, String conversationId, String message) implements SchedulePayload {}
    record SteerAction(String agentId, String conversationId, String message) implements SchedulePayload {}
    record FollowUpAction(String agentId, String conversationId, String message) implements SchedulePayload {}
    record CustomAction(String actionType, Map<String, Object> metadata) implements SchedulePayload {}
}
