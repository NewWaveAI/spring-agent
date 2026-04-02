package ai.newwave.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import reactor.core.publisher.Mono;

/**
 * Definition of a tool that the agent can invoke.
 *
 * @param <P> The parameter type for this tool (typically a record)
 * @param <D> The detail type returned by this tool
 */
public interface AgentTool<P, D> {

    ObjectMapper SCHEMA_MAPPER = new ObjectMapper().findAndRegisterModules();

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
     * Default implementation uses Spring AI's {@link JsonSchemaGenerator} (backed by victools).
     * Use {@link com.fasterxml.jackson.annotation.JsonPropertyDescription} on record components
     * to provide field descriptions to the LLM.
     * Override to provide a custom schema.
     */
    default JsonNode parameterSchema() {
        try {
            String schema = JsonSchemaGenerator.generateForType(parameterType());
            return SCHEMA_MAPPER.readTree(schema);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate schema for " + parameterType().getName(), e);
        }
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
