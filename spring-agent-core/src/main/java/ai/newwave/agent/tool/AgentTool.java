package ai.newwave.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;

/**
 * Definition of a tool that the agent can invoke.
 *
 * @param <P> The parameter type for this tool
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
     */
    JsonNode parameterSchema();

    /**
     * The Java class for deserializing input JSON into typed parameters.
     */
    Class<P> parameterType();

    /**
     * Execute the tool with the given context.
     * Returns a Mono to support both sync and async implementations.
     */
    Mono<AgentToolResult<D>> execute(ToolCallContext<P> context);
}
