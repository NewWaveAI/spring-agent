package ai.newwave.agent.compaction;

import ai.newwave.agent.compaction.model.CompactionConfig;
import ai.newwave.agent.compaction.model.CompactionResult;
import ai.newwave.agent.compaction.spi.CompactionStrategy;
import ai.newwave.agent.compaction.spi.TokenEstimator;
import ai.newwave.agent.config.AgentHooks;
import ai.newwave.agent.model.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentHooks implementation that triggers conversation compaction
 * when the context exceeds the configured token threshold.
 */
public class CompactionHook implements AgentHooks {

    private static final Logger log = LoggerFactory.getLogger(CompactionHook.class);

    private final CompactionStrategy strategy;
    private final CompactionConfig config;
    private final TokenEstimator tokenEstimator;
    private volatile CompactionResult lastCompaction;

    public CompactionHook(CompactionStrategy strategy, CompactionConfig config, TokenEstimator tokenEstimator) {
        this.strategy = strategy;
        this.config = config;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public List<AgentMessage> transformContext(List<AgentMessage> messages) {
        int tokenCount = tokenEstimator.estimateTokens(messages);
        if (tokenCount <= config.maxContextTokens()) {
            return messages;
        }

        log.info("Context exceeds {} tokens (estimated {}), triggering compaction",
                config.maxContextTokens(), tokenCount);

        CompactionResult result = strategy.compact(messages, config).block();
        if (result == null) {
            return messages;
        }

        lastCompaction = result;

        int splitIndex = Math.max(0, messages.size() - config.preserveRecentCount());
        List<AgentMessage> compacted = new ArrayList<>();
        compacted.add(result.summaryMessage());
        compacted.addAll(messages.subList(splitIndex, messages.size()));
        return compacted;
    }

    public CompactionResult getLastCompaction() {
        return lastCompaction;
    }
}
