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
        @JsonSubTypes.Type(value = ContentBlock.Thinking.class, name = "thinking"),
        @JsonSubTypes.Type(value = ContentBlock.Media.class, name = "media")
})
public sealed interface ContentBlock
        permits ContentBlock.Text, ContentBlock.ToolUse, ContentBlock.ToolResult, ContentBlock.Thinking,
                ContentBlock.Media {

    record Text(String text) implements ContentBlock {
    }

    record ToolUse(String id, String name, JsonNode input) implements ContentBlock {
    }

    record ToolResult(String toolUseId, List<ContentBlock> content, boolean isError) implements ContentBlock {
    }

    record Thinking(String thinking) implements ContentBlock {
    }

    /**
     * Binary media (image or document) attached to a user message, for multimodal models.
     *
     * @param mimeType the IANA media type, e.g. {@code image/png}, {@code image/jpeg}, {@code application/pdf}
     * @param data     the raw bytes, Base64-encoded (standard, no line breaks)
     */
    record Media(String mimeType, String data) implements ContentBlock {
    }
}
