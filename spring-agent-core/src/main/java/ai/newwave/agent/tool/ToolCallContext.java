package ai.newwave.agent.tool;

import java.util.Map;

/**
 * Context provided to a tool when it is executed.
 *
 * @param toolUseId      The unique ID of this tool invocation
 * @param name           The tool name
 * @param parameters     The deserialized parameters
 * @param agentId        The agent (user/tenant) that triggered this call
 * @param conversationId The conversation this call belongs to
 * @param attributes     Custom request-scoped attributes (e.g., workspaceId)
 * @param <P>            Parameter type
 */
public record ToolCallContext<P>(
        String toolUseId,
        String name,
        P parameters,
        String agentId,
        String conversationId,
        Map<String, Object> attributes
) {
}
