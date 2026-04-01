package ai.newwave.agent.timeline.model;

import java.time.Instant;
import java.util.List;

/**
 * Query parameters for searching the activity timeline.
 */
public record TimelineQuery(
        String agentId,
        String conversationId,
        List<String> eventTypes,
        Instant since,
        Instant until,
        int limit,
        int offset
) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String agentId;
        private String conversationId;
        private List<String> eventTypes;
        private Instant since;
        private Instant until;
        private int limit = 50;
        private int offset = 0;

        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder conversationId(String v) { this.conversationId = v; return this; }
        public Builder eventTypes(List<String> v) { this.eventTypes = v; return this; }
        public Builder since(Instant v) { this.since = v; return this; }
        public Builder until(Instant v) { this.until = v; return this; }
        public Builder limit(int v) { this.limit = v; return this; }
        public Builder offset(int v) { this.offset = v; return this; }

        public TimelineQuery build() {
            return new TimelineQuery(agentId, conversationId, eventTypes, since, until, limit, offset);
        }
    }
}
