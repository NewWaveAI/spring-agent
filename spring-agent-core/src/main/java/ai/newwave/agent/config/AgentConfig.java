package ai.newwave.agent.config;

import ai.newwave.agent.model.ThinkingLevel;
import ai.newwave.agent.tool.AgentTool;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level configuration for an Agent instance.
 *
 * @param agentId       Unique identifier for this agent (e.g. tenant/workspace ID)
 * @param systemPrompt  System instructions for the LLM
 * @param model         Model identifier (e.g. "claude-sonnet-4-5-20250514")
 * @param thinkingLevel Thinking/reasoning level
 * @param maxTokens     Maximum output tokens
 * @param tools         Available tools
 * @param loopConfig    Agent loop configuration
 * @param sessionId     Optional session ID for cache-aware backends
 */
public record AgentConfig(
        String agentId,
        String systemPrompt,
        String model,
        ThinkingLevel thinkingLevel,
        int maxTokens,
        List<AgentTool<?, ?>> tools,
        AgentLoopConfig loopConfig,
        String sessionId
) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String agentId = "default";
        private String systemPrompt = "You are a helpful assistant.";
        private String model = "claude-sonnet-4-5-20250514";
        private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
        private int maxTokens = 8192;
        private List<AgentTool<?, ?>> tools = new ArrayList<>();
        private AgentLoopConfig loopConfig = AgentLoopConfig.defaults();
        private String sessionId;

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

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

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(agentId, systemPrompt, model, thinkingLevel, maxTokens, tools, loopConfig, sessionId);
        }
    }
}
