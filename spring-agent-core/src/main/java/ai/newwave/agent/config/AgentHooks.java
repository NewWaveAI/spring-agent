package ai.newwave.agent.config;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.tool.AgentToolResult;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Lifecycle hooks for customizing agent behavior.
 */
public interface AgentHooks {

    /**
     * Result of the beforeToolCall hook.
     *
     * @param proceed Whether to proceed with tool execution
     * @param reason  Optional reason if blocked
     */
    record BeforeToolCallResult(boolean proceed, String reason) {
        public static BeforeToolCallResult allow() {
            return new BeforeToolCallResult(true, null);
        }

        public static BeforeToolCallResult block(String reason) {
            return new BeforeToolCallResult(false, reason);
        }
    }

    /**
     * Result of the afterToolCall hook.
     *
     * @param content  Optionally overridden content
     * @param details  Optionally overridden details
     * @param isError  Optionally overridden error status
     */
    record AfterToolCallResult(
            List<ContentBlock> content,
            Object details,
            boolean isError
    ) {
    }

    /**
     * Called before a tool is executed. Can block execution.
     */
    default Mono<BeforeToolCallResult> beforeToolCall(String toolName, ContentBlock.ToolUse toolUse) {
        return Mono.just(BeforeToolCallResult.allow());
    }

    /**
     * Called after a tool is executed. Can modify the result.
     */
    default Mono<AgentToolResult<?>> afterToolCall(String toolName, ContentBlock.ToolUse toolUse, AgentToolResult<?> result) {
        return Mono.just(result);
    }

    /**
     * Transform the message context before sending to the LLM.
     * Useful for pruning or compacting conversation history.
     */
    default List<AgentMessage> transformContext(List<AgentMessage> messages) {
        return messages;
    }

    /**
     * Convert agent messages to LLM-compatible format.
     * By default, filters out non-standard message types.
     */
    default List<AgentMessage> convertToLlm(List<AgentMessage> messages) {
        return messages;
    }
}
