package ai.newwave.agent.core;

import ai.newwave.agent.model.AgentMessage;

import java.util.HashMap;
import java.util.Map;

/**
 * Request to stream an agent conversation turn.
 */
public record AgentRequest(
        String agentId,
        String conversationId,
        AgentMessage message,
        Map<String, Object> attributes
) {

    public AgentRequest {
        if (attributes == null) attributes = Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String agentId;
        private String conversationId = Agent.DEFAULT_CONVERSATION;
        private AgentMessage message;
        private final Map<String, Object> attributes = new HashMap<>();

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder message(String message) {
            this.message = AgentMessage.user(message);
            return this;
        }

        public Builder message(AgentMessage message) {
            this.message = message;
            return this;
        }

        public Builder attribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes.putAll(attributes);
            return this;
        }

        public AgentRequest build() {
            if (agentId == null) throw new IllegalArgumentException("agentId is required");
            if (message == null) throw new IllegalArgumentException("message is required");
            return new AgentRequest(agentId, conversationId, message, Map.copyOf(attributes));
        }
    }
}
