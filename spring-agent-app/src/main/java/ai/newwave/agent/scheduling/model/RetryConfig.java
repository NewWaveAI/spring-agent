package ai.newwave.agent.scheduling.model;

import java.time.Duration;

/**
 * Retry configuration for scheduled events.
 */
public record RetryConfig(
        int maxRetries,
        Duration initialDelay,
        double backoffMultiplier
) {
    public static RetryConfig defaults() {
        return new RetryConfig(3, Duration.ofSeconds(1), 2.0);
    }

    public static RetryConfig none() {
        return new RetryConfig(0, Duration.ZERO, 1.0);
    }
}
