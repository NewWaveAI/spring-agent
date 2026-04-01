package ai.newwave.agent.core;

import ai.newwave.agent.tool.AgentTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Adapts an AgentTool to Spring AI's ToolCallback interface.
 * This is used to register tool definitions with the ChatModel so it knows
 * what tools are available. Actual tool execution is handled by the AgentLoop,
 * not by Spring AI's internal tool execution.
 */
public class AgentToolCallbackAdapter implements ToolCallback {

    private final AgentTool<?, ?> agentTool;
    private final ToolDefinition toolDefinition;

    public AgentToolCallbackAdapter(AgentTool<?, ?> agentTool) {
        this.agentTool = agentTool;
        this.toolDefinition = ToolDefinition.builder()
                .name(agentTool.name())
                .description(agentTool.description())
                .inputSchema(agentTool.parameterSchema().toString())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        // This should not be called since we disable internal tool execution.
        // If it is called, return a no-op response.
        throw new UnsupportedOperationException(
                "AgentToolCallbackAdapter.call() should not be invoked directly. " +
                "Tool execution is handled by the AgentLoop.");
    }
}
