package ai.newwave.agent.memory.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ai.newwave.agent.memory.MemoryService;
import ai.newwave.agent.tool.AgentTool;
import ai.newwave.agent.tool.AgentToolResult;
import ai.newwave.agent.tool.ToolCallContext;
import reactor.core.publisher.Mono;

/**
 * Agent tool for saving/updating a memory entry.
 */
public class SaveMemoryTool implements AgentTool<SaveMemoryParams, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final MemoryService memoryService;

    public SaveMemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "save_memory";
    }

    @Override
    public String label() {
        return "Save Memory";
    }

    @Override
    public String description() {
        return "Save a piece of knowledge for future reference across all conversations. " +
                "Use a descriptive key and relevant tags for easy retrieval.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode key = properties.putObject("key");
        key.put("type", "string");
        key.put("description", "Unique key for this memory (e.g., 'api-rotation-schedule')");

        ObjectNode content = properties.putObject("content");
        content.put("type", "string");
        content.put("description", "The knowledge to remember");

        ObjectNode tags = properties.putObject("tags");
        tags.put("type", "array");
        tags.putObject("items").put("type", "string");
        tags.put("description", "Tags for categorization and retrieval (e.g., ['ops', 'api'])");

        schema.putArray("required").add("key").add("content");

        return schema;
    }

    @Override
    public Class<SaveMemoryParams> parameterType() {
        return SaveMemoryParams.class;
    }

    @Override
    public Mono<AgentToolResult<String>> execute(ToolCallContext<SaveMemoryParams> context) {
        SaveMemoryParams params = context.parameters();
        return memoryService.save(params.key(), params.content(), params.tags())
                .then(Mono.just(AgentToolResult.success(
                        "Memory saved: " + params.key(), params.key())));
    }
}
