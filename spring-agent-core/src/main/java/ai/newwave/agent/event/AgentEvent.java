package ai.newwave.agent.event;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.tool.AgentToolResult;

import java.time.Instant;
import java.util.Map;

/**
 * Sealed interface for all agent lifecycle events.
 * Enables exhaustive pattern matching in switch expressions.
 * All events include agentId (which agent) and conversationId (which conversation).
 */
public sealed interface AgentEvent permits
        AgentEvent.AgentStart,
        AgentEvent.AgentEnd,
        AgentEvent.TurnStart,
        AgentEvent.TurnEnd,
        AgentEvent.MessageStart,
        AgentEvent.MessageUpdate,
        AgentEvent.MessageEnd,
        AgentEvent.ToolExecutionStart,
        AgentEvent.ToolExecutionUpdate,
        AgentEvent.ToolExecutionEnd,
        AgentEvent.ScheduleFired {

    Instant timestamp();

    AgentEventType type();

    /** The agent that emitted this event. */
    String agentId();

    /** The conversation this event belongs to, or null for global events. */
    String conversationId();

    record AgentStart(Instant timestamp, String agentId, String conversationId) implements AgentEvent {
        public AgentStart(String agentId, String conversationId) { this(Instant.now(), agentId, conversationId); }
        @Override public AgentEventType type() { return AgentEventType.AGENT_START; }
    }

    record AgentEnd(Instant timestamp, String agentId, String conversationId, String error) implements AgentEvent {
        public AgentEnd(String agentId, String conversationId) { this(Instant.now(), agentId, conversationId, null); }
        public AgentEnd(String agentId, String conversationId, String error) { this(Instant.now(), agentId, conversationId, error); }
        @Override public AgentEventType type() { return AgentEventType.AGENT_END; }
    }

    record TurnStart(Instant timestamp, String agentId, String conversationId, int turnNumber) implements AgentEvent {
        public TurnStart(String agentId, String conversationId, int turnNumber) { this(Instant.now(), agentId, conversationId, turnNumber); }
        @Override public AgentEventType type() { return AgentEventType.TURN_START; }
    }

    record TurnEnd(Instant timestamp, String agentId, String conversationId, int turnNumber) implements AgentEvent {
        public TurnEnd(String agentId, String conversationId, int turnNumber) { this(Instant.now(), agentId, conversationId, turnNumber); }
        @Override public AgentEventType type() { return AgentEventType.TURN_END; }
    }

    record MessageStart(Instant timestamp, String agentId, String conversationId, AgentMessage message) implements AgentEvent {
        public MessageStart(String agentId, String conversationId, AgentMessage message) { this(Instant.now(), agentId, conversationId, message); }
        @Override public AgentEventType type() { return AgentEventType.MESSAGE_START; }
    }

    record MessageUpdate(Instant timestamp, String agentId, String conversationId, String delta) implements AgentEvent {
        public MessageUpdate(String agentId, String conversationId, String delta) { this(Instant.now(), agentId, conversationId, delta); }
        @Override public AgentEventType type() { return AgentEventType.MESSAGE_UPDATE; }
    }

    record MessageEnd(Instant timestamp, String agentId, String conversationId, AgentMessage message) implements AgentEvent {
        public MessageEnd(String agentId, String conversationId, AgentMessage message) { this(Instant.now(), agentId, conversationId, message); }
        @Override public AgentEventType type() { return AgentEventType.MESSAGE_END; }
    }

    record ToolExecutionStart(Instant timestamp, String agentId, String conversationId, ContentBlock.ToolUse toolUse) implements AgentEvent {
        public ToolExecutionStart(String agentId, String conversationId, ContentBlock.ToolUse toolUse) { this(Instant.now(), agentId, conversationId, toolUse); }
        @Override public AgentEventType type() { return AgentEventType.TOOL_EXECUTION_START; }
    }

    record ToolExecutionUpdate(Instant timestamp, String agentId, String conversationId, ContentBlock.ToolUse toolUse, String update) implements AgentEvent {
        public ToolExecutionUpdate(String agentId, String conversationId, ContentBlock.ToolUse toolUse, String update) { this(Instant.now(), agentId, conversationId, toolUse, update); }
        @Override public AgentEventType type() { return AgentEventType.TOOL_EXECUTION_UPDATE; }
    }

    record ToolExecutionEnd(Instant timestamp, String agentId, String conversationId, ContentBlock.ToolUse toolUse, AgentToolResult<?> result) implements AgentEvent {
        public ToolExecutionEnd(String agentId, String conversationId, ContentBlock.ToolUse toolUse, AgentToolResult<?> result) { this(Instant.now(), agentId, conversationId, toolUse, result); }
        @Override public AgentEventType type() { return AgentEventType.TOOL_EXECUTION_END; }
    }

    record ScheduleFired(Instant timestamp, String agentId, String conversationId, String scheduleId, String scheduleType, Map<String, Object> metadata) implements AgentEvent {
        public ScheduleFired(String agentId, String conversationId, String scheduleId, String scheduleType) { this(Instant.now(), agentId, conversationId, scheduleId, scheduleType, Map.of()); }
        public ScheduleFired(String agentId, String conversationId, String scheduleId, String scheduleType, Map<String, Object> metadata) { this(Instant.now(), agentId, conversationId, scheduleId, scheduleType, metadata); }
        @Override public AgentEventType type() { return AgentEventType.SCHEDULE_FIRED; }
    }
}
