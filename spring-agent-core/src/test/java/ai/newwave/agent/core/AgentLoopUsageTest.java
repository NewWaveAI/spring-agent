package ai.newwave.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentLoop#readLongAccessor} pulls Anthropic prompt-cache token counts off the
 * provider-native usage object reflectively, so core stays decoupled from the Anthropic SDK
 * type and other providers (no such accessor) report 0 instead of breaking the loop.
 */
class AgentLoopUsageTest {

    /** Mirrors the official Anthropic SDK shape: cache accessors return Optional<Long>. */
    public static final class OptionalUsage {
        public Optional<Long> cacheCreationInputTokens() { return Optional.of(120L); }
        public Optional<Long> cacheReadInputTokens() { return Optional.empty(); }
    }

    /** A provider exposing primitive long accessors. */
    public static final class LongUsage {
        public long cacheReadInputTokens() { return 7L; }
    }

    /** A provider with no cache accessors at all (e.g. non-Anthropic). */
    public static final class NoCacheUsage {
        public long inputTokens() { return 5L; }
    }

    @Test
    @DisplayName("Optional<Long> accessor: present → value, empty → 0")
    void readsOptionalAccessor() {
        OptionalUsage u = new OptionalUsage();
        assertEquals(120L, AgentLoop.readLongAccessor(u, "cacheCreationInputTokens"));
        assertEquals(0L, AgentLoop.readLongAccessor(u, "cacheReadInputTokens"));
    }

    @Test
    @DisplayName("primitive long accessor is read")
    void readsPrimitiveAccessor() {
        assertEquals(7L, AgentLoop.readLongAccessor(new LongUsage(), "cacheReadInputTokens"));
    }

    @Test
    @DisplayName("null native usage → 0")
    void nullUsageIsZero() {
        assertEquals(0L, AgentLoop.readLongAccessor(null, "cacheReadInputTokens"));
    }

    @Test
    @DisplayName("missing accessor (other provider) → 0, no throw")
    void missingAccessorIsZero() {
        assertEquals(0L, AgentLoop.readLongAccessor(new NoCacheUsage(), "cacheReadInputTokens"));
    }
}
