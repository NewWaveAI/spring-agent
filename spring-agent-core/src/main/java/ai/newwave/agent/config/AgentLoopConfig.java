package ai.newwave.agent.config;

import ai.newwave.agent.model.ToolExecutionMode;

/**
 * Configuration for the agent loop behavior.
 *
 * @param maxTurns              Maximum number of LLM turns before stopping
 * @param toolExecutionMode     Sequential or parallel tool execution
 * @param hooks                 Lifecycle hooks
 * @param maxToolResultsInContext Maximum number of recent tool_use/tool_result pairs to include in LLM context (0 = unlimited)
 */
public record AgentLoopConfig(
        int maxTurns,
        ToolExecutionMode toolExecutionMode,
        AgentHooks hooks,
        int maxToolResultsInContext
) {

    public static AgentLoopConfig defaults() {
        return new AgentLoopConfig(25, ToolExecutionMode.PARALLEL, new AgentHooks() {}, 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxTurns = 25;
        private ToolExecutionMode toolExecutionMode = ToolExecutionMode.PARALLEL;
        private AgentHooks hooks = new AgentHooks() {};
        private int maxToolResultsInContext = 0;

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder toolExecutionMode(ToolExecutionMode mode) {
            this.toolExecutionMode = mode;
            return this;
        }

        public Builder hooks(AgentHooks hooks) {
            this.hooks = hooks;
            return this;
        }

        public Builder maxToolResultsInContext(int max) {
            this.maxToolResultsInContext = max;
            return this;
        }

        public AgentLoopConfig build() {
            return new AgentLoopConfig(maxTurns, toolExecutionMode, hooks, maxToolResultsInContext);
        }
    }
}
