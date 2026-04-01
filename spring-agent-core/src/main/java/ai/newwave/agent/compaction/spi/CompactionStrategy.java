package ai.newwave.agent.compaction.spi;

import ai.newwave.agent.compaction.model.CompactionConfig;
import ai.newwave.agent.compaction.model.CompactionResult;
import ai.newwave.agent.model.AgentMessage;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * SPI for conversation compaction strategies.
 */
public interface CompactionStrategy {

    /**
     * Compact the given messages according to the config.
     * Returns empty Mono if no compaction is needed.
     */
    Mono<CompactionResult> compact(List<AgentMessage> messages, CompactionConfig config);
}
