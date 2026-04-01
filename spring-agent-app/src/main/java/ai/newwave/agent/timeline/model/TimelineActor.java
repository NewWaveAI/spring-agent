package ai.newwave.agent.timeline.model;

/**
 * Represents who performed an action in the timeline.
 */
public sealed interface TimelineActor permits
        TimelineActor.User,
        TimelineActor.AgentActor,
        TimelineActor.System {

    record User(String userId, String displayName) implements TimelineActor {}
    record AgentActor(String agentId, String agentName) implements TimelineActor {}
    record System(String component) implements TimelineActor {}
}
