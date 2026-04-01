package ai.newwave.agent.compaction;

import ai.newwave.agent.compaction.model.CompactionConfig;
import ai.newwave.agent.compaction.model.CompactionResult;
import ai.newwave.agent.compaction.spi.CompactionStrategy;
import ai.newwave.agent.compaction.spi.TokenEstimator;
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compaction strategy that uses the LLM to summarize older messages.
 */
public class LlmCompactionStrategy implements CompactionStrategy {

    private static final Logger log = LoggerFactory.getLogger(LlmCompactionStrategy.class);

    private final ChatModel chatModel;
    private final TokenEstimator tokenEstimator;

    public LlmCompactionStrategy(ChatModel chatModel, TokenEstimator tokenEstimator) {
        this.chatModel = chatModel;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public Mono<CompactionResult> compact(List<AgentMessage> messages, CompactionConfig config) {
        int totalTokens = tokenEstimator.estimateTokens(messages);
        if (totalTokens <= config.maxContextTokens()) {
            return Mono.empty();
        }

        int splitIndex = Math.max(0, messages.size() - config.preserveRecentCount());
        if (splitIndex == 0) {
            return Mono.empty();
        }

        List<AgentMessage> toSummarize = messages.subList(0, splitIndex);
        String transcript = formatTranscript(toSummarize, config.preserveToolResults());
        int originalTokens = tokenEstimator.estimateTokens(toSummarize);

        return Mono.fromCallable(() -> {
            log.info("Compacting {} messages ({} estimated tokens) into summary", toSummarize.size(), originalTokens);

            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(config.summaryPrompt()),
                    new UserMessage(transcript)
            ));

            ChatResponse response = chatModel.call(prompt);
            String summary = response.getResult().getOutput().getText();

            AgentMessage summaryMessage = AgentMessage.user("[Conversation Summary]\n" + summary);
            int compactedTokens = tokenEstimator.estimateTokens(summaryMessage);

            log.info("Compaction complete: {} tokens -> {} tokens", originalTokens, compactedTokens);

            return new CompactionResult(
                    summaryMessage,
                    toSummarize.size(),
                    originalTokens,
                    compactedTokens,
                    Instant.now()
            );
        });
    }

    private String formatTranscript(List<AgentMessage> messages, boolean includeToolResults) {
        StringBuilder sb = new StringBuilder();
        for (AgentMessage msg : messages) {
            String role = switch (msg.role()) {
                case USER -> "User";
                case ASSISTANT -> "Assistant";
                case TOOL_RESULT -> includeToolResults ? "Tool Result" : null;
            };
            if (role == null) continue;

            String content = msg.content().stream()
                    .map(block -> switch (block) {
                        case ContentBlock.Text t -> t.text();
                        case ContentBlock.ToolUse t -> "[Called tool: " + t.name() + "]";
                        case ContentBlock.ToolResult t -> t.content().stream()
                                .filter(b -> b instanceof ContentBlock.Text)
                                .map(b -> ((ContentBlock.Text) b).text())
                                .collect(Collectors.joining("\n"));
                        case ContentBlock.Thinking t -> "[Thinking: " + t.thinking().substring(0, Math.min(100, t.thinking().length())) + "...]";
                    })
                    .collect(Collectors.joining("\n"));

            sb.append(role).append(": ").append(content).append("\n\n");
        }
        return sb.toString();
    }
}
