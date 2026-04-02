package ai.newwave.agent.timeline.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Represents who performed an action in the timeline.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TimelineActor.User.class, name = "user"),
        @JsonSubTypes.Type(value = TimelineActor.AgentActor.class, name = "agent"),
        @JsonSubTypes.Type(value = TimelineActor.System.class, name = "system")
})
public sealed interface TimelineActor permits
        TimelineActor.User,
        TimelineActor.AgentActor,
        TimelineActor.System {

    record User(String userId, String displayName) implements TimelineActor {}
    record AgentActor(String agentId, String agentName) implements TimelineActor {}
    record System(String component) implements TimelineActor {}
}
