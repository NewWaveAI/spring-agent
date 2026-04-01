package ai.newwave.agent.compaction.model;

import ai.newwave.agent.model.AgentMessage;

import java.time.Instant;

/**
 * Result of a compaction operation.
 *
 * @param summaryMessage        The compacted summary as an AgentMessage
 * @param originalMessageCount  Number of messages before compaction
 * @param originalTokenEstimate Estimated tokens before compaction
 * @param compactedTokenEstimate Estimated tokens of the summary
 * @param compactedAt           When compaction occurred
 */
public record CompactionResult(
        AgentMessage summaryMessage,
        int originalMessageCount,
        int originalTokenEstimate,
        int compactedTokenEstimate,
        Instant compactedAt
) {
}
