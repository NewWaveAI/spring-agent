package ai.newwave.agent.tool;

import ai.newwave.agent.model.ContentBlock;

import java.util.List;

/**
 * Result of an agent tool execution.
 *
 * @param content  Content blocks returned by the tool
 * @param details  Optional structured details (typed per tool)
 * @param isError  Whether the tool execution resulted in an error
 * @param <D>      Type of the structured details
 */
public record AgentToolResult<D>(
        List<ContentBlock> content,
        D details,
        boolean isError
) {

    public static <D> AgentToolResult<D> success(String text) {
        return new AgentToolResult<>(List.of(new ContentBlock.Text(text)), null, false);
    }

    public static <D> AgentToolResult<D> success(String text, D details) {
        return new AgentToolResult<>(List.of(new ContentBlock.Text(text)), details, false);
    }

    public static <D> AgentToolResult<D> error(String message) {
        return new AgentToolResult<>(List.of(new ContentBlock.Text(message)), null, true);
    }

    public static <D> AgentToolResult<D> of(List<ContentBlock> content) {
        return new AgentToolResult<>(content, null, false);
    }
}
