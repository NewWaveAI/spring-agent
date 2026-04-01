package ai.newwave.agent.event;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.tool.AgentToolResult;

import java.time.Instant;
import java.util.Map;

/**
 * Sealed interface for all agent lifecycle events.
 * Enables exhaustive pattern matching in switch expressions.
 * All events include agentId (which agent) and channelId (which channel).
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

    String type();

    /** The agent that emitted this event. */
    String agentId();

    /** The channel this event belongs to, or null for global events. */
    String channelId();

    record AgentStart(Instant timestamp, String agentId, String channelId) implements AgentEvent {
        public AgentStart(String agentId, String channelId) { this(Instant.now(), agentId, channelId); }
        @Override public String type() { return "agent_start"; }
    }

    record AgentEnd(Instant timestamp, String agentId, String channelId, String error) implements AgentEvent {
        public AgentEnd(String agentId, String channelId) { this(Instant.now(), agentId, channelId, null); }
        public AgentEnd(String agentId, String channelId, String error) { this(Instant.now(), agentId, channelId, error); }
        @Override public String type() { return "agent_end"; }
    }

    record TurnStart(Instant timestamp, String agentId, String channelId, int turnNumber) implements AgentEvent {
        public TurnStart(String agentId, String channelId, int turnNumber) { this(Instant.now(), agentId, channelId, turnNumber); }
        @Override public String type() { return "turn_start"; }
    }

    record TurnEnd(Instant timestamp, String agentId, String channelId, int turnNumber) implements AgentEvent {
        public TurnEnd(String agentId, String channelId, int turnNumber) { this(Instant.now(), agentId, channelId, turnNumber); }
        @Override public String type() { return "turn_end"; }
    }

    record MessageStart(Instant timestamp, String agentId, String channelId, AgentMessage message) implements AgentEvent {
        public MessageStart(String agentId, String channelId, AgentMessage message) { this(Instant.now(), agentId, channelId, message); }
        @Override public String type() { return "message_start"; }
    }

    record MessageUpdate(Instant timestamp, String agentId, String channelId, String delta) implements AgentEvent {
        public MessageUpdate(String agentId, String channelId, String delta) { this(Instant.now(), agentId, channelId, delta); }
        @Override public String type() { return "message_update"; }
    }

    record MessageEnd(Instant timestamp, String agentId, String channelId, AgentMessage message) implements AgentEvent {
        public MessageEnd(String agentId, String channelId, AgentMessage message) { this(Instant.now(), agentId, channelId, message); }
        @Override public String type() { return "message_end"; }
    }

    record ToolExecutionStart(Instant timestamp, String agentId, String channelId, ContentBlock.ToolUse toolUse) implements AgentEvent {
        public ToolExecutionStart(String agentId, String channelId, ContentBlock.ToolUse toolUse) { this(Instant.now(), agentId, channelId, toolUse); }
        @Override public String type() { return "tool_execution_start"; }
    }

    record ToolExecutionUpdate(Instant timestamp, String agentId, String channelId, ContentBlock.ToolUse toolUse, String update) implements AgentEvent {
        public ToolExecutionUpdate(String agentId, String channelId, ContentBlock.ToolUse toolUse, String update) { this(Instant.now(), agentId, channelId, toolUse, update); }
        @Override public String type() { return "tool_execution_update"; }
    }

    record ToolExecutionEnd(Instant timestamp, String agentId, String channelId, ContentBlock.ToolUse toolUse, AgentToolResult<?> result) implements AgentEvent {
        public ToolExecutionEnd(String agentId, String channelId, ContentBlock.ToolUse toolUse, AgentToolResult<?> result) { this(Instant.now(), agentId, channelId, toolUse, result); }
        @Override public String type() { return "tool_execution_end"; }
    }

    record ScheduleFired(Instant timestamp, String agentId, String channelId, String scheduleId, String scheduleType, Map<String, Object> metadata) implements AgentEvent {
        public ScheduleFired(String agentId, String channelId, String scheduleId, String scheduleType) { this(Instant.now(), agentId, channelId, scheduleId, scheduleType, Map.of()); }
        public ScheduleFired(String agentId, String channelId, String scheduleId, String scheduleType, Map<String, Object> metadata) { this(Instant.now(), agentId, channelId, scheduleId, scheduleType, metadata); }
        @Override public String type() { return "schedule_fired"; }
    }
}
