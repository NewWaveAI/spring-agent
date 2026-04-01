package ai.newwave.agent.timeline;

import ai.newwave.agent.event.AgentEvent;
import ai.newwave.agent.event.AgentEventListener;
import ai.newwave.agent.timeline.model.TimelineActor;
import ai.newwave.agent.timeline.model.TimelineEvent;
import ai.newwave.agent.timeline.spi.TimelineStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Bridges the agent event system to the timeline store.
 * Automatically converts AgentEvents into TimelineEvents,
 * preserving the channelId from each event.
 */
public class TimelineRecorder implements AgentEventListener {

    private static final Logger log = LoggerFactory.getLogger(TimelineRecorder.class);

    private final TimelineStore store;

    public TimelineRecorder(TimelineStore store) {
        this.store = store;
    }

    @Override
    public void onEvent(AgentEvent event) {
        TimelineEvent timelineEvent = toTimelineEvent(event);
        if (timelineEvent != null) {
            store.append(timelineEvent)
                    .doOnError(e -> log.error("Failed to record timeline event", e))
                    .subscribe();
        }
    }

    private TimelineEvent toTimelineEvent(AgentEvent event) {
        TimelineActor agent = new TimelineActor.AgentActor(event.agentId(), "agent");
        String agentId = event.agentId();
        String channelId = event.channelId();

        return switch (event) {
            case AgentEvent.AgentStart e -> TimelineEvent.builder()
                    .actor(new TimelineActor.System("agent"))
                    .eventType("agent_started")
                    .summary("Agent started")
                    .agentId(agentId)
                    .channelId(channelId)
                    .build();

            case AgentEvent.AgentEnd e -> TimelineEvent.builder()
                    .actor(new TimelineActor.System("agent"))
                    .eventType("agent_ended")
                    .summary(e.error() != null ? "Agent ended with error: " + e.error() : "Agent completed")
                    .metadata(e.error() != null ? Map.of("error", e.error()) : Map.of())
                    .agentId(agentId)
                    .channelId(channelId)
                    .build();

            case AgentEvent.ToolExecutionStart e -> TimelineEvent.builder()
                    .actor(agent)
                    .eventType("tool_execution_started")
                    .summary("Started executing tool '%s'".formatted(e.toolUse().name()))
                    .metadata(Map.of("toolName", e.toolUse().name(), "toolUseId", e.toolUse().id()))
                    .agentId(agentId)
                    .channelId(channelId)
                    .build();

            case AgentEvent.ToolExecutionEnd e -> TimelineEvent.builder()
                    .actor(agent)
                    .eventType("tool_executed")
                    .summary("Executed tool '%s'%s".formatted(
                            e.toolUse().name(),
                            e.result().isError() ? " (error)" : ""))
                    .metadata(Map.of(
                            "toolName", e.toolUse().name(),
                            "toolUseId", e.toolUse().id(),
                            "isError", e.result().isError()))
                    .agentId(agentId)
                    .channelId(channelId)
                    .build();

            case AgentEvent.ScheduleFired e -> TimelineEvent.builder()
                    .actor(new TimelineActor.System("scheduler"))
                    .eventType("schedule_fired")
                    .summary("Schedule '%s' fired (type: %s)".formatted(e.scheduleId(), e.scheduleType()))
                    .metadata(e.metadata())
                    .agentId(agentId)
                    .channelId(channelId)
                    .build();

            case AgentEvent.TurnStart e -> TimelineEvent.builder()
                    .actor(new TimelineActor.System("agent"))
                    .eventType("turn_started")
                    .summary("Turn %d started".formatted(e.turnNumber()))
                    .agentId(agentId)
                    .channelId(channelId)
                    .build();

            // Skip high-frequency events (message updates, turn ends) to avoid noise
            case AgentEvent.MessageUpdate ignored -> null;
            case AgentEvent.MessageStart ignored -> null;
            case AgentEvent.TurnEnd ignored -> null;
            case AgentEvent.ToolExecutionUpdate ignored -> null;

            case AgentEvent.MessageEnd e -> TimelineEvent.builder()
                    .actor(agent)
                    .eventType("message_completed")
                    .summary("Assistant message completed")
                    .agentId(agentId)
                    .channelId(channelId)
                    .build();
        };
    }
}
