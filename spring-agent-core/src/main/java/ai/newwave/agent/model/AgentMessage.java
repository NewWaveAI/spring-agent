package ai.newwave.agent.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A message in the agent's conversation history.
 * Wraps content blocks with role and metadata.
 */
public record AgentMessage(
        String id,
        MessageRole role,
        List<ContentBlock> content,
        Instant timestamp
) {

    public AgentMessage(MessageRole role, List<ContentBlock> content) {
        this(UUID.randomUUID().toString(), role, content, Instant.now());
    }

    public static AgentMessage user(String text) {
        return new AgentMessage(MessageRole.USER, List.of(new ContentBlock.Text(text)));
    }

    public static AgentMessage assistant(List<ContentBlock> content) {
        return new AgentMessage(MessageRole.ASSISTANT, content);
    }

    public static AgentMessage toolResult(String toolUseId, List<ContentBlock> content, boolean isError) {
        return new AgentMessage(
                MessageRole.TOOL_RESULT,
                List.of(new ContentBlock.ToolResult(toolUseId, content, isError))
        );
    }
}
