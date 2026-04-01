package ai.newwave.agent.scheduling;

import ai.newwave.agent.core.Agent;
import ai.newwave.agent.core.AgentRequest;
import ai.newwave.agent.scheduling.model.SchedulePayload;
import ai.newwave.agent.scheduling.model.ScheduledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Routes fired scheduled events to the appropriate agent action on the target conversation.
 */
public class ScheduleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScheduleDispatcher.class);

    private final Agent agent;

    public ScheduleDispatcher(Agent agent) {
        this.agent = agent;
    }

    /**
     * Dispatch a fired scheduled event to the agent.
     */
    public Mono<Void> dispatch(ScheduledEvent event) {
        return switch (event.payload()) {
            case SchedulePayload.PromptAction a -> {
                log.info("Dispatching prompt action for schedule {} to {}:{}", event.id(), a.agentId(), a.conversationId());
                yield agent.stream(AgentRequest.builder()
                        .agentId(a.agentId()).conversationId(a.conversationId()).message(a.message()).build()).then();
            }
            case SchedulePayload.SteerAction a -> {
                log.info("Dispatching steer action for schedule {} to {}:{}", event.id(), a.agentId(), a.conversationId());
                yield agent.stream(AgentRequest.builder()
                        .agentId(a.agentId()).conversationId(a.conversationId()).message(a.message()).build()).then();
            }
            case SchedulePayload.FollowUpAction a -> {
                log.info("Dispatching follow-up action for schedule {} to {}:{}", event.id(), a.agentId(), a.conversationId());
                yield agent.stream(AgentRequest.builder()
                        .agentId(a.agentId()).conversationId(a.conversationId()).message(a.message()).build()).then();
            }
            case SchedulePayload.CustomAction a -> {
                log.info("Custom action '{}' for schedule {} — no default handler", a.actionType(), event.id());
                yield Mono.empty();
            }
        };
    }
}
