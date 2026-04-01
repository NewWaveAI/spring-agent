package ai.newwave.agent.compaction;

import ai.newwave.agent.compaction.spi.TokenEstimator;
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;

import java.util.List;

/**
 * Heuristic token estimator using text length / 4.
 * Reasonable approximation for English text with Claude's tokenizer.
 */
public class SimpleTokenEstimator implements TokenEstimator {

    private static final double CHARS_PER_TOKEN = 4.0;

    @Override
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    @Override
    public int estimateTokens(AgentMessage message) {
        int tokens = 0;
        for (ContentBlock block : message.content()) {
            tokens += switch (block) {
                case ContentBlock.Text t -> estimateTokens(t.text());
                case ContentBlock.ToolUse t -> estimateTokens(t.name()) + estimateTokens(t.input().toString());
                case ContentBlock.ToolResult t -> t.content().stream()
                        .mapToInt(b -> b instanceof ContentBlock.Text txt ? estimateTokens(txt.text()) : 0)
                        .sum();
                case ContentBlock.Thinking t -> estimateTokens(t.thinking());
            };
        }
        return tokens;
    }

    @Override
    public int estimateTokens(List<AgentMessage> messages) {
        return messages.stream().mapToInt(this::estimateTokens).sum();
    }
}
