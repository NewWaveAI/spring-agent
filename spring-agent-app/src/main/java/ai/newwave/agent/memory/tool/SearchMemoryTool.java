package ai.newwave.agent.memory.tool;

import ai.newwave.agent.util.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ai.newwave.agent.memory.MemoryService;
import ai.newwave.agent.memory.model.Memory;
import ai.newwave.agent.tool.AgentTool;
import ai.newwave.agent.tool.AgentToolResult;
import ai.newwave.agent.tool.ToolCallContext;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent tool for searching memories by tags.
 */
public class SearchMemoryTool implements AgentTool<SearchMemoryParams, List<Memory>> {

    

    private final MemoryService memoryService;

    public SearchMemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "search_memory";
    }

    @Override
    public String label() {
        return "Search Memory";
    }

    @Override
    public String description() {
        return "Search saved memories by tags. Returns matching knowledge entries. " +
                "If no tags provided, returns all memories.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = Json.MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode tags = properties.putObject("tags");
        tags.put("type", "array");
        tags.putObject("items").put("type", "string");
        tags.put("description", "Tags to search for (returns memories matching any tag)");

        return schema;
    }

    @Override
    public Class<SearchMemoryParams> parameterType() {
        return SearchMemoryParams.class;
    }

    @Override
    public Mono<AgentToolResult<List<Memory>>> execute(ToolCallContext<SearchMemoryParams> context) {
        SearchMemoryParams params = context.parameters();

        var source = params.tags().isEmpty()
                ? memoryService.listAll()
                : memoryService.search(params.tags());

        return source.collectList()
                .map(memories -> {
                    String formatted = memories.stream()
                            .map(m -> "[%s] (tags: %s) %s".formatted(
                                    m.key(), String.join(", ", m.tags()), m.content()))
                            .collect(Collectors.joining("\n"));
                    if (formatted.isEmpty()) formatted = "No memories found.";
                    return AgentToolResult.success(formatted, memories);
                });
    }
}
