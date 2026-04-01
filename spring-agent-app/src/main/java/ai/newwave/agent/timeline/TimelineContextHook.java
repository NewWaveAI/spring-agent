package ai.newwave.agent.timeline;

import ai.newwave.agent.config.AgentHooks;
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.timeline.model.TimelineQuery;

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
    public List<AgentMessage> transformContext(List<AgentMessage> messages) {
        String summary = timelineService.summarize(
                        TimelineQuery.builder().limit(maxRecentEvents).build())
                .block();

        if (summary == null || summary.isBlank()) {
            return messages;
        }

        AgentMessage contextMsg = AgentMessage.user("[Activity Timeline]\n" + summary);

        List<AgentMessage> result = new ArrayList<>();
        result.add(contextMsg);
        result.addAll(messages);
        return result;
    }
}
