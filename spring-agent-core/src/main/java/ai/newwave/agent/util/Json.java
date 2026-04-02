package ai.newwave.agent.util;

import ai.newwave.agent.model.ContentBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.util.List;

/**
 * Shared ObjectMapper for the spring-agent library.
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final ObjectWriter CONTENT_BLOCK_WRITER =
            MAPPER.writerFor(new TypeReference<List<ContentBlock>>() {});

    private Json() {}

    /**
     * Serialize a list of ContentBlocks with type discriminators.
     * Uses writerFor to ensure Jackson sees the ContentBlock sealed interface type,
     * not the concrete record type, so @JsonTypeInfo annotations are respected.
     */
    public static String serializeContentBlocks(List<ContentBlock> blocks) {
        try {
            return CONTENT_BLOCK_WRITER.writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize content blocks", e);
        }
    }

    /**
     * Deserialize a JSON string to a list of ContentBlocks.
     */
    public static List<ContentBlock> deserializeContentBlocks(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize content blocks", e);
        }
    }
}
