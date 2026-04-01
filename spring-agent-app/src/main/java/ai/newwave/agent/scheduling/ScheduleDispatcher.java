package ai.newwave.agent.scheduling;

import ai.newwave.agent.core.Agent;
import ai.newwave.agent.event.AgentEvent;
import ai.newwave.agent.event.EventEmitter;
import ai.newwave.agent.scheduling.model.SchedulePayload;
import ai.newwave.agent.scheduling.model.ScheduledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Routes fired scheduled events to the appropriate agent action on the target channel.
 */
public class ScheduleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScheduleDispatcher.class);

    private final Agent agent;
    private final EventEmitter eventEmitter;

    public ScheduleDispatcher(Agent agent, EventEmitter eventEmitter) {
        this.agent = agent;
        this.eventEmitter = eventEmitter;
    }

    /**
     * Dispatch a fired scheduled event to the agent on the appropriate channel.
     */
    public Mono<Void> dispatch(ScheduledEvent event) {
        String channelId = resolveChannelId(event.payload());

        // Emit schedule_fired event for timeline tracking
        String agentId = agent.getState().getConfig().agentId();
        eventEmitter.emit(new AgentEvent.ScheduleFired(
                agentId,
                channelId,
                event.id(),
                event.type().name(),
                Map.of("payload", event.payload().toString())
        ));

        return switch (event.payload()) {
            case SchedulePayload.PromptAction a -> {
                log.info("Dispatching prompt action for schedule {} to channel {}", event.id(), a.channelId());
                yield agent.prompt(a.message(), a.channelId());
            }
            case SchedulePayload.SteerAction a -> {
                log.info("Dispatching steer action for schedule {} to channel {}", event.id(), a.channelId());
                agent.steer(a.message(), a.channelId());
                yield Mono.empty();
            }
            case SchedulePayload.FollowUpAction a -> {
                log.info("Dispatching follow-up action for schedule {} to channel {}", event.id(), a.channelId());
                agent.followUp(a.message(), a.channelId());
                yield Mono.empty();
            }
            case SchedulePayload.CustomAction a -> {
                log.info("Custom action '{}' for schedule {} — no default handler", a.actionType(), event.id());
                yield Mono.empty();
            }
        };
    }

    private String resolveChannelId(SchedulePayload payload) {
        return switch (payload) {
            case SchedulePayload.PromptAction a -> a.channelId();
            case SchedulePayload.SteerAction a -> a.channelId();
            case SchedulePayload.FollowUpAction a -> a.channelId();
            case SchedulePayload.CustomAction a -> Agent.DEFAULT_CHANNEL;
        };
    }
}
