package ai.newwave.agent.tool;

/**
 * Context provided to a tool when it is executed.
 *
 * @param toolUseId  The unique ID of this tool invocation
 * @param name       The tool name
 * @param parameters The deserialized parameters
 * @param <P>        Parameter type
 */
public record ToolCallContext<P>(
        String toolUseId,
        String name,
        P parameters
) {
}
