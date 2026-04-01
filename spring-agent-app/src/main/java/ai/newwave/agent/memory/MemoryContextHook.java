package ai.newwave.agent.memory;

import ai.newwave.agent.config.AgentHooks;
import ai.newwave.agent.model.AgentMessage;

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
    public List<AgentMessage> transformContext(List<AgentMessage> messages) {
        String summary = memoryService.summarize().block();

        if (summary == null || summary.isBlank()) {
            return messages;
        }

        AgentMessage contextMsg = AgentMessage.user("[Agent Memory]\n" + summary);

        List<AgentMessage> result = new ArrayList<>();
        result.add(contextMsg);
        result.addAll(messages);
        return result;
    }
}
