package ai.newwave.agent.config;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.tool.AgentToolResult;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Lifecycle hooks for customizing agent behavior.
 * All hooks receive a {@link HookContext} with request-scoped data.
 * All hooks are reactive — return {@link Mono} to support async operations.
 */
public interface AgentHooks {

    record BeforeToolCallResult(boolean proceed, String reason) {
        public static BeforeToolCallResult allow() {
            return new BeforeToolCallResult(true, null);
        }

        public static BeforeToolCallResult block(String reason) {
            return new BeforeToolCallResult(false, reason);
        }
    }

    record AfterToolCallResult(
            List<ContentBlock> content,
            Object details,
            boolean isError
    ) {
    }

    default Mono<BeforeToolCallResult> beforeToolCall(HookContext ctx, String toolName, ContentBlock.ToolUse toolUse) {
        return Mono.just(BeforeToolCallResult.allow());
    }

    default Mono<AgentToolResult<?>> afterToolCall(HookContext ctx, String toolName, ContentBlock.ToolUse toolUse, AgentToolResult<?> result) {
        return Mono.just(result);
    }

    default Mono<List<AgentMessage>> transformContext(HookContext ctx, List<AgentMessage> messages) {
        return Mono.just(messages);
    }

    default Mono<List<AgentMessage>> convertToLlm(HookContext ctx, List<AgentMessage> messages) {
        return Mono.just(messages);
    }
}
