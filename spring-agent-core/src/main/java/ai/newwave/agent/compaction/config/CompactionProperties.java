package ai.newwave.agent.compaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.compaction")
public class CompactionProperties {

    private boolean enabled = false;
    private int maxContextTokens = 100_000;
    private int preserveRecentCount = 10;
    private int maxSummaryTokens = 2000;
    private boolean preserveToolResults = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxContextTokens() { return maxContextTokens; }
    public void setMaxContextTokens(int maxContextTokens) { this.maxContextTokens = maxContextTokens; }

    public int getPreserveRecentCount() { return preserveRecentCount; }
    public void setPreserveRecentCount(int preserveRecentCount) { this.preserveRecentCount = preserveRecentCount; }

    public int getMaxSummaryTokens() { return maxSummaryTokens; }
    public void setMaxSummaryTokens(int maxSummaryTokens) { this.maxSummaryTokens = maxSummaryTokens; }

    public boolean isPreserveToolResults() { return preserveToolResults; }
    public void setPreserveToolResults(boolean preserveToolResults) { this.preserveToolResults = preserveToolResults; }
}
