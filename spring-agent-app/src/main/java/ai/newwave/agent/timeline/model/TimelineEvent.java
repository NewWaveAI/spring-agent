package ai.newwave.agent.timeline.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A single entry in the activity timeline.
 * Not a chat message — represents "what happened" (tool executed, user logged in, etc.).
 */
public record TimelineEvent(
        String id,
        Instant timestamp,
        TimelineActor actor,
        String eventType,
        String summary,
        Map<String, Object> metadata,
        String agentId,
        String conversationId
) {

    public TimelineEvent {
        if (id == null) id = UUID.randomUUID().toString();
        if (timestamp == null) timestamp = Instant.now();
        if (metadata == null) metadata = Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private Instant timestamp;
        private TimelineActor actor;
        private String eventType;
        private String summary;
        private Map<String, Object> metadata;
        private String agentId;
        private String conversationId;

        public Builder id(String v) { this.id = v; return this; }
        public Builder timestamp(Instant v) { this.timestamp = v; return this; }
        public Builder actor(TimelineActor v) { this.actor = v; return this; }
        public Builder eventType(String v) { this.eventType = v; return this; }
        public Builder summary(String v) { this.summary = v; return this; }
        public Builder metadata(Map<String, Object> v) { this.metadata = v; return this; }
        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder conversationId(String v) { this.conversationId = v; return this; }

        public TimelineEvent build() {
            return new TimelineEvent(id, timestamp, actor, eventType, summary, metadata, agentId, conversationId);
        }
    }
}
