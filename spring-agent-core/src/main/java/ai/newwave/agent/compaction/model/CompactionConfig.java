package ai.newwave.agent.compaction.model;

/**
 * Configuration for conversation compaction behavior.
 *
 * @param maxContextTokens    Trigger compaction when total tokens exceed this
 * @param preserveRecentCount Number of recent messages to keep intact
 * @param maxSummaryTokens    Maximum tokens for the generated summary
 * @param summaryPrompt       System prompt for the summarization LLM call
 * @param preserveToolResults Whether to include tool results in the summary
 */
public record CompactionConfig(
        int maxContextTokens,
        int preserveRecentCount,
        int maxSummaryTokens,
        String summaryPrompt,
        boolean preserveToolResults
) {

    private static final String DEFAULT_SUMMARY_PROMPT = """
            Summarize the following conversation history. Preserve:
            - Key decisions made by the user
            - Important facts and user preferences discovered
            - Tool execution outcomes and their significance
            - Any commitments or action items
            - Critical context needed for continuing the conversation
            Be concise but comprehensive. Use bullet points for clarity.""";

    public static CompactionConfig defaults() {
        return new CompactionConfig(100_000, 10, 2000, DEFAULT_SUMMARY_PROMPT, true);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxContextTokens = 100_000;
        private int preserveRecentCount = 10;
        private int maxSummaryTokens = 2000;
        private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;
        private boolean preserveToolResults = true;

        public Builder maxContextTokens(int v) { this.maxContextTokens = v; return this; }
        public Builder preserveRecentCount(int v) { this.preserveRecentCount = v; return this; }
        public Builder maxSummaryTokens(int v) { this.maxSummaryTokens = v; return this; }
        public Builder summaryPrompt(String v) { this.summaryPrompt = v; return this; }
        public Builder preserveToolResults(boolean v) { this.preserveToolResults = v; return this; }

        public CompactionConfig build() {
            return new CompactionConfig(maxContextTokens, preserveRecentCount, maxSummaryTokens, summaryPrompt, preserveToolResults);
        }
    }
}
