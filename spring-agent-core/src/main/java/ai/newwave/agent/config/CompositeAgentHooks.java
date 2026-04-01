package ai.newwave.agent.config;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.tool.AgentToolResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Chains multiple AgentHooks delegates in sequence.
 * Used when both compaction and timeline hooks (or user-defined hooks) are active.
 */
public class CompositeAgentHooks implements AgentHooks {

    private final List<AgentHooks> delegates;

    public CompositeAgentHooks(List<AgentHooks> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public Mono<BeforeToolCallResult> beforeToolCall(String toolName, ContentBlock.ToolUse toolUse) {
        return Flux.fromIterable(delegates)
                .concatMap(h -> h.beforeToolCall(toolName, toolUse))
                .filter(r -> !r.proceed())
                .next()
                .defaultIfEmpty(BeforeToolCallResult.allow());
    }

    @Override
    public Mono<AgentToolResult<?>> afterToolCall(String toolName, ContentBlock.ToolUse toolUse, AgentToolResult<?> result) {
        Mono<AgentToolResult<?>> current = Mono.just(result);
        for (AgentHooks hook : delegates) {
            current = current.flatMap(r -> hook.afterToolCall(toolName, toolUse, r));
        }
        return current;
    }

    @Override
    public List<AgentMessage> transformContext(List<AgentMessage> messages) {
        List<AgentMessage> result = messages;
        for (AgentHooks hook : delegates) {
            result = hook.transformContext(result);
        }
        return result;
    }

    @Override
    public List<AgentMessage> convertToLlm(List<AgentMessage> messages) {
        List<AgentMessage> result = messages;
        for (AgentHooks hook : delegates) {
            result = hook.convertToLlm(result);
        }
        return result;
    }
}
