package ai.newwave.agent.event;

public enum AgentEventType {

    AGENT_START("agent_start"),
    AGENT_END("agent_end"),
    TURN_START("turn_start"),
    TURN_END("turn_end"),
    MESSAGE_START("message_start"),
    MESSAGE_UPDATE("message_update"),
    MESSAGE_END("message_end"),
    TOOL_EXECUTION_START("tool_execution_start"),
    TOOL_EXECUTION_UPDATE("tool_execution_update"),
    THINKING_UPDATE("thinking_update"),
    TOOL_EXECUTION_END("tool_execution_end"),
    SCHEDULE_FIRED("schedule_fired");

    private final String value;

    AgentEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
