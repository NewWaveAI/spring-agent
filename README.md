# spring-agent

[![Maven Central](https://img.shields.io/maven-central/v/ai.new-wave/spring-agent-core)](https://central.sonatype.com/artifact/ai.new-wave/spring-agent-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

A stateless AI agent client for Java, built on [Spring Boot 4](https://spring.io/projects/spring-boot) and [Spring AI 2](https://spring.io/projects/spring-ai). Designed for multi-tenant deployments behind a load balancer.

## Modules

| Module | Description |
|--------|-------------|
| `spring-agent-core` | Agent client, tools, events, hooks, compaction, SSE proxy |
| `spring-agent-app` | Activity timeline, agent memory, event scheduling, JDBC/AWS stores |

## Requirements

- Java 21+
- Spring Boot 4.0+
- Spring AI 2.0+
- PostgreSQL (for conversation/timeline/memory persistence)

---

# spring-agent-core

```xml
<dependency>
    <groupId>ai.new-wave</groupId>
    <artifactId>spring-agent-core</artifactId>
    <version>0.2.2</version>
</dependency>
```

You also need the Spring AI milestone repository (until Spring AI 2.0 GA):

```xml
<repositories>
    <repository>
        <id>spring-milestones</id>
        <url>https://repo.spring.io/milestone</url>
    </repository>
</repositories>
```

## Quick Start

### 1. Configure `application.yml`

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

### 2. Provide a `ConversationStore` bean

The agent needs a store for conversation persistence. Use the provided `JdbcConversationStore` for PostgreSQL (in `spring-agent-app`), or implement your own:

```java
@Bean
public ConversationStore conversationStore(JdbcTemplate jdbc) {
    return new JdbcConversationStore(jdbc);
}
```

### 3. Build a controller

```java
@RestController
public class ChatController {
    private final Agent agent;

    public ChatController(Agent agent) {
        this.agent = agent;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> chat(
            Authentication auth,
            @RequestBody ChatRequest request
    ) {
        return agent.stream(AgentRequest.builder()
                        .agentId(auth.getUserId())
                        .conversationId(request.conversationId())
                        .message(request.message())
                        .attribute("workspaceId", request.workspaceId())
                        .build())
                .map(e -> ServerSentEvent.<AgentEvent>builder()
                        .event(e.type().value())
                        .data(e)
                        .build());
    }
}
```

That's it. The `Agent` is auto-configured as a Spring bean. Each `stream()` call is stateless and self-contained — it loads conversation history from the store, runs the agent loop (LLM + tools), streams events, and persists new messages. Any instance behind a load balancer can handle any request.

---

## Core Concepts

### Agent ID and Conversations

The **agent ID** identifies a user or tenant. Each agentId gets its own isolated set of conversations. Typically the user's UUID from your auth system.

A **conversation** is an isolated chat thread under an agentId. Each conversation has its own message history persisted in the `ConversationStore`. The hierarchy is **agentId > conversationId**.

Both should be **UUIDs**. The only exception is `"default"` — the reserved conversationId used when none is specified.

```java
String userId = auth.getUserId();  // UUID

// Two independent conversations for the same user
agent.stream(AgentRequest.builder()
        .agentId(userId)
        .conversationId(UUID.randomUUID().toString())
        .message("Help me reset my password")
        .build()).subscribe();

agent.stream(AgentRequest.builder()
        .agentId(userId)
        .conversationId(UUID.randomUUID().toString())
        .message("I have a billing question")
        .build()).subscribe();
```

| | Agent ID | Conversation |
|--|----------|-------------|
| **What** | User/tenant identity (UUID) | A chat thread (UUID) |
| **Required** | Yes, always | No, defaults to `"default"` |
| **Cardinality** | Many per application | Many per agentId |
| **Shares** | System prompt, tools, hooks, model config | Nothing |
| **Isolates** | All conversations and message history | Message history |

---

## Agent API

### `Agent.stream(AgentRequest)` → `Flux<AgentEvent>`

The only method on the `Agent` class. Returns a reactive stream of events.

```java
Flux<AgentEvent> events = agent.stream(AgentRequest.builder()
        .agentId(userId)
        .conversationId(convId)
        .message("What's the weather in Tokyo?")
        .attribute("workspaceId", workspaceId)   // custom request context
        .build());
```

Each call:
1. Loads existing messages from `ConversationStore`
2. Appends the new user message
3. Runs the agent loop (LLM → tool calls → recurse until done)
4. Emits `AgentEvent`s as a `Flux`
5. Persists all new messages
6. Completes with `AgentEnd`

To continue a conversation, call `stream()` again with the same agentId + conversationId.

### AgentRequest

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `agentId` | `String` | Yes | — | User/tenant UUID |
| `conversationId` | `String` | No | `"default"` | Conversation UUID |
| `message` | `AgentMessage` | Yes | — | The user message |
| `attributes` | `Map<String, Object>` | No | empty | Custom request-scoped context |

```java
AgentRequest.builder()
        .agentId("user-uuid")
        .conversationId("conv-uuid")      // optional
        .message("Hello")                  // creates AgentMessage.user("Hello")
        .message(AgentMessage.user("Hi"))  // or pass a pre-built message
        .attribute("workspaceId", "ws-1")  // custom context for tools
        .attributes(Map.of("key", "val"))  // bulk add
        .build();
```

### Conversation Management

Use `ConversationStore` directly for CRUD operations:

```java
// Load messages
conversationStore.loadMessages(agentId, convId).collectList().block();

// List conversations for a user
conversationStore.listConversationIds(agentId).collectList().block();

// Delete a conversation
conversationStore.deleteConversation(agentId, convId).block();
```

---

## Tools

Tools let the agent take actions. Implement `AgentTool<P, D>` and register as a `@Component` for auto-discovery. `P` is the parameter type, `D` is the result detail type.

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

### ToolCallContext

Every tool receives a `ToolCallContext` with request-scoped data:

| Field | Type | Description |
|-------|------|-------------|
| `toolUseId` | `String` | Unique ID for this tool invocation |
| `name` | `String` | Tool name |
| `parameters` | `P` | Deserialized typed parameters |
| `agentId` | `String` | The user/tenant who triggered this call |
| `conversationId` | `String` | The conversation this call belongs to |
| `attributes` | `Map<String, Object>` | Custom attributes from `AgentRequest` |

This solves multi-tenant tool context — tools are `@Component` singletons, but each invocation carries the request context:

```java
@Override
public Mono<AgentToolResult<String>> execute(ToolCallContext<Params> ctx) {
    // Access tenant context
    String workspaceId = (String) ctx.attributes().get("workspaceId");
    String userId = ctx.agentId();

    // Scope the query to this workspace
    List<Invoice> invoices = invoiceRepo.findByWorkspace(workspaceId);
    return Mono.just(AgentToolResult.success(formatInvoices(invoices)));
}
```

### AgentToolResult

| Factory Method | Description |
|----------------|-------------|
| `AgentToolResult.success("text")` | Text-only success |
| `AgentToolResult.success("text", details)` | Success with typed details |
| `AgentToolResult.error("message")` | Error result |
| `AgentToolResult.of(List.of(blocks))` | Custom content blocks |

---

## Events

All events are emitted as a `Flux<AgentEvent>` from `agent.stream()`. Every event has `timestamp()`, `type()` (returns `AgentEventType` enum), `agentId()`, and `conversationId()`.

### AgentEventType

| Enum | Wire Value | Record | Key Fields |
|------|-----------|--------|-----------|
| `AGENT_START` | `agent_start` | `AgentStart` | |
| `AGENT_END` | `agent_end` | `AgentEnd` | `error` |
| `TURN_START` | `turn_start` | `TurnStart` | `turnNumber` |
| `TURN_END` | `turn_end` | `TurnEnd` | `turnNumber` |
| `MESSAGE_START` | `message_start` | `MessageStart` | `message` |
| `MESSAGE_UPDATE` | `message_update` | `MessageUpdate` | `delta` |
| `MESSAGE_END` | `message_end` | `MessageEnd` | `message` |
| `TOOL_EXECUTION_START` | `tool_execution_start` | `ToolExecutionStart` | `toolUse` |
| `TOOL_EXECUTION_UPDATE` | `tool_execution_update` | `ToolExecutionUpdate` | `toolUse`, `update` |
| `TOOL_EXECUTION_END` | `tool_execution_end` | `ToolExecutionEnd` | `toolUse`, `result` |
| `SCHEDULE_FIRED` | `schedule_fired` | `ScheduleFired` | `scheduleId`, `scheduleType`, `metadata` |

Use `event.type()` for enum comparison, `event.type().value()` for the wire string (SSE event names).

### Consuming Events

```java
agent.stream(request)
    .doOnNext(event -> switch (event) {
        case AgentEvent.MessageUpdate e    -> System.out.print(e.delta());
        case AgentEvent.ToolExecutionEnd e -> log.info("Tool {} done", e.toolUse().name());
        case AgentEvent.AgentEnd e         -> log.info("Done (error={})", e.error());
        default -> {}
    })
    .subscribe();
```

### Streaming as SSE to Frontend

```java
@PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<AgentEvent>> chat(@RequestBody ChatRequest req) {
    return agent.stream(AgentRequest.builder()
                    .agentId(req.agentId())
                    .message(req.message())
                    .build())
            .map(e -> ServerSentEvent.<AgentEvent>builder()
                    .event(e.type().value())
                    .data(e)
                    .build());
}
```

---

## Hooks

Customize agent behavior by implementing `AgentHooks` and registering as a `@Component`. Multiple hooks are automatically composed via `CompositeAgentHooks`.

```java
@Component
public class MyHooks implements AgentHooks {

    @Override
    public Mono<BeforeToolCallResult> beforeToolCall(String toolName, ContentBlock.ToolUse toolUse) {
        if (toolName.equals("dangerous_tool")) {
            return Mono.just(BeforeToolCallResult.block("Requires approval"));
        }
        return Mono.just(BeforeToolCallResult.allow());
    }

    @Override
    public Mono<AgentToolResult<?>> afterToolCall(String toolName, ContentBlock.ToolUse toolUse, AgentToolResult<?> result) {
        return Mono.just(result);  // modify or replace tool results
    }

    @Override
    public List<AgentMessage> transformContext(List<AgentMessage> messages) {
        return messages;  // modify messages before each LLM call
    }

    @Override
    public List<AgentMessage> convertToLlm(List<AgentMessage> messages) {
        return messages;  // convert to LLM-compatible format
    }
}
```

| Hook | When | Use Case |
|------|------|----------|
| `beforeToolCall` | Before a tool executes | Block dangerous tools, require approval |
| `afterToolCall` | After a tool executes | Modify results, add logging |
| `transformContext` | Before each LLM call | Inject context, prune messages, compact |
| `convertToLlm` | After transformContext | Format conversion for the LLM |

---

## Message Model

### AgentMessage

```java
AgentMessage.user("Hello")                                     // User text message
AgentMessage.assistant(List.of(new ContentBlock.Text("Hi")))  // Assistant response
AgentMessage.toolResult(toolUseId, List.of(blocks), false)    // Tool result
```

### ContentBlock (Sealed Interface)

| Type | Fields | Description |
|------|--------|-------------|
| `Text` | `text` | Plain text |
| `ToolUse` | `id`, `name`, `input` (JsonNode) | Tool invocation request |
| `ToolResult` | `toolUseId`, `content`, `isError` | Tool execution result |
| `Thinking` | `thinking` | Extended thinking block |

---

## Configuration

Prefix: `agent.*`

| Property | Default | Description |
|----------|---------|-------------|
| `system-prompt` | `"You are a helpful assistant."` | System prompt. Can be a resource path: `classpath:prompt.txt` |
| `thinking-level` | `off` | Extended thinking: `off`, `low`, `medium`, `high`, `xhigh` |
| `tool-execution-mode` | `parallel` | Tool execution: `parallel` or `sequential` |
| `max-turns` | `25` | Max LLM turns per `stream()` call |
| `max-tokens` | `8192` | Max output tokens per LLM call |

### Thinking Levels

| Level | Budget Tokens | Max Completion Tokens |
|-------|--------------|----------------------|
| `off` | 0 | 0 |
| `low` | 1,024 | 4,096 |
| `medium` | 4,096 | 8,192 |
| `high` | 16,384 | 32,768 |
| `xhigh` | 65,536 | 131,072 |

---

## Conversation Compaction

Automatically summarizes old messages when context grows too large.

```yaml
agent:
  compaction:
    enabled: true
    max-context-tokens: 100000
    preserve-recent-count: 10
    max-summary-tokens: 2000
    preserve-tool-results: true
```

Before each LLM call, `CompactionHook` estimates the token count. If it exceeds `max-context-tokens`, older messages are sent to the LLM for summarization. The summary replaces them as a `[Conversation Summary]` message. Recent messages are kept intact.

Override defaults:

```java
@Bean
public TokenEstimator tokenEstimator() {
    return new MyTikTokenEstimator();  // default: text.length() / 4
}

@Bean
public CompactionStrategy compactionStrategy() {
    return new MyCustomStrategy();  // default: LLM-based summarization
}
```

---

## Conversation Persistence

A `ConversationStore` bean is **required** (no default). Use `JdbcConversationStore` from `spring-agent-app`:

```java
@Bean
public ConversationStore conversationStore(JdbcTemplate jdbc) {
    return new JdbcConversationStore(jdbc);
}
```

Required table:

```sql
CREATE TABLE conversation_messages (
    id VARCHAR(255) PRIMARY KEY,
    agent_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    sequence INT NOT NULL
);
CREATE INDEX idx_conv_agent_conversation ON conversation_messages (agent_id, conversation_id, sequence);
```

Or implement the interface yourself:

```java
public interface ConversationStore {
    Mono<Void> appendMessage(String agentId, String conversationId, AgentMessage message);
    Flux<AgentMessage> loadMessages(String agentId, String conversationId);
    Mono<Void> replaceMessages(String agentId, String conversationId, List<AgentMessage> messages);
    Mono<Void> deleteConversation(String agentId, String conversationId);
    Flux<String> listConversationIds(String agentId);
}
```

---

## SSE Proxy (Optional)

Built-in HTTP endpoint for streaming. Opt-in:

```yaml
agent:
  proxy:
    enabled: true
```

```
POST /api/stream
Content-Type: application/json

{"message": "Hello!", "agentId": "550e8400-...", "conversationId": "optional-uuid"}
```

Returns `text/event-stream`:

```
event: message_update
data: {"type":"message_update","timestamp":"...","delta":"Hello"}

event: agent_end
data: {"type":"agent_end","timestamp":"...","error":null}
```

---

## Auto-Configuration

Beans registered by `AgentAutoConfiguration` (all `@ConditionalOnMissingBean`):

| Bean | Description |
|------|-------------|
| `AgentHooks` | Composite of all registered hooks |
| `AgentConfig` | Built from YAML properties + discovered tools + hooks |
| `Agent` | Stateless agent client (uses `@Qualifier("anthropicChatModel")`) |

**Required bean:** `ConversationStore` — no default provided.

Compaction beans (when `agent.compaction.enabled=true`):

| Bean | Description |
|------|-------------|
| `TokenEstimator` | `SimpleTokenEstimator` (text.length() / 4) |
| `CompactionConfig` | Built from compaction properties |
| `CompactionStrategy` | `LlmCompactionStrategy` |
| `CompactionHook` | Registered as `AgentHooks` |

---

# spring-agent-app

Optional features: activity timeline, agent memory, event scheduling, and JDBC store implementations.

```xml
<dependency>
    <groupId>ai.new-wave</groupId>
    <artifactId>spring-agent-app</artifactId>
    <version>0.2.2</version>
</dependency>
```

---

## Activity Timeline

Persistent "what happened" feed. The agent can query it for situational awareness.

```yaml
agent:
  timeline:
    enabled: true
    max-store-size: 10000
    max-recent-events-for-context: 20
    context-injection-enabled: true
    query-tool-enabled: true
```

Requires a `TimelineStore` bean:

```java
@Bean
public TimelineStore timelineStore(JdbcTemplate jdbc) {
    return new JdbcTimelineStore(jdbc);
}
```

### What Gets Recorded

`TimelineRecorder` converts agent events to timeline entries:

| Agent Event | Timeline Type | Example |
|-------------|--------------|---------|
| `AgentStart` | `agent_started` | "Agent started" |
| `AgentEnd` | `agent_ended` | "Agent completed" |
| `ToolExecutionStart` | `tool_execution_started` | "Started executing tool 'search'" |
| `ToolExecutionEnd` | `tool_executed` | "Executed tool 'search'" |
| `ScheduleFired` | `schedule_fired` | "Schedule 'daily-report' fired" |
| `TurnStart` | `turn_started` | "Turn 3 started" |
| `MessageEnd` | `message_completed` | "Assistant message completed" |

High-frequency events (`MessageUpdate`, `MessageStart`, `TurnEnd`, `ToolExecutionUpdate`) are skipped.

### Custom Events

```java
timelineService.record(
    new TimelineActor.User(userId, "John"),
    "user_login",
    "User John logged in"
).subscribe();
```

### Querying

```java
timelineService.query(TimelineQuery.builder()
    .agentId(userId)
    .eventTypes(List.of("tool_executed"))
    .since(Instant.now().minus(Duration.ofHours(1)))
    .limit(10)
    .build()
).subscribe(event -> log.info("{}: {}", event.eventType(), event.summary()));
```

### Context Injection

When `context-injection-enabled: true`, recent events are prepended to the LLM context as an `[Activity Timeline]` message, giving the agent passive situational awareness.

### Required Table

```sql
CREATE TABLE timeline_events (
    id VARCHAR(255) PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL,
    actor TEXT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    summary TEXT NOT NULL,
    metadata TEXT,
    agent_id VARCHAR(255),
    conversation_id VARCHAR(255)
);
CREATE INDEX idx_timeline_timestamp ON timeline_events (timestamp DESC);
CREATE INDEX idx_timeline_type ON timeline_events (event_type);
CREATE INDEX idx_timeline_agent ON timeline_events (agent_id);
```

---

## Agent Memory

Durable cross-conversation knowledge store. The agent can save and search facts using built-in tools.

```yaml
agent:
  memory:
    enabled: true
    context-injection-enabled: true
    save-tool-enabled: true
    search-tool-enabled: true
```

Requires a `MemoryStore` bean:

```java
@Bean
public MemoryStore memoryStore(JdbcTemplate jdbc) {
    return new JdbcMemoryStore(jdbc);
}
```

### Built-in Tools

| Tool | Parameters | Description |
|------|-----------|-------------|
| `save_memory` | `key`, `content`, `tags` | Save a fact for future reference |
| `search_memory` | `tags` | Search memories by tags |

### Programmatic Access

```java
memoryService.save("api-schedule", "Keys rotate every 90 days", Set.of("ops")).block();
memoryService.search(Set.of("ops")).subscribe(m -> log.info("{}: {}", m.key(), m.content()));
memoryService.delete("api-schedule").block();
```

When `context-injection-enabled: true`, all memories are prepended to the LLM context as an `[Agent Memory]` message.

---

## Event Scheduling

Schedule agent actions for later execution. Backed by EventBridge (AWS) or PostgreSQL.

```yaml
agent:
  scheduling:
    enabled: true
```

Requires a `ScheduleExecutor` bean — provided by `AwsSchedulingAutoConfiguration` when AWS SDK is on the classpath, or implement your own.

### Schedule Types

| Type | Expression | Description |
|------|-----------|-------------|
| `IMMEDIATE` | — | Fire now |
| `ONE_SHOT` | ISO-8601 timestamp | Fire once at a specific time |
| `PERIODIC` | Cron or ISO duration | Recurring |

### Payload Types

```java
new SchedulePayload.PromptAction(agentId, conversationId, "Generate the daily report")
new SchedulePayload.SteerAction(agentId, conversationId, "Also check error logs")
new SchedulePayload.FollowUpAction(agentId, conversationId, "Summarize findings")
new SchedulePayload.CustomAction("webhook", Map.of("url", "https://..."))
```

### Usage

```java
scheduleService.create(ScheduledEvent.builder()
    .type(ScheduleType.ONE_SHOT)
    .scheduleExpression(Instant.now().plusHours(1).toString())
    .payload(new SchedulePayload.PromptAction(agentId, "default", "Send daily summary"))
    .build()
).block();

scheduleService.cancel(scheduleId).block();
scheduleService.listActive().subscribe(e -> log.info("{}", e));
```

The `query_schedules` built-in tool lets the agent list, get, and cancel schedules.

### Backend: AWS (EventBridge)

```yaml
agent:
  scheduling:
    aws:
      sqs-target-arn: arn:aws:sqs:us-east-1:123456789:spring-agent-schedules
      sqs-queue-url: https://sqs.us-east-1.amazonaws.com/123456789/spring-agent-schedules
      role-arn: arn:aws:iam::123456789:role/eventbridge-sqs-role
      schedule-group: spring-agent
      dynamodb-table: spring-agent-schedules
      lock-ttl: PT30S
      poll-interval: PT5S
```

Required AWS resources: SQS queue, DynamoDB table (partition key `id`, GSI `enabled-nextFireTime-index`), IAM role, EventBridge schedule group.

Required dependencies:

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

### Backend: PostgreSQL (JDBC)

Provide a `JdbcScheduleStore` bean. Required table:

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

---

# Extension Points

All SPIs use `@ConditionalOnMissingBean` — provide your own bean to override.

### spring-agent-core

| SPI | Purpose | Provided Implementation |
|-----|---------|------------------------|
| `ConversationStore` | Message persistence | `JdbcConversationStore` (in app module) |
| `AgentHooks` | Lifecycle hooks | No-op composite |
| `TokenEstimator` | Token counting | `SimpleTokenEstimator` (length / 4) |
| `CompactionStrategy` | Context summarization | `LlmCompactionStrategy` |

### spring-agent-app

| SPI | Purpose | Provided Implementation |
|-----|---------|------------------------|
| `TimelineStore` | Timeline persistence | `JdbcTimelineStore` (PostgreSQL) |
| `MemoryStore` | Memory persistence | `JdbcMemoryStore` (PostgreSQL) |
| `ScheduleStore` | Schedule persistence + locking | `AwsScheduleStore` (DynamoDB) |
| `ScheduleExecutor` | Schedule execution | `AwsScheduleExecutor` (EventBridge) |

---

# Building

```bash
./mvnw clean compile
./mvnw test
./mvnw install          # install to local ~/.m2 for development
```

# License

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
