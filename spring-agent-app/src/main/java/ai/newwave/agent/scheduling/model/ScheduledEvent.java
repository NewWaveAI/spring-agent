package ai.newwave.agent.scheduling.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A scheduled event that triggers an agent action.
 */
public record ScheduledEvent(
        String id,
        ScheduleType type,
        String scheduleExpression,
        String timezone,
        SchedulePayload payload,
        RetryConfig retryConfig,
        Instant createdAt,
        Instant nextFireTime,
        boolean enabled
) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private ScheduleType type;
        private String scheduleExpression;
        private String timezone = "UTC";
        private SchedulePayload payload;
        private RetryConfig retryConfig = RetryConfig.defaults();
        private Instant createdAt = Instant.now();
        private Instant nextFireTime;
        private boolean enabled = true;

        public Builder id(String v) { this.id = v; return this; }
        public Builder type(ScheduleType v) { this.type = v; return this; }
        public Builder scheduleExpression(String v) { this.scheduleExpression = v; return this; }
        public Builder timezone(String v) { this.timezone = v; return this; }
        public Builder payload(SchedulePayload v) { this.payload = v; return this; }
        public Builder retryConfig(RetryConfig v) { this.retryConfig = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder nextFireTime(Instant v) { this.nextFireTime = v; return this; }
        public Builder enabled(boolean v) { this.enabled = v; return this; }

        public ScheduledEvent build() {
            return new ScheduledEvent(id, type, scheduleExpression, timezone, payload, retryConfig, createdAt, nextFireTime, enabled);
        }
    }
}
