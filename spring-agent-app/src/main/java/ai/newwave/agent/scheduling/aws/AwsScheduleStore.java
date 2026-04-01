package ai.newwave.agent.scheduling.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.newwave.agent.scheduling.model.RetryConfig;
import ai.newwave.agent.scheduling.model.ScheduleType;
import ai.newwave.agent.scheduling.model.ScheduledEvent;
import ai.newwave.agent.scheduling.spi.ScheduleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * DynamoDB-backed schedule store with conditional writes for distributed locking.
 * Table name is configurable. Expected schema:
 *
 * <pre>
 * PK: id (S)
 * Attributes: type, scheduleExpression, timezone, payload (JSON), retryConfig (JSON),
 *             createdAt, nextFireTime, enabled, lockOwner, lockExpiry
 * GSI: enabled-nextFireTime-index (enabled=PK, nextFireTime=SK) for findDueEvents
 * </pre>
 */
public class AwsScheduleStore implements ScheduleStore {

    private static final Logger log = LoggerFactory.getLogger(AwsScheduleStore.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public AwsScheduleStore(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    @Override
    public Mono<ScheduledEvent> save(ScheduledEvent event) {
        return Mono.fromCallable(() -> {
            Map<String, AttributeValue> item = toItem(event);
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());
            return event;
        });
    }

    @Override
    public Mono<ScheduledEvent> findById(String id) {
        return Mono.fromCallable(() -> {
            GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("id", AttributeValue.fromS(id)))
                    .build());
            if (!response.hasItem() || response.item().isEmpty()) return null;
            return fromItem(response.item());
        });
    }

    @Override
    public Flux<ScheduledEvent> findByType(ScheduleType type) {
        return Flux.defer(() -> {
            ScanResponse response = dynamoDb.scan(ScanRequest.builder()
                    .tableName(tableName)
                    .filterExpression("#t = :type")
                    .expressionAttributeNames(Map.of("#t", "type"))
                    .expressionAttributeValues(Map.of(":type", AttributeValue.fromS(type.name())))
                    .build());
            return Flux.fromIterable(response.items()).map(this::fromItem);
        });
    }

    @Override
    public Flux<ScheduledEvent> findDueEvents(Instant before) {
        return Flux.defer(() -> {
            // Query the GSI for enabled events with nextFireTime <= before
            QueryResponse response = dynamoDb.query(QueryRequest.builder()
                    .tableName(tableName)
                    .indexName("enabled-nextFireTime-index")
                    .keyConditionExpression("enabled = :enabled AND nextFireTime <= :before")
                    .expressionAttributeValues(Map.of(
                            ":enabled", AttributeValue.fromS("true"),
                            ":before", AttributeValue.fromS(before.toString())))
                    .build());
            return Flux.fromIterable(response.items()).map(this::fromItem);
        });
    }

    @Override
    public Mono<Void> delete(String id) {
        return Mono.fromRunnable(() ->
                dynamoDb.deleteItem(DeleteItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("id", AttributeValue.fromS(id)))
                        .build()));
    }

    @Override
    public Mono<ScheduledEvent> updateNextFireTime(String id, Instant nextFireTime) {
        return Mono.fromCallable(() -> {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("id", AttributeValue.fromS(id)))
                    .updateExpression("SET nextFireTime = :nft")
                    .expressionAttributeValues(Map.of(":nft", AttributeValue.fromS(nextFireTime.toString())))
                    .build());
            return findById(id).block();
        });
    }

    @Override
    public Mono<Boolean> tryAcquireLock(String eventId, String instanceId, Duration ttl) {
        return Mono.fromCallable(() -> {
            Instant expiry = Instant.now().plus(ttl);
            try {
                dynamoDb.updateItem(UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("id", AttributeValue.fromS(eventId)))
                        .updateExpression("SET lockOwner = :owner, lockExpiry = :expiry")
                        .conditionExpression("attribute_not_exists(lockOwner) OR lockExpiry < :now")
                        .expressionAttributeValues(Map.of(
                                ":owner", AttributeValue.fromS(instanceId),
                                ":expiry", AttributeValue.fromS(expiry.toString()),
                                ":now", AttributeValue.fromS(Instant.now().toString())))
                        .build());
                return true;
            } catch (ConditionalCheckFailedException e) {
                return false;
            }
        });
    }

    @Override
    public Mono<Void> releaseLock(String eventId, String instanceId) {
        return Mono.fromRunnable(() -> {
            try {
                dynamoDb.updateItem(UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("id", AttributeValue.fromS(eventId)))
                        .updateExpression("REMOVE lockOwner, lockExpiry")
                        .conditionExpression("lockOwner = :owner")
                        .expressionAttributeValues(Map.of(":owner", AttributeValue.fromS(instanceId)))
                        .build());
            } catch (ConditionalCheckFailedException e) {
                log.warn("Failed to release lock for event {} - lock owned by another instance", eventId);
            }
        });
    }

    // --- Serialization ---

    private Map<String, AttributeValue> toItem(ScheduledEvent event) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", AttributeValue.fromS(event.id()));
        item.put("type", AttributeValue.fromS(event.type().name()));
        if (event.scheduleExpression() != null)
            item.put("scheduleExpression", AttributeValue.fromS(event.scheduleExpression()));
        item.put("timezone", AttributeValue.fromS(event.timezone()));
        try {
            item.put("payload", AttributeValue.fromS(objectMapper.writeValueAsString(event.payload())));
            item.put("retryConfig", AttributeValue.fromS(objectMapper.writeValueAsString(event.retryConfig())));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize schedule event", e);
        }
        item.put("createdAt", AttributeValue.fromS(event.createdAt().toString()));
        if (event.nextFireTime() != null)
            item.put("nextFireTime", AttributeValue.fromS(event.nextFireTime().toString()));
        item.put("enabled", AttributeValue.fromS(String.valueOf(event.enabled())));
        return item;
    }

    private ScheduledEvent fromItem(Map<String, AttributeValue> item) {
        try {
            return ScheduledEvent.builder()
                    .id(item.get("id").s())
                    .type(ScheduleType.valueOf(item.get("type").s()))
                    .scheduleExpression(item.containsKey("scheduleExpression") ? item.get("scheduleExpression").s() : null)
                    .timezone(item.get("timezone").s())
                    .payload(objectMapper.readValue(item.get("payload").s(),
                            ai.newwave.agent.scheduling.model.SchedulePayload.class))
                    .retryConfig(objectMapper.readValue(item.get("retryConfig").s(), RetryConfig.class))
                    .createdAt(Instant.parse(item.get("createdAt").s()))
                    .nextFireTime(item.containsKey("nextFireTime") ? Instant.parse(item.get("nextFireTime").s()) : null)
                    .enabled(Boolean.parseBoolean(item.get("enabled").s()))
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize schedule event", e);
        }
    }
}
