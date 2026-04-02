package ai.newwave.agent.memory;

import ai.newwave.agent.config.AgentHooks;
import ai.newwave.agent.config.HookContext;
import ai.newwave.agent.model.AgentMessage;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentHooks implementation that prepends agent memories
 * to the context, giving the agent persistent cross-conversation knowledge.
 */
public class MemoryContextHook implements AgentHooks {

    private final MemoryService memoryService;

    public MemoryContextHook(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public Mono<List<AgentMessage>> transformContext(HookContext ctx, List<AgentMessage> messages) {
        return memoryService.summarize()
                .flatMap(summary -> {
                    if (summary == null || summary.isBlank()) {
                        return Mono.just(messages);
                    }
                    AgentMessage contextMsg = AgentMessage.user("[Agent Memory]\n" + summary);
                    List<AgentMessage> result = new ArrayList<>();
                    result.add(contextMsg);
                    result.addAll(messages);
                    return Mono.just(result);
                })
                .defaultIfEmpty(messages);
    }
}
