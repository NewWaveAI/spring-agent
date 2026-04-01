package ai.newwave.agent.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Sealed interface representing content blocks within agent messages.
 * Maps to the TypeScript ContentBlock union type.
 */
public sealed interface ContentBlock
        permits ContentBlock.Text, ContentBlock.ToolUse, ContentBlock.ToolResult, ContentBlock.Thinking {

    record Text(String text) implements ContentBlock {
    }

    record ToolUse(String id, String name, JsonNode input) implements ContentBlock {
    }

    record ToolResult(String toolUseId, List<ContentBlock> content, boolean isError) implements ContentBlock {
    }

    record Thinking(String thinking) implements ContentBlock {
    }
}
