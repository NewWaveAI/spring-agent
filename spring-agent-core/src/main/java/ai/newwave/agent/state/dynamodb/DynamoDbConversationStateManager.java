package ai.newwave.agent.state.dynamodb;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.state.model.ConversationStatus;
import ai.newwave.agent.state.spi.ConversationStateManager;
import ai.newwave.agent.util.Json;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * DynamoDB-backed conversation state manager.
 * Provides distributed locking, status tracking, and message queuing.
 *
 * <p>Required table schema:
 * <pre>
 * Table: agent_conversation_state (configurable)
 * PK: pk (S) — "agent:{agentId}:conv:{conversationId}"
 * SK: sk (S) — "status" | "followup:{seq}" | "steer:{seq}"
 * Attributes:
 *   - status (S): IDLE/RUNNING/ABORTING (for sk="status")
 *   - lockExpiry (N): epoch seconds (for sk="status")
 *   - message (S): serialized AgentMessage JSON (for followup/steer items)
 *   - seq (N): sequence number for ordering (for followup/steer items)
 * </pre>
 */
public class DynamoDbConversationStateManager implements ConversationStateManager {

    private final DynamoDbAsyncClient dynamoDb;
    private final String tableName;
    private final Duration lockTtl;

    public DynamoDbConversationStateManager(DynamoDbAsyncClient dynamoDb, String tableName) {
        this(dynamoDb, tableName, Duration.ofMinutes(5));
    }

    public DynamoDbConversationStateManager(DynamoDbAsyncClient dynamoDb, String tableName, Duration lockTtl) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
        this.lockTtl = lockTtl;
    }

    private String pk(String agentId, String conversationId) {
        return "agent:" + agentId + ":conv:" + conversationId;
    }

    // --- Status ---

    @Override
    public Mono<ConversationStatus> getStatus(String agentId, String conversationId) {
        return Mono.fromFuture(() -> dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "pk", AttributeValue.fromS(pk(agentId, conversationId)),
                                "sk", AttributeValue.fromS("status")))
                        .build()))
                .map(resp -> {
                    if (!resp.hasItem() || !resp.item().containsKey("status")) {
                        return ConversationStatus.IDLE;
                    }
                    return ConversationStatus.valueOf(resp.item().get("status").s());
                })
                .defaultIfEmpty(ConversationStatus.IDLE);
    }

    @Override
    public Mono<Boolean> tryAcquire(String agentId, String conversationId) {
        long expiry = Instant.now().plus(lockTtl).getEpochSecond();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(pk(agentId, conversationId)));
        item.put("sk", AttributeValue.fromS("status"));
        item.put("status", AttributeValue.fromS(ConversationStatus.RUNNING.name()));
        item.put("lockExpiry", AttributeValue.fromN(String.valueOf(expiry)));

        // Conditional put: only if not exists or status is IDLE or lock is expired
        return Mono.fromFuture(() -> dynamoDb.putItem(PutItemRequest.builder()
                        .tableName(tableName)
                        .item(item)
                        .conditionExpression(
                                "attribute_not_exists(pk) OR #s = :idle OR #le < :now")
                        .expressionAttributeNames(Map.of("#s", "status", "#le", "lockExpiry"))
                        .expressionAttributeValues(Map.of(
                                ":idle", AttributeValue.fromS(ConversationStatus.IDLE.name()),
                                ":now", AttributeValue.fromN(String.valueOf(Instant.now().getEpochSecond()))))
                        .build()))
                .thenReturn(true)
                .onErrorResume(ConditionalCheckFailedException.class, e -> Mono.just(false));
    }

    @Override
    public Mono<Void> release(String agentId, String conversationId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(pk(agentId, conversationId)));
        item.put("sk", AttributeValue.fromS("status"));
        item.put("status", AttributeValue.fromS(ConversationStatus.IDLE.name()));
        item.put("lockExpiry", AttributeValue.fromN("0"));

        return Mono.fromFuture(() -> dynamoDb.putItem(PutItemRequest.builder()
                        .tableName(tableName)
                        .item(item)
                        .build()))
                .then();
    }

    @Override
    public Mono<Void> requestAbort(String agentId, String conversationId) {
        return Mono.fromFuture(() -> dynamoDb.updateItem(UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "pk", AttributeValue.fromS(pk(agentId, conversationId)),
                                "sk", AttributeValue.fromS("status")))
                        .updateExpression("SET #s = :aborting")
                        .expressionAttributeNames(Map.of("#s", "status"))
                        .expressionAttributeValues(Map.of(
                                ":aborting", AttributeValue.fromS(ConversationStatus.ABORTING.name())))
                        .build()))
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
        return enqueueMessage(agentId, conversationId, "followup", message);
    }

    @Override
    public Mono<AgentMessage> dequeueFollowUp(String agentId, String conversationId) {
        return dequeueMessage(agentId, conversationId, "followup");
    }

    @Override
    public Mono<Boolean> hasFollowUps(String agentId, String conversationId) {
        return hasMessages(agentId, conversationId, "followup");
    }

    // --- Steering queue ---

    @Override
    public Mono<Void> enqueueSteer(String agentId, String conversationId, AgentMessage message) {
        return enqueueMessage(agentId, conversationId, "steer", message);
    }

    @Override
    public Flux<AgentMessage> drainSteering(String agentId, String conversationId) {
        return dequeueMessage(agentId, conversationId, "steer")
                .expand(msg -> dequeueMessage(agentId, conversationId, "steer"));
    }

    // --- Queue helpers ---

    private Mono<Void> enqueueMessage(String agentId, String conversationId, String queueType, AgentMessage message) {
        String sk = queueType + ":" + Instant.now().toEpochMilli() + ":" + message.id();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(pk(agentId, conversationId)));
        item.put("sk", AttributeValue.fromS(sk));
        item.put("message", AttributeValue.fromS(serializeMessage(message)));

        return Mono.fromFuture(() -> dynamoDb.putItem(PutItemRequest.builder()
                        .tableName(tableName)
                        .item(item)
                        .build()))
                .then();
    }

    private Mono<AgentMessage> dequeueMessage(String agentId, String conversationId, String queueType) {
        String pkVal = pk(agentId, conversationId);
        // Query for the first item with the queue prefix
        return Mono.fromFuture(() -> dynamoDb.query(QueryRequest.builder()
                        .tableName(tableName)
                        .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValue.fromS(pkVal),
                                ":prefix", AttributeValue.fromS(queueType + ":")))
                        .limit(1)
                        .build()))
                .flatMap(resp -> {
                    if (!resp.hasItems() || resp.items().isEmpty()) {
                        return Mono.empty();
                    }
                    Map<String, AttributeValue> item = resp.items().getFirst();
                    String messageJson = item.get("message").s();
                    // Delete the item atomically
                    return Mono.fromFuture(() -> dynamoDb.deleteItem(DeleteItemRequest.builder()
                                    .tableName(tableName)
                                    .key(Map.of(
                                            "pk", item.get("pk"),
                                            "sk", item.get("sk")))
                                    .build()))
                            .thenReturn(deserializeMessage(messageJson));
                });
    }

    private Mono<Boolean> hasMessages(String agentId, String conversationId, String queueType) {
        return Mono.fromFuture(() -> dynamoDb.query(QueryRequest.builder()
                        .tableName(tableName)
                        .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValue.fromS(pk(agentId, conversationId)),
                                ":prefix", AttributeValue.fromS(queueType + ":")))
                        .limit(1)
                        .select(Select.COUNT)
                        .build()))
                .map(resp -> resp.count() > 0)
                .defaultIfEmpty(false);
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
