package ai.newwave.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Mono;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Definition of a tool that the agent can invoke.
 *
 * @param <P> The parameter type for this tool (typically a record)
 * @param <D> The detail type returned by this tool
 */
public interface AgentTool<P, D> {

    /**
     * Unique name identifying this tool.
     */
    String name();

    /**
     * Human-readable display label.
     */
    String label();

    /**
     * Description of what this tool does (sent to the LLM).
     */
    String description();

    /**
     * JSON Schema describing the tool's parameters.
     * Default implementation auto-generates from the {@link #parameterType()} record,
     * using {@link Description} annotations for field descriptions.
     * Override to provide a custom schema.
     */
    default JsonNode parameterSchema() {
        return SchemaGenerator.fromRecord(parameterType());
    }

    /**
     * The Java class for deserializing input JSON into typed parameters.
     */
    Class<P> parameterType();

    /**
     * Execute the tool with the given context.
     */
    Mono<AgentToolResult<D>> execute(ToolCallContext<P> context);
}

/**
 * Generates JSON Schema from Java record types.
 */
class SchemaGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    static JsonNode fromRecord(Class<?> recordType) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");

        if (recordType.isRecord()) {
            for (RecordComponent component : recordType.getRecordComponents()) {
                String fieldName = component.getName();
                Class<?> fieldType = component.getType();

                ObjectNode prop = properties.putObject(fieldName);
                prop.put("type", toJsonType(fieldType));

                // Handle array/list element type
                if (fieldType == List.class || fieldType == Set.class || fieldType.isArray()) {
                    prop.putObject("items").put("type", "string");
                }

                // Handle Map type
                if (fieldType == Map.class) {
                    prop.put("type", "object");
                }

                // Add description from @Description annotation
                Description desc = component.getAnnotation(Description.class);
                if (desc != null) {
                    prop.put("description", desc.value());
                }

                required.add(fieldName);
            }
        }

        return schema;
    }

    private static String toJsonType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class) return "integer";
        if (type == long.class || type == Long.class) return "integer";
        if (type == double.class || type == Double.class) return "number";
        if (type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type == List.class || type == Set.class || type.isArray()) return "array";
        if (type == Map.class) return "object";
        return "string"; // fallback
    }
}
