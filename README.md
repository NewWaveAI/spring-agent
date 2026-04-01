# spring-agent

[![Maven Central](https://img.shields.io/maven-central/v/ai.new-wave/spring-agent-core)](https://central.sonatype.com/artifact/ai.new-wave/spring-agent-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

A stateful AI agent framework for Java, built on [Spring Boot 4](https://spring.io/projects/spring-boot) and [Spring AI 2](https://spring.io/projects/spring-ai).

## Modules

| Module | Description |
|--------|-------------|
| `spring-agent-core` | Agent engine, tools, events, hooks, compaction, SSE proxy |
| `spring-agent-app` | Activity timeline, agent memory, event scheduling |

## Requirements

- Java 21+
- Spring Boot 4.0+
- Spring AI 2.0+

---

# spring-agent-core

The core agent engine. Provides multi-turn conversations with streaming responses, tool execution, lifecycle events, hooks, conversation compaction, and an optional SSE proxy endpoint.

```xml
<dependency>
    <groupId>ai.new-wave</groupId>
    <artifactId>spring-agent-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

You also need the Spring AI milestone repository:

```xml
<repositories>
    <repository>
        <id>spring-milestones</id>
        <url>https://repo.spring.io/milestone</url>
    </repository>
</repositories>
```

## Quick Start

Configure in `application.yml`:

```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-5-20250514
          max-tokens: 8192

agent:
  system-prompt: "You are a helpful assistant."
  thinking-level: off
  tool-execution-mode: parallel
  max-turns: 25
```

Inject the auto-configured `Agent` bean:

```java
@Service
public class ChatService {
    private final Agent agent;

    public ChatService(Agent agent) {
        this.agent = agent;
    }

    public void chat(String userMessage) {
        agent.subscribe(event -> {
            if (event instanceof AgentEvent.MessageUpdate update) {
                System.out.print(update.delta());
            }
        });

        agent.prompt(userMessage).block();
    }
}
```

## Configuration

Prefix: `agent.*`

| Property | Default | Description |
|----------|---------|-------------|
| `id` | `"default"` | Agent identifier |
| `system-prompt` | `"You are a helpful assistant."` | System prompt (can be a resource path like `classpath:prompt.txt`) |
| `thinking-level` | `off` | Extended thinking: `off`, `low`, `medium`, `high`, `xhigh` |
| `tool-execution-mode` | `parallel` | `parallel` or `sequential` |
| `max-turns` | `25` | Max LLM turns per run |
| `max-tokens` | `8192` | Max output tokens per LLM call |

### Thinking Levels

| Level | Budget Tokens | Max Completion Tokens |
|-------|--------------|----------------------|
| `off` | 0 | 0 |
| `low` | 1,024 | 4,096 |
| `medium` | 4,096 | 8,192 |
| `high` | 16,384 | 32,768 |
| `xhigh` | 65,536 | 131,072 |

## Agent API

The `Agent` class is the main entry point. All methods have an overload that accepts a `channelId` for multi-channel support, and a default-channel variant.

### Core Operations

```java
// Start a new conversation turn
agent.prompt("Hello").block();
agent.prompt("Hello", "channel-1").block();

// Resume from current transcript
agent.continueConversation().block();

// Inject a message mid-turn (picked up at next loop iteration)
agent.steer("Also check the error logs");

// Queue a message for after the current run completes
agent.followUp("Summarize what you found");
```

### Event Subscription

```java
// Callback-based
Disposable sub = agent.subscribe(event -> { /* handle */ });

// Reactive stream
agent.events()
    .filter(e -> e instanceof AgentEvent.MessageUpdate)
    .subscribe(e -> System.out.print(((AgentEvent.MessageUpdate) e).delta()));

// Per-channel stream
agent.events("channel-1").subscribe(/* ... */);
```

### Control

```java
agent.abort();                     // Cancel the current run
agent.waitForIdle().block();       // Block until agent finishes
agent.reset();                     // Clear all state
```

### Channel Management

```java
List<String> channels = agent.listChannels();
List<AgentMessage> msgs = agent.getMessages("channel-1");
AgentStatus status = agent.getStatus("channel-1");
agent.deleteChannel("channel-1");
```

## Tools

Implement `AgentTool<P, D>` and annotate with `@Component` for auto-discovery. `P` is the parameter type, `D` is the result detail type.

```java
@Component
public class WeatherTool implements AgentTool<WeatherTool.Params, String> {

    public record Params(String location) {}

    @Override public String name() { return "get_weather"; }
    @Override public String label() { return "Get Weather"; }
    @Override public String description() { return "Get current weather for a location"; }
    @Override public Class<Params> parameterType() { return Params.class; }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties")
              .putObject("location").put("type", "string");
        schema.putArray("required").add("location");
        return schema;
    }

    @Override
    public Mono<AgentToolResult<String>> execute(ToolCallContext<Params> ctx) {
        String weather = fetchWeather(ctx.parameters().location());
        return Mono.just(AgentToolResult.success(weather, weather));
    }
}
```

### AgentToolResult Factory Methods

```java
AgentToolResult.success("result text")            // Text-only success
AgentToolResult.success("result text", details)    // Success with typed details
AgentToolResult.error("something went wrong")      // Error result
AgentToolResult.of(List.of(contentBlocks))          // Custom content blocks
```

## Events

All agent lifecycle events are modeled as a sealed interface `AgentEvent`. Every event includes `timestamp()`, `type()`, `agentId()`, and `channelId()`.

| Event | Key Fields | Description |
|-------|-----------|-------------|
| `AgentStart` | | Agent execution began |
| `AgentEnd` | `error` | Agent completed (null error = success) |
| `TurnStart` | `turnNumber` | LLM turn began |
| `TurnEnd` | `turnNumber` | LLM turn ended |
| `MessageStart` | `message` | Assistant message began |
| `MessageUpdate` | `delta` | Streaming text chunk |
| `MessageEnd` | `message` | Assistant message completed |
| `ToolExecutionStart` | `toolUse` | Tool invocation began |
| `ToolExecutionUpdate` | `toolUse`, `update` | Tool progress update |
| `ToolExecutionEnd` | `toolUse`, `result` | Tool invocation completed |
| `ScheduleFired` | `scheduleId`, `scheduleType`, `metadata` | Scheduled event fired |

```java
agent.subscribe(event -> switch (event) {
    case AgentEvent.AgentStart e       -> log.info("Started");
    case AgentEvent.MessageUpdate e    -> System.out.print(e.delta());
    case AgentEvent.ToolExecutionEnd e -> log.info("Tool {} done", e.toolUse().name());
    case AgentEvent.AgentEnd e         -> log.info("Done (error={})", e.error());
    default -> {}
});
```

## Hooks

Customize agent behavior by implementing `AgentHooks` and registering as a `@Component`. Multiple hooks are automatically composed via `CompositeAgentHooks` (first registered = first executed).

```java
@Component
public class MyHooks implements AgentHooks {

    @Override
    public Mono<BeforeToolCallResult> beforeToolCall(String toolName, ContentBlock.ToolUse toolUse) {
        // Block or allow tool execution
        if (toolName.equals("dangerous_tool")) {
            return Mono.just(BeforeToolCallResult.block("Requires approval"));
        }
        return Mono.just(BeforeToolCallResult.allow());
    }

    @Override
    public Mono<AgentToolResult<?>> afterToolCall(String toolName, ContentBlock.ToolUse toolUse, AgentToolResult<?> result) {
        // Modify or replace tool results
        return Mono.just(result);
    }

    @Override
    public List<AgentMessage> transformContext(List<AgentMessage> messages) {
        // Modify messages before each LLM call (e.g., inject context, prune)
        return messages;
    }

    @Override
    public List<AgentMessage> convertToLlm(List<AgentMessage> messages) {
        // Convert messages to LLM-compatible format
        return messages;
    }
}
```

## Message Model

### AgentMessage

```java
AgentMessage.user("Hello")                                          // User message
AgentMessage.assistant(List.of(new ContentBlock.Text("Hi")))       // Assistant message
AgentMessage.toolResult(toolUseId, List.of(blocks), false)         // Tool result
```

### ContentBlock (Sealed Interface)

| Type | Fields | Description |
|------|--------|-------------|
| `Text` | `text` | Plain text |
| `ToolUse` | `id`, `name`, `input` (JsonNode) | Tool invocation |
| `ToolResult` | `toolUseId`, `content`, `isError` | Tool response |
| `Thinking` | `thinking` | Extended thinking block |

## Conversation Compaction

Automatically summarizes old messages when context grows too large, using the LLM itself. Enabled via configuration.

```yaml
agent:
  compaction:
    enabled: true
    max-context-tokens: 100000
    preserve-recent-count: 10
    max-summary-tokens: 2000
    preserve-tool-results: true
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Enable compaction |
| `max-context-tokens` | `100000` | Trigger compaction above this token estimate |
| `preserve-recent-count` | `10` | Keep last N messages intact |
| `max-summary-tokens` | `2000` | Max tokens for the summary |
| `preserve-tool-results` | `true` | Include tool results in the summary |

**How it works:** Before each LLM call, `CompactionHook` estimates the token count. If it exceeds `max-context-tokens`, older messages are sent to the LLM for summarization. The summary replaces them as a `[Conversation Summary]` user message. Recent messages are kept intact.

### Customization

Override the default token estimator or compaction strategy by providing your own bean:

```java
// Custom token estimator (default: text.length() / 4)
@Bean
public TokenEstimator tokenEstimator() {
    return new MyTikTokenEstimator();
}

// Custom compaction strategy (default: LLM-based summarization)
@Bean
public CompactionStrategy compactionStrategy() {
    return new MyCustomStrategy();
}
```

## Conversation Persistence

By default, messages are stored in memory (`InMemoryConversationStore`). For production, provide a `ConversationStore` bean:

```java
@Bean
public ConversationStore conversationStore(JdbcTemplate jdbc) {
    return new MyJdbcConversationStore(jdbc);
}
```

The `ConversationStore` interface:

```java
public interface ConversationStore {
    Mono<Void> appendMessage(String channelId, AgentMessage message);
    Flux<AgentMessage> loadMessages(String channelId);
    Mono<Void> replaceMessages(String channelId, List<AgentMessage> messages);
    Mono<Void> deleteChannel(String channelId);
    Flux<String> listChannelIds();
}
```

## SSE Proxy

Opt-in HTTP endpoint that streams agent events as Server-Sent Events for web clients.

```yaml
agent:
  proxy:
    enabled: true
```

```
POST /api/stream
Content-Type: application/json

{"message": "Hello, agent!"}
```

Returns `text/event-stream`:

```
event: message_update
data: {"type":"message_update","timestamp":"...","delta":"Hello"}

event: agent_end
data: {"type":"agent_end","timestamp":"...","error":null}
```

## Auto-Configuration

`spring-agent-core` registers the following beans via `AgentAutoConfiguration` (all use `@ConditionalOnMissingBean`):

| Bean | Default |
|------|---------|
| `AgentHooks` | Composite of all registered hooks |
| `ConversationStore` | `InMemoryConversationStore` |
| `ChannelManager` | Default (backed by conversation store) |
| `AgentConfig` | Built from `AgentProperties` + discovered tools + hooks |
| `Agent` | Main agent instance |

Compaction beans (when `agent.compaction.enabled=true`):

| Bean | Default |
|------|---------|
| `TokenEstimator` | `SimpleTokenEstimator` (length / 4) |
| `CompactionConfig` | Built from `CompactionProperties` |
| `CompactionStrategy` | `LlmCompactionStrategy` |
| `CompactionHook` | Registered as `AgentHooks` |

---

# spring-agent-app

Optional application-layer features: activity timeline, agent memory, and event scheduling. Requires `spring-agent-core`.

```xml
<dependency>
    <groupId>ai.new-wave</groupId>
    <artifactId>spring-agent-app</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Activity Timeline

Persistent "what happened" feed, automatically recorded from agent events. The agent can reference recent activity for situational awareness and query the timeline via a built-in tool.

```yaml
agent:
  timeline:
    enabled: true
    max-store-size: 10000
    max-recent-events-for-context: 20
    context-injection-enabled: true
    query-tool-enabled: true
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Enable timeline |
| `max-store-size` | `10000` | Max events in the in-memory store |
| `max-recent-events-for-context` | `20` | Number of events injected into LLM context |
| `context-injection-enabled` | `true` | Auto-prepend recent timeline to LLM context |
| `query-tool-enabled` | `true` | Register `query_timeline` tool for the agent |

### What Gets Recorded

`TimelineRecorder` automatically subscribes to agent events and converts them to timeline entries:

| Agent Event | Timeline Event Type | Example Summary |
|-------------|-------------------|-----------------|
| `AgentStart` | `agent_started` | "Agent started" |
| `AgentEnd` | `agent_ended` | "Agent completed" / "Agent ended with error: ..." |
| `ToolExecutionStart` | `tool_execution_started` | "Started executing tool 'search'" |
| `ToolExecutionEnd` | `tool_executed` | "Executed tool 'search'" |
| `ScheduleFired` | `schedule_fired` | "Schedule 'daily-report' fired" |
| `TurnStart` | `turn_started` | "Turn 3 started" |
| `MessageEnd` | `message_completed` | "Assistant message completed" |

High-frequency events (`MessageUpdate`, `MessageStart`, `TurnEnd`, `ToolExecutionUpdate`) are skipped.

### Custom Events

Record your own events:

```java
@Service
public class MyService {
    private final TimelineService timelineService;

    public void onUserLogin(String userId) {
        timelineService.record(
            new TimelineActor.User(userId, "John"),
            "user_login",
            "User John logged in"
        ).subscribe();
    }
}
```

### Querying

The agent can use the built-in `query_timeline` tool to ask about recent activity. You can also query programmatically:

```java
timelineService.query(TimelineQuery.builder()
    .eventTypes(List.of("tool_executed"))
    .since(Instant.now().minus(Duration.ofHours(1)))
    .limit(10)
    .build()
).subscribe(event -> log.info("{}: {}", event.eventType(), event.summary()));
```

### Context Injection

When `context-injection-enabled: true`, recent timeline events are prepended to the LLM context as an `[Activity Timeline]` user message before each call, giving the agent passive situational awareness.

### Persistence

By default, events are stored in memory (`InMemoryTimelineStore`). For production, provide a `TimelineStore` bean:

```java
@Bean
public TimelineStore timelineStore(JdbcTemplate jdbc) {
    return new JdbcTimelineStore(jdbc);
}
```

Required table:

```sql
CREATE TABLE timeline_events (
    id VARCHAR(255) PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL,
    actor TEXT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    summary TEXT NOT NULL,
    metadata TEXT,
    agent_id VARCHAR(255),
    channel_id VARCHAR(255)
);
CREATE INDEX idx_timeline_timestamp ON timeline_events (timestamp DESC);
CREATE INDEX idx_timeline_type ON timeline_events (event_type);
CREATE INDEX idx_timeline_agent ON timeline_events (agent_id);
```

## Agent Memory

Durable cross-channel knowledge store. The agent can save facts and search them across all conversations using built-in tools (`save_memory`, `search_memory`).

```yaml
agent:
  memory:
    enabled: true
    context-injection-enabled: true
    save-tool-enabled: true
    search-tool-enabled: true
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Enable memory |
| `context-injection-enabled` | `true` | Auto-inject all memories into LLM context |
| `save-tool-enabled` | `true` | Register `save_memory` tool |
| `search-tool-enabled` | `true` | Register `search_memory` tool |

### How It Works

Memories are key-value entries with tags:

```java
@Service
public class MyService {
    private final MemoryService memoryService;

    public void remember() {
        // Save a memory
        memoryService.save(
            "api-rotation-schedule",
            "API keys rotate every 90 days, next rotation: 2026-07-01",
            Set.of("ops", "api")
        ).block();

        // Search by tags
        memoryService.search(Set.of("ops")).subscribe(memory ->
            log.info("{}: {}", memory.key(), memory.content())
        );

        // Get all memories
        memoryService.listAll().subscribe(/* ... */);

        // Delete
        memoryService.delete("api-rotation-schedule").block();
    }
}
```

When `context-injection-enabled: true`, all stored memories are prepended to the LLM context as an `[Agent Memory]` message.

### Built-in Tools

- **`save_memory`** — Parameters: `key` (string), `content` (string), `tags` (array of strings)
- **`search_memory`** — Parameters: `tags` (array of strings). Returns memories matching any tag, or all memories if no tags provided.

### Persistence

By default, memories are stored in memory (`InMemoryMemoryStore`). For production, provide a `MemoryStore` bean:

```java
@Bean
public MemoryStore memoryStore(JdbcTemplate jdbc) {
    return new JdbcMemoryStore(jdbc);
}
```

## Event Scheduling

Schedule agent actions (prompt, steer, follow-up) for later execution. Supports immediate, one-shot, and periodic schedules.

```yaml
agent:
  scheduling:
    enabled: true
    provider: memory  # memory | aws | database
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Enable scheduling |
| `provider` | `"memory"` | Backend: `memory`, `aws`, or `database` |

### Schedule Types

| Type | Expression | Description |
|------|-----------|-------------|
| `IMMEDIATE` | *(none)* | Fire immediately |
| `ONE_SHOT` | ISO-8601 timestamp | Fire once at a specific time |
| `PERIODIC` | Cron or ISO duration | Fire on a recurring schedule |

### Payload Types

Each schedule triggers an agent action:

```java
new SchedulePayload.PromptAction("default", "Generate the daily report")
new SchedulePayload.SteerAction("default", "Also check the error logs")
new SchedulePayload.FollowUpAction("default", "Summarize what you found")
new SchedulePayload.CustomAction("webhook", Map.of("url", "https://..."))
```

### Usage

```java
@Service
public class ReminderService {
    private final ScheduleService scheduleService;

    // One-shot: fire at a specific time
    public void scheduleReminder(String message, Instant when) {
        scheduleService.create(ScheduledEvent.builder()
            .type(ScheduleType.ONE_SHOT)
            .scheduleExpression(when.toString())
            .payload(new SchedulePayload.PromptAction("default", message))
            .build()
        ).block();
    }

    // Periodic: fire every 5 minutes
    public void schedulePeriodicCheck() {
        scheduleService.create(ScheduledEvent.builder()
            .type(ScheduleType.PERIODIC)
            .scheduleExpression("PT5M")
            .payload(new SchedulePayload.PromptAction("default", "Check system health"))
            .build()
        ).block();
    }

    // Cancel
    public void cancel(String scheduleId) {
        scheduleService.cancel(scheduleId).block();
    }

    // List active
    public void listActive() {
        scheduleService.listActive().subscribe(e ->
            log.info("{}: {} (next: {})", e.id(), e.type(), e.nextFireTime())
        );
    }
}
```

### Built-in Tool

The `query_schedules` tool lets the agent list, get, and cancel schedules. Actions: `list`, `get`, `cancel`.

### Backend: In-Memory (dev/test)

```yaml
agent:
  scheduling:
    provider: memory
```

Uses `ScheduledExecutorService`. Data lost on restart. Single instance only.

### Backend: AWS (production)

Uses EventBridge Scheduler for timing, SQS for delivery, DynamoDB for persistence and distributed locking. Safe for multi-instance deployments.

```yaml
agent:
  scheduling:
    provider: aws
    aws:
      sqs-target-arn: arn:aws:sqs:us-east-1:123456789:spring-agent-schedules
      sqs-queue-url: https://sqs.us-east-1.amazonaws.com/123456789/spring-agent-schedules
      role-arn: arn:aws:iam::123456789:role/eventbridge-sqs-role
      schedule-group: spring-agent
      dynamodb-table: spring-agent-schedules
      lock-ttl: PT30S
      poll-interval: PT5S
```

| Property | Default | Required | Description |
|----------|---------|----------|-------------|
| `sqs-target-arn` | | Yes | SQS queue ARN for EventBridge target |
| `sqs-queue-url` | | Yes | SQS queue URL for polling |
| `role-arn` | | Yes | IAM role for EventBridge -> SQS |
| `schedule-group` | `"spring-agent"` | No | EventBridge schedule group name |
| `dynamodb-table` | `"spring-agent-schedules"` | No | DynamoDB table name |
| `lock-ttl` | `PT30S` | No | Distributed lock TTL |
| `poll-interval` | `PT5S` | No | SQS polling interval |

**Required AWS resources:**

1. **SQS Queue** — receives messages from EventBridge when schedules fire
2. **DynamoDB Table** — persists schedule metadata and distributed locks (partition key: `id`, GSI: `enabled-nextFireTime-index`)
3. **IAM Role** — allows EventBridge Scheduler to send messages to SQS
4. **EventBridge Schedule Group** — groups all spring-agent schedules

**Required dependencies** in your app's `pom.xml`:

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>scheduler</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>sqs</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>dynamodb</artifactId>
</dependency>
```

### Backend: Database (JDBC)

For setups with a shared database but no AWS. Provide a `JdbcScheduleStore` bean.

Required table:

```sql
CREATE TABLE scheduled_events (
    id VARCHAR(255) PRIMARY KEY,
    type VARCHAR(50) NOT NULL,
    schedule_expression VARCHAR(255),
    timezone VARCHAR(100) DEFAULT 'UTC',
    payload TEXT NOT NULL,
    retry_config TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    next_fire_time TIMESTAMP,
    enabled BOOLEAN DEFAULT TRUE,
    lock_owner VARCHAR(255),
    lock_expiry TIMESTAMP
);
CREATE INDEX idx_due_events ON scheduled_events (enabled, next_fire_time);
```

Distributed safety: `tryAcquireLock` uses `UPDATE ... WHERE lock_owner IS NULL OR lock_expiry < NOW()` to claim events atomically.

---

# Extension Points

All SPIs use `@ConditionalOnMissingBean` — provide your own bean to override the default.

### spring-agent-core

| SPI | Purpose | Default |
|-----|---------|---------|
| `ConversationStore` | Message persistence | `InMemoryConversationStore` |
| `AgentHooks` | Lifecycle hooks | No-op (composite) |
| `TokenEstimator` | Token counting for compaction | `SimpleTokenEstimator` (length / 4) |
| `CompactionStrategy` | Context summarization | `LlmCompactionStrategy` |

### spring-agent-app

| SPI | Purpose | Default |
|-----|---------|---------|
| `TimelineStore` | Timeline event persistence | `InMemoryTimelineStore` |
| `MemoryStore` | Agent memory persistence | `InMemoryMemoryStore` |
| `ScheduleStore` | Schedule persistence + distributed locking | `InMemoryScheduleStore` |
| `ScheduleExecutor` | Schedule execution engine | `InMemoryScheduleExecutor` |

---

# Building

```bash
./mvnw clean compile
./mvnw test
./mvnw install
```

# License

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
