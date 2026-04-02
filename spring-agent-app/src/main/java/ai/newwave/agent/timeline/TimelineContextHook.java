package ai.newwave.agent.timeline;

import ai.newwave.agent.config.AgentHooks;
import ai.newwave.agent.config.HookContext;
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.timeline.model.TimelineQuery;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentHooks implementation that prepends recent timeline events
 * to the context, giving the agent passive situational awareness.
 */
public class TimelineContextHook implements AgentHooks {

    private final TimelineService timelineService;
    private final int maxRecentEvents;

    public TimelineContextHook(TimelineService timelineService, int maxRecentEvents) {
        this.timelineService = timelineService;
        this.maxRecentEvents = maxRecentEvents;
    }

    @Override
    public Mono<List<AgentMessage>> transformContext(HookContext ctx, List<AgentMessage> messages) {
        return timelineService.summarize(
                        TimelineQuery.builder().limit(maxRecentEvents).build())
                .flatMap(summary -> {
                    if (summary == null || summary.isBlank()) {
                        return Mono.just(messages);
                    }
                    AgentMessage contextMsg = AgentMessage.user("[Activity Timeline]\n" + summary);
                    List<AgentMessage> result = new ArrayList<>();
                    result.add(contextMsg);
                    result.addAll(messages);
                    return Mono.just(result);
                })
                .defaultIfEmpty(messages);
    }
}
