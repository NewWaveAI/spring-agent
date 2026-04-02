package ai.newwave.agent.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Sealed interface representing content blocks within agent messages.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentBlock.Text.class, name = "text"),
        @JsonSubTypes.Type(value = ContentBlock.ToolUse.class, name = "tool_use"),
        @JsonSubTypes.Type(value = ContentBlock.ToolResult.class, name = "tool_result"),
        @JsonSubTypes.Type(value = ContentBlock.Thinking.class, name = "thinking")
})
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
