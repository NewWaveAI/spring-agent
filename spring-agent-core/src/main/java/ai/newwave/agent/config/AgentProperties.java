package ai.newwave.agent.config;

import ai.newwave.agent.model.ThinkingLevel;
import ai.newwave.agent.model.ToolExecutionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private String id = "default";
    private String systemPrompt = "You are a helpful assistant.";
    private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
    private ToolExecutionMode toolExecutionMode = ToolExecutionMode.PARALLEL;
    private int maxTurns = 25;
    private int maxTokens = 8192;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public ThinkingLevel getThinkingLevel() {
        return thinkingLevel;
    }

    public void setThinkingLevel(ThinkingLevel thinkingLevel) {
        this.thinkingLevel = thinkingLevel;
    }

    public ToolExecutionMode getToolExecutionMode() {
        return toolExecutionMode;
    }

    public void setToolExecutionMode(ToolExecutionMode toolExecutionMode) {
        this.toolExecutionMode = toolExecutionMode;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }
}
