package ai.newwave.agent.config;

import java.util.Map;

/**
 * Request-scoped context passed to all agent hooks.
 *
 * @param agentId        The user/tenant who triggered this request
 * @param conversationId The conversation this request belongs to
 * @param attributes     Custom attributes from AgentRequest
 */
public record HookContext(
        String agentId,
        String conversationId,
        Map<String, Object> attributes
) {
}
