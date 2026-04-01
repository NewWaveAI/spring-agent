package ai.newwave.agent.event;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe event emitter with support for both callback-based
 * and reactive (Flux) subscription patterns.
 */
public class EventEmitter {

    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

    /**
     * Add a callback-based listener. Returns a Disposable for unsubscription.
     */
    public Disposable addListener(AgentEventListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Emit an event to all listeners and the reactive sink.
     */
    public void emit(AgentEvent event) {
        for (AgentEventListener listener : listeners) {
            listener.onEvent(event);
        }
        sink.tryEmitNext(event);
    }

    /**
     * Get a reactive stream of all events.
     * Each subscriber receives events emitted after subscription.
     */
    public Flux<AgentEvent> asFlux() {
        return sink.asFlux();
    }

    /**
     * Complete the event stream. Should be called when the agent is done.
     */
    public void complete() {
        sink.tryEmitComplete();
    }
}
