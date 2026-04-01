package ai.newwave.agent.model;

public enum ThinkingLevel {

    OFF(0, 0),
    LOW(1024, 4096),
    MEDIUM(4096, 8192),
    HIGH(16384, 32768),
    XHIGH(65536, 131072);

    private final int budgetTokens;
    private final int maxCompletionTokens;

    ThinkingLevel(int budgetTokens, int maxCompletionTokens) {
        this.budgetTokens = budgetTokens;
        this.maxCompletionTokens = maxCompletionTokens;
    }

    public int getBudgetTokens() {
        return budgetTokens;
    }

    public int getMaxCompletionTokens() {
        return maxCompletionTokens;
    }
}
