package ai.newwave.agent.timeline;

import ai.newwave.agent.event.AgentEvent;

import java.util.function.Consumer;

/**
 * Base class for recording agent events to the timeline.
 * Default implementation does nothing — extend and override {@link #accept(AgentEvent)}
 * to record events that matter to your application.
 *
 * <p>Use in your stream pipeline:
 * <pre>
 * agent.stream(request)
 *     .doOnNext(recorder::accept)
 *     .subscribe();
 * </pre>
 */
public class TimelineRecorder implements Consumer<AgentEvent> {

    @Override
    public void accept(AgentEvent event) {
        // No-op by default. Override to record specific events.
    }
}
