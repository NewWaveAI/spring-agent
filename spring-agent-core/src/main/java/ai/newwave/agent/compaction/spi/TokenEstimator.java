package ai.newwave.agent.compaction.spi;

import ai.newwave.agent.model.AgentMessage;

import java.util.List;

/**
 * SPI for estimating token counts of text and messages.
 */
public interface TokenEstimator {

    int estimateTokens(String text);

    int estimateTokens(AgentMessage message);

    int estimateTokens(List<AgentMessage> messages);
}
