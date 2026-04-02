package ai.newwave.agent.scheduling.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * What action a scheduled event triggers.
 * Actions target a specific agent and conversation via agentId and conversationId.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SchedulePayload.PromptAction.class, name = "prompt"),
        @JsonSubTypes.Type(value = SchedulePayload.SteerAction.class, name = "steer"),
        @JsonSubTypes.Type(value = SchedulePayload.FollowUpAction.class, name = "follow_up"),
        @JsonSubTypes.Type(value = SchedulePayload.CustomAction.class, name = "custom")
})
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
