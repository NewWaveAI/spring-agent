package ai.newwave.agent.event;

/**
 * Functional interface for listening to agent lifecycle events.
 */
@FunctionalInterface
public interface AgentEventListener {

    void onEvent(AgentEvent event);
}
