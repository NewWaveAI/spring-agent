# spring-agent

[![Maven Central](https://img.shields.io/maven-central/v/ai.new-wave/spring-agent-core)](https://central.sonatype.com/artifact/ai.new-wave/spring-agent-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

A stateless AI agent client for Java, built on [Spring Boot 4](https://spring.io/projects/spring-boot) and [Spring AI 2](https://spring.io/projects/spring-ai). Designed for multi-tenant deployments behind a load balancer.

No auto-configuration — you wire everything explicitly, like an SDK client.

## Modules

| Module | Description |
|--------|-------------|
| `spring-agent-core` | Agent client, tools, events, hooks, compaction |
| `spring-agent-app` | Activity timeline, agent memory, event scheduling, JDBC/AWS stores |

## Requirements

- Java 21+
- Spring Boot 4.0+
- Spring AI 2.0+
- PostgreSQL (for persistence)

---

# spring-agent-core

## Package Structure

```
ai.newwave.agent.core           Agent, AgentRequest, AgentLoop
ai.newwave.agent.config         AgentConfig, AgentHooks, HookContext, AgentLoopConfig, CompositeAgentHooks
ai.newwave.agent.event          AgentEvent, AgentEventType
ai.newwave.agent.tool           AgentTool, AgentToolResult, ToolCallContext, @Description
ai.newwave.agent.model          AgentMessage, ContentBlock, ThinkingLevel, MessageRole, ToolExecutionMode
ai.newwave.agent.state.spi      ConversationStore
ai.newwave.agent.compaction     CompactionHook, LlmCompactionStrategy, SimpleTokenEstimator
ai.newwave.agent.compaction.spi CompactionStrategy, TokenEstimator
```

## Installation

```xml
<dependency>
    <groupId>ai.new-wave</groupId>
    <artifactId>spring-agent-core</artifactId>
    <version>0.3.0</version>
</dependency>
```

Spring AI milestone repository (until Spring AI 2.0 GA):

```xml
<repositories>
    <repository>
        <id>spring-milestones</id>
        <url>https://repo.spring.io/milestone</url>
    </repository>
</repositories>
```

## Quick Start

### 1. Configure the Agent bean

```java
import ai.newwave.agent.core.Agent;
import ai.newwave.agent.config.AgentConfig;
import ai.newwave.agent.config.AgentLoopConfig;
import ai.newwave.agent.config.AgentHooks;
import ai.newwave.agent.config.CompositeAgentHooks;
import ai.newwave.agent.model.ThinkingLevel;
import ai.newwave.agent.model.ToolExecutionMode;
import ai.newwave.agent.state.spi.ConversationStore;
import ai.newwave.agent.state.database.JdbcConversationStore;  // from spring-agent-app

@Configuration
public class AgentConfiguration {

    @Bean
    public ConversationStore conversationStore(JdbcTemplate jdbc) {
        return new JdbcConversationStore(jdbc);
    }

    @Bean
    public Agent agent(@Qualifier("anthropicChatModel") ChatModel chatModel,
                       ConversationStore store,
                       List<AgentTool<?, ?>> tools) {
        AgentConfig config = AgentConfig.builder()
                .systemPrompt("You are a helpful assistant.")
                .thinkingLevel(ThinkingLevel.OFF)
                .maxTokens(8192)
                .tools(tools)
                .loopConfig(AgentLoopConfig.builder()
                        .maxTurns(25)
                        .toolExecutionMode(ToolExecutionMode.PARALLEL)
                        .hooks(new AgentHooks() {})  // no-op, or your hooks
                        .build())
                .build();
        return new Agent(config, chatModel, store);
    }
}
```

### 2. Build a controller

```java
import ai.newwave.agent.core.Agent;
import ai.newwave.agent.core.AgentRequest;
import ai.newwave.agent.event.AgentEvent;

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

Each `stream()` call is stateless and self-contained — it loads conversation history from the store, runs the agent loop (LLM + tools), streams events, and persists new messages. Any instance behind a load balancer can handle any request.

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
conversationStore.loadMessages(agentId, convId).collectList().block();
conversationStore.listConversationIds(agentId).collectList().block();
conversationStore.deleteConversation(agentId, convId).block();
```

---

## Tools

Implement `AgentTool<P, D>` and register as a `@Component`. `P` is the parameter type (a record), `D` is the result detail type.

The `parameterSchema()` method is **auto-generated** from the record type. Use `@Description` to add field descriptions for the LLM:

```java
import ai.newwave.agent.tool.AgentTool;
import ai.newwave.agent.tool.AgentToolResult;
import ai.newwave.agent.tool.ToolCallContext;
import ai.newwave.agent.tool.Description;

@Component
public class WeatherTool implements AgentTool<WeatherTool.Params, String> {

    public record Params(
        @Description("The city name, e.g. 'Tokyo'") String location,
        @Description("Temperature unit: 'celsius' or 'fahrenheit'") String unit
    ) {}

    @Override public String name() { return "get_weather"; }
    @Override public String label() { return "Get Weather"; }
    @Override public String description() { return "Get current weather for a location"; }
    @Override public Class<Params> parameterType() { return Params.class; }

    // parameterSchema() auto-generated from Params record + @Description annotations

    @Override
    public Mono<AgentToolResult<String>> execute(ToolCallContext<Params> ctx) {
        String weather = fetchWeather(ctx.parameters().location());
        return Mono.just(AgentToolResult.success(weather, weather));
    }
}
```

You can still override `parameterSchema()` for custom schemas.

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

Multi-tenant tool context — tools are singletons, but each invocation carries the request context:

```java
@Override
public Mono<AgentToolResult<String>> execute(ToolCallContext<Params> ctx) {
    String workspaceId = (String) ctx.attributes().get("workspaceId");
    String userId = ctx.agentId();
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

---

## Hooks

Implement `AgentHooks` to customize agent behavior. All hooks receive a `HookContext` with `agentId`, `conversationId`, and `attributes`. Use `CompositeAgentHooks` to chain multiple hooks.

```java
import ai.newwave.agent.config.AgentHooks;
import ai.newwave.agent.config.HookContext;
import ai.newwave.agent.config.CompositeAgentHooks;

public class MyHooks implements AgentHooks {

    @Override
    public Mono<BeforeToolCallResult> beforeToolCall(HookContext ctx, String toolName, ContentBlock.ToolUse toolUse) {
        if (toolName.equals("dangerous_tool")) {
            return Mono.just(BeforeToolCallResult.block("Requires approval"));
        }
        return Mono.just(BeforeToolCallResult.allow());
    }

    @Override
    public Mono<AgentToolResult<?>> afterToolCall(HookContext ctx, String toolName, ContentBlock.ToolUse toolUse, AgentToolResult<?> result) {
        return Mono.just(result);
    }

    @Override
    public List<AgentMessage> transformContext(HookContext ctx, List<AgentMessage> messages) {
        // ctx.agentId(), ctx.conversationId(), ctx.attributes() available
        return messages;
    }

    @Override
    public List<AgentMessage> convertToLlm(HookContext ctx, List<AgentMessage> messages) {
        return messages;
    }
}

// Wire into the agent
AgentConfig config = AgentConfig.builder()
        .loopConfig(AgentLoopConfig.builder()
                .hooks(new CompositeAgentHooks(List.of(myHook1, myHook2)))
                .build())
        .build();
```

| Hook | When | Use Case |
|------|------|----------|
| `beforeToolCall` | Before a tool executes | Block dangerous tools, require human approval |
| `afterToolCall` | After a tool executes | Modify results, add logging |
| `transformContext` | Before each LLM call | Inject workspace context, prune messages |
| `convertToLlm` | After transformContext | Format conversion for the LLM |

---

## AgentConfig

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `systemPrompt` | `String` | `"You are a helpful assistant."` | System prompt |
| `model` | `String` | `"claude-sonnet-4-5-20250514"` | Model identifier |
| `thinkingLevel` | `ThinkingLevel` | `OFF` | Extended thinking level |
| `maxTokens` | `int` | `8192` | Max output tokens per LLM call |
| `tools` | `List<AgentTool<?, ?>>` | empty | Available tools |
| `loopConfig` | `AgentLoopConfig` | defaults | Loop settings (maxTurns, toolExecutionMode, hooks) |
| `sessionId` | `String` | null | Optional session ID for cache-aware backends |

### AgentLoopConfig

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `maxTurns` | `int` | `25` | Max LLM turns per `stream()` call |
| `toolExecutionMode` | `ToolExecutionMode` | `PARALLEL` | `PARALLEL` or `SEQUENTIAL` |
| `hooks` | `AgentHooks` | no-op | Lifecycle hooks |

### ThinkingLevel

| Level | Budget Tokens | Max Completion Tokens |
|-------|--------------|----------------------|
| `OFF` | 0 | 0 |
| `LOW` | 1,024 | 4,096 |
| `MEDIUM` | 4,096 | 8,192 |
| `HIGH` | 16,384 | 32,768 |
| `XHIGH` | 65,536 | 131,072 |

---

## Message Model

### AgentMessage

```java
import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;

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

## Conversation Compaction

Summarizes old messages when context grows too large. Wire into the agent via hooks:

```java
import ai.newwave.agent.compaction.CompactionHook;
import ai.newwave.agent.compaction.LlmCompactionStrategy;
import ai.newwave.agent.compaction.SimpleTokenEstimator;
import ai.newwave.agent.compaction.model.CompactionConfig;

TokenEstimator estimator = new SimpleTokenEstimator();  // text.length() / 4
CompactionStrategy strategy = new LlmCompactionStrategy(chatModel, estimator);
CompactionConfig compactionConfig = CompactionConfig.builder()
        .maxContextTokens(100_000)
        .preserveRecentCount(10)
        .maxSummaryTokens(2000)
        .preserveToolResults(true)
        .build();
CompactionHook compactionHook = new CompactionHook(strategy, compactionConfig, estimator);

// Add to your hooks
AgentLoopConfig.builder()
        .hooks(new CompositeAgentHooks(List.of(compactionHook, myOtherHooks)))
        .build();
```

---

## Conversation Persistence

Provide a `ConversationStore`. Use `JdbcConversationStore` from `spring-agent-app`:

```java
import ai.newwave.agent.state.database.JdbcConversationStore;

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

The `ConversationStore` interface:

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

# spring-agent-app

Optional features: activity timeline, agent memory, event scheduling, and JDBC/AWS store implementations.

## Package Structure

```
ai.newwave.agent.state.database     JdbcConversationStore
ai.newwave.agent.timeline           TimelineService, TimelineRecorder, TimelineContextHook
ai.newwave.agent.timeline.model     TimelineEvent, TimelineQuery, TimelineActor
ai.newwave.agent.timeline.spi       TimelineStore
ai.newwave.agent.timeline.database  JdbcTimelineStore
ai.newwave.agent.memory             MemoryService, MemoryContextHook
ai.newwave.agent.memory.model       Memory
ai.newwave.agent.memory.spi         MemoryStore
ai.newwave.agent.memory.database    JdbcMemoryStore
ai.newwave.agent.scheduling         ScheduleService, ScheduleDispatcher
ai.newwave.agent.scheduling.model   ScheduledEvent, SchedulePayload, ScheduleType, RetryConfig
ai.newwave.agent.scheduling.spi     ScheduleStore, ScheduleExecutor
ai.newwave.agent.scheduling.aws     AwsScheduleExecutor, AwsScheduleStore, SqsScheduleListener
```

## Installation

```xml
<dependency>
    <groupId>ai.new-wave</groupId>
    <artifactId>spring-agent-app</artifactId>
    <version>0.3.0</version>
</dependency>
```

---

## Activity Timeline

Persistent "what happened" feed. Wire it yourself:

```java
import ai.newwave.agent.timeline.*;
import ai.newwave.agent.timeline.database.JdbcTimelineStore;

@Bean
public TimelineStore timelineStore(JdbcTemplate jdbc) {
    return new JdbcTimelineStore(jdbc);
}

@Bean
public TimelineService timelineService(TimelineStore store) {
    return new TimelineService(store);
}

@Bean
public TimelineRecorder timelineRecorder(TimelineStore store) {
    return new TimelineRecorder(store);
}

// Add TimelineContextHook to your agent's hooks to inject timeline into LLM context
TimelineContextHook timelineHook = new TimelineContextHook(timelineService, 20);
```

`TimelineRecorder` converts agent events to timeline entries. Use it in your stream pipeline:

```java
agent.stream(request)
    .doOnNext(timelineRecorder::onEvent)
    .map(e -> ServerSentEvent.<AgentEvent>builder().event(e.type().value()).data(e).build());
```

### What Gets Recorded

| Agent Event | Timeline Type | Example |
|-------------|--------------|---------|
| `AgentStart` | `agent_started` | "Agent started" |
| `AgentEnd` | `agent_ended` | "Agent completed" |
| `ToolExecutionStart` | `tool_execution_started` | "Started executing tool 'search'" |
| `ToolExecutionEnd` | `tool_executed` | "Executed tool 'search'" |
| `ScheduleFired` | `schedule_fired` | "Schedule 'daily-report' fired" |
| `TurnStart` | `turn_started` | "Turn 3 started" |
| `MessageEnd` | `message_completed` | "Assistant message completed" |

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

Durable cross-conversation knowledge store. Wire it yourself:

```java
import ai.newwave.agent.memory.*;
import ai.newwave.agent.memory.database.JdbcMemoryStore;

@Bean
public MemoryStore memoryStore(JdbcTemplate jdbc) {
    return new JdbcMemoryStore(jdbc);
}

@Bean
public MemoryService memoryService(MemoryStore store) {
    return new MemoryService(store);
}

// Add MemoryContextHook to your agent's hooks to inject memories into LLM context
MemoryContextHook memoryHook = new MemoryContextHook(memoryService);
```

### Built-in Tools

Register these as tools in your `AgentConfig`:

```java
import ai.newwave.agent.memory.tool.SaveMemoryTool;
import ai.newwave.agent.memory.tool.SearchMemoryTool;

AgentConfig.builder()
    .addTool(new SaveMemoryTool(memoryService))
    .addTool(new SearchMemoryTool(memoryService))
    .build();
```

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

---

## Event Scheduling

Schedule agent actions for later execution. Wire it yourself:

```java
import ai.newwave.agent.scheduling.*;
import ai.newwave.agent.scheduling.aws.*;

@Bean
public ScheduleDispatcher scheduleDispatcher(Agent agent) {
    return new ScheduleDispatcher(agent);
}

@Bean
public ScheduleService scheduleService(ScheduleExecutor executor) {
    return new ScheduleService(executor);
}

// Register the query tool
AgentConfig.builder()
    .addTool(new ScheduleQueryTool(scheduleService))
    .build();
```

### Schedule Types

| Type | Expression | Description |
|------|-----------|-------------|
| `IMMEDIATE` | — | Fire now |
| `ONE_SHOT` | ISO-8601 timestamp | Fire once at a specific time |
| `PERIODIC` | Cron or ISO duration | Recurring |

### Payload Types

```java
import ai.newwave.agent.scheduling.model.SchedulePayload;

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

### Backend: AWS (EventBridge)

```java
import ai.newwave.agent.scheduling.aws.*;

SchedulerClient schedulerClient = SchedulerClient.create();
SqsClient sqsClient = SqsClient.create();
DynamoDbClient dynamoDbClient = DynamoDbClient.create();

ScheduleStore store = new AwsScheduleStore(dynamoDbClient, "spring-agent-schedules");
ScheduleExecutor executor = new AwsScheduleExecutor(schedulerClient, store, sqsTargetArn, roleArn, "spring-agent");
SqsScheduleListener listener = new SqsScheduleListener(sqsClient, queueUrl, store, dispatcher, Duration.ofSeconds(30));
```

Required AWS resources: SQS queue, DynamoDB table (partition key `id`, GSI `enabled-nextFireTime-index`), IAM role, EventBridge schedule group.

### Backend: PostgreSQL (JDBC)

```java
import ai.newwave.agent.scheduling.database.JdbcScheduleStore;

ScheduleStore store = new JdbcScheduleStore(jdbc);
```

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

---

# Interfaces

All interfaces — implement your own or use the provided implementations.

### spring-agent-core

| Interface | Purpose | Provided Implementation |
|-----------|---------|------------------------|
| `ConversationStore` | Message persistence | `JdbcConversationStore` (in app module) |
| `AgentHooks` | Lifecycle hooks | `CompositeAgentHooks` for chaining |
| `TokenEstimator` | Token counting | `SimpleTokenEstimator` (length / 4) |
| `CompactionStrategy` | Context summarization | `LlmCompactionStrategy` |

### spring-agent-app

| Interface | Purpose | Provided Implementation |
|-----------|---------|------------------------|
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
