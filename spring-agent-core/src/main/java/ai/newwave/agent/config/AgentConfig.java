package ai.newwave.agent.config;

import ai.newwave.agent.core.AnthropicPromptBuilder;
import ai.newwave.agent.core.PromptBuilder;
import ai.newwave.agent.model.ThinkingLevel;
import ai.newwave.agent.tool.AgentTool;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level configuration for an Agent instance.
 *
 * @param systemPrompt  System instructions for the LLM
 * @param model         Model identifier (e.g. "claude-sonnet-4-6")
 * @param thinkingLevel Thinking/reasoning level
 * @param maxTokens     Maximum output tokens
 * @param tools         Available tools
 * @param loopConfig    Agent loop configuration
 * @param promptBuilder Strategy for building LLM prompts (default: Anthropic with caching)
 */
public record AgentConfig(
        String systemPrompt,
        String model,
        ThinkingLevel thinkingLevel,
        int maxTokens,
        List<AgentTool<?, ?>> tools,
        AgentLoopConfig loopConfig,
        PromptBuilder promptBuilder
) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String systemPrompt = "You are a helpful assistant.";
        private String model = "claude-sonnet-4-6";
        private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
        private int maxTokens = 8192;
        private List<AgentTool<?, ?>> tools = new ArrayList<>();
        private AgentLoopConfig loopConfig = AgentLoopConfig.defaults();
        private PromptBuilder promptBuilder = new AnthropicPromptBuilder();

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder thinkingLevel(ThinkingLevel thinkingLevel) {
            this.thinkingLevel = thinkingLevel;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder tools(List<AgentTool<?, ?>> tools) {
            this.tools = tools;
            return this;
        }

        public Builder addTool(AgentTool<?, ?> tool) {
            this.tools.add(tool);
            return this;
        }

        public Builder loopConfig(AgentLoopConfig loopConfig) {
            this.loopConfig = loopConfig;
            return this;
        }

        public Builder promptBuilder(PromptBuilder promptBuilder) {
            this.promptBuilder = promptBuilder;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(systemPrompt, model, thinkingLevel, maxTokens, tools, loopConfig, promptBuilder);
        }
    }
}
