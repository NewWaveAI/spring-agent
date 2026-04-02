package ai.newwave.agent.state.redis;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.state.model.ConversationStatus;
import ai.newwave.agent.state.spi.ConversationStateManager;
import ai.newwave.agent.util.Json;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Redis-backed conversation state manager.
 * Provides distributed locking, status tracking, and message queuing.
 *
 * <p>Key schema:
 * <ul>
 *   <li>{@code agent:{agentId}:conv:{conversationId}:status} — conversation status (IDLE/RUNNING/ABORTING)</li>
 *   <li>{@code agent:{agentId}:conv:{conversationId}:lock} — distributed lock with TTL</li>
 *   <li>{@code agent:{agentId}:conv:{conversationId}:followup} — follow-up message queue (list)</li>
 *   <li>{@code agent:{agentId}:conv:{conversationId}:steer} — steering message queue (list)</li>
 * </ul>
 */
public class RedisConversationStateManager implements ConversationStateManager {

    private final ReactiveStringRedisTemplate redis;
    private final Duration lockTtl;

    public RedisConversationStateManager(ReactiveStringRedisTemplate redis) {
        this(redis, Duration.ofMinutes(5));
    }

    public RedisConversationStateManager(ReactiveStringRedisTemplate redis, Duration lockTtl) {
        this.redis = redis;
        this.lockTtl = lockTtl;
    }

    // --- Key helpers ---

    private String statusKey(String agentId, String conversationId) {
        return "agent:" + agentId + ":conv:" + conversationId + ":status";
    }

    private String lockKey(String agentId, String conversationId) {
        return "agent:" + agentId + ":conv:" + conversationId + ":lock";
    }

    private String followUpKey(String agentId, String conversationId) {
        return "agent:" + agentId + ":conv:" + conversationId + ":followup";
    }

    private String steerKey(String agentId, String conversationId) {
        return "agent:" + agentId + ":conv:" + conversationId + ":steer";
    }

    // --- Status ---

    @Override
    public Mono<ConversationStatus> getStatus(String agentId, String conversationId) {
        return redis.opsForValue().get(statusKey(agentId, conversationId))
                .map(ConversationStatus::valueOf)
                .defaultIfEmpty(ConversationStatus.IDLE);
    }

    @Override
    public Mono<Boolean> tryAcquire(String agentId, String conversationId) {
        return redis.opsForValue()
                .setIfAbsent(lockKey(agentId, conversationId), "1", lockTtl)
                .flatMap(acquired -> {
                    if (Boolean.TRUE.equals(acquired)) {
                        return redis.opsForValue()
                                .set(statusKey(agentId, conversationId), ConversationStatus.RUNNING.name())
                                .thenReturn(true);
                    }
                    return Mono.just(false);
                });
    }

    @Override
    public Mono<Void> release(String agentId, String conversationId) {
        return redis.delete(lockKey(agentId, conversationId))
                .then(redis.opsForValue().set(statusKey(agentId, conversationId), ConversationStatus.IDLE.name()))
                .then();
    }

    @Override
    public Mono<Void> requestAbort(String agentId, String conversationId) {
        return redis.opsForValue()
                .set(statusKey(agentId, conversationId), ConversationStatus.ABORTING.name())
                .then();
    }

    @Override
    public Mono<Boolean> isAborting(String agentId, String conversationId) {
        return getStatus(agentId, conversationId)
                .map(status -> status == ConversationStatus.ABORTING);
    }

    // --- Follow-up queue ---

    @Override
    public Mono<Void> enqueueFollowUp(String agentId, String conversationId, AgentMessage message) {
        return redis.opsForList()
                .rightPush(followUpKey(agentId, conversationId), serializeMessage(message))
                .then();
    }

    @Override
    public Mono<AgentMessage> dequeueFollowUp(String agentId, String conversationId) {
        return redis.opsForList()
                .leftPop(followUpKey(agentId, conversationId))
                .map(this::deserializeMessage);
    }

    @Override
    public Mono<Boolean> hasFollowUps(String agentId, String conversationId) {
        return redis.opsForList()
                .size(followUpKey(agentId, conversationId))
                .map(size -> size != null && size > 0)
                .defaultIfEmpty(false);
    }

    // --- Steering queue ---

    @Override
    public Mono<Void> enqueueSteer(String agentId, String conversationId, AgentMessage message) {
        return redis.opsForList()
                .rightPush(steerKey(agentId, conversationId), serializeMessage(message))
                .then();
    }

    @Override
    public Flux<AgentMessage> drainSteering(String agentId, String conversationId) {
        String key = steerKey(agentId, conversationId);
        return redis.opsForList().leftPop(key)
                .repeatWhenEmpty(0, flux -> Flux.empty())
                .expand(val -> redis.opsForList().leftPop(key).defaultIfEmpty(""))
                .filter(val -> !val.isEmpty())
                .map(this::deserializeMessage);
    }

    // --- Serialization ---

    private String serializeMessage(AgentMessage message) {
        try {
            return Json.MAPPER.writeValueAsString(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize AgentMessage", e);
        }
    }

    private AgentMessage deserializeMessage(String json) {
        try {
            return Json.MAPPER.readValue(json, AgentMessage.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize AgentMessage", e);
        }
    }
}
