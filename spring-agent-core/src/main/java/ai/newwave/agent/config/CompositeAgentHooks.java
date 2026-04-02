package ai.newwave.agent.config;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.tool.AgentToolResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Chains multiple AgentHooks delegates in sequence.
 */
public class CompositeAgentHooks implements AgentHooks {

    private final List<AgentHooks> delegates;

    public CompositeAgentHooks(List<AgentHooks> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public Mono<BeforeToolCallResult> beforeToolCall(HookContext ctx, String toolName, ContentBlock.ToolUse toolUse) {
        return Flux.fromIterable(delegates)
                .concatMap(h -> h.beforeToolCall(ctx, toolName, toolUse))
                .filter(r -> !r.proceed())
                .next()
                .defaultIfEmpty(BeforeToolCallResult.allow());
    }

    @Override
    public Mono<AgentToolResult<?>> afterToolCall(HookContext ctx, String toolName, ContentBlock.ToolUse toolUse, AgentToolResult<?> result) {
        Mono<AgentToolResult<?>> current = Mono.just(result);
        for (AgentHooks hook : delegates) {
            current = current.flatMap(r -> hook.afterToolCall(ctx, toolName, toolUse, r));
        }
        return current;
    }

    @Override
    public Mono<List<AgentMessage>> transformContext(HookContext ctx, List<AgentMessage> messages) {
        Mono<List<AgentMessage>> current = Mono.just(messages);
        for (AgentHooks hook : delegates) {
            current = current.flatMap(msgs -> hook.transformContext(ctx, msgs));
        }
        return current;
    }

    @Override
    public Mono<List<AgentMessage>> convertToLlm(HookContext ctx, List<AgentMessage> messages) {
        Mono<List<AgentMessage>> current = Mono.just(messages);
        for (AgentHooks hook : delegates) {
            current = current.flatMap(msgs -> hook.convertToLlm(ctx, msgs));
        }
        return current;
    }
}
