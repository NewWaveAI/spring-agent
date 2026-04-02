package ai.newwave.agent.util;

import ai.newwave.agent.model.ContentBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContentBlockSerializationTest {

    @Test
    void textBlockRoundtrip() {
        List<ContentBlock> blocks = List.of(new ContentBlock.Text("Hello"));

        String json = Json.serializeContentBlocks(blocks);
        assertTrue(json.contains("\"type\":\"text\""), "Missing type discriminator: " + json);

        List<ContentBlock> deserialized = Json.deserializeContentBlocks(json);
        assertEquals(1, deserialized.size());
        assertInstanceOf(ContentBlock.Text.class, deserialized.getFirst());
        assertEquals("Hello", ((ContentBlock.Text) deserialized.getFirst()).text());
    }

    @Test
    void toolUseBlockRoundtrip() throws Exception {
        JsonNode input = new ObjectMapper().readTree("{\"location\":\"Tokyo\"}");
        List<ContentBlock> blocks = List.of(new ContentBlock.ToolUse("id-1", "get_weather", input));

        String json = Json.serializeContentBlocks(blocks);
        assertTrue(json.contains("\"type\":\"tool_use\""), "Missing type discriminator: " + json);

        List<ContentBlock> deserialized = Json.deserializeContentBlocks(json);
        assertEquals(1, deserialized.size());
        assertInstanceOf(ContentBlock.ToolUse.class, deserialized.getFirst());
        ContentBlock.ToolUse tu = (ContentBlock.ToolUse) deserialized.getFirst();
        assertEquals("id-1", tu.id());
        assertEquals("get_weather", tu.name());
        assertEquals("Tokyo", tu.input().get("location").asText());
    }

    @Test
    void toolResultBlockRoundtrip() {
        List<ContentBlock> inner = List.of(new ContentBlock.Text("result text"));
        List<ContentBlock> blocks = List.of(new ContentBlock.ToolResult("tu-1", inner, false));

        String json = Json.serializeContentBlocks(blocks);
        assertTrue(json.contains("\"type\":\"tool_result\""), "Missing type discriminator: " + json);

        List<ContentBlock> deserialized = Json.deserializeContentBlocks(json);
        assertEquals(1, deserialized.size());
        assertInstanceOf(ContentBlock.ToolResult.class, deserialized.getFirst());
        ContentBlock.ToolResult tr = (ContentBlock.ToolResult) deserialized.getFirst();
        assertEquals("tu-1", tr.toolUseId());
        assertFalse(tr.isError());
        assertEquals(1, tr.content().size());
        assertInstanceOf(ContentBlock.Text.class, tr.content().getFirst());
    }

    @Test
    void thinkingBlockRoundtrip() {
        List<ContentBlock> blocks = List.of(new ContentBlock.Thinking("Let me think..."));

        String json = Json.serializeContentBlocks(blocks);
        assertTrue(json.contains("\"type\":\"thinking\""), "Missing type discriminator: " + json);

        List<ContentBlock> deserialized = Json.deserializeContentBlocks(json);
        assertEquals(1, deserialized.size());
        assertInstanceOf(ContentBlock.Thinking.class, deserialized.getFirst());
        assertEquals("Let me think...", ((ContentBlock.Thinking) deserialized.getFirst()).thinking());
    }

    @Test
    void mixedBlocksRoundtrip() throws Exception {
        JsonNode input = new ObjectMapper().readTree("{\"q\":\"test\"}");
        List<ContentBlock> blocks = List.of(
                new ContentBlock.Text("Hello"),
                new ContentBlock.ToolUse("id-1", "search", input),
                new ContentBlock.ToolResult("id-1", List.of(new ContentBlock.Text("found it")), false),
                new ContentBlock.Thinking("reasoning...")
        );

        String json = Json.serializeContentBlocks(blocks);
        assertTrue(json.contains("\"type\":\"text\""));
        assertTrue(json.contains("\"type\":\"tool_use\""));
        assertTrue(json.contains("\"type\":\"tool_result\""));
        assertTrue(json.contains("\"type\":\"thinking\""));

        List<ContentBlock> deserialized = Json.deserializeContentBlocks(json);
        assertEquals(4, deserialized.size());
        assertInstanceOf(ContentBlock.Text.class, deserialized.get(0));
        assertInstanceOf(ContentBlock.ToolUse.class, deserialized.get(1));
        assertInstanceOf(ContentBlock.ToolResult.class, deserialized.get(2));
        assertInstanceOf(ContentBlock.Thinking.class, deserialized.get(3));
    }
}
