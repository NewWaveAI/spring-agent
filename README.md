# spring-agent

A stateful AI agent framework for Java, built on [Spring Boot 4.0](https://spring.io/projects/spring-boot) and [Spring AI 2.0](https://spring.io/projects/spring-ai). Inspired by [@mariozechner/spring-agent-core](https://github.com/badlogic/pi-mono/tree/main/packages/agent).

## Features

- **Stateful multi-turn conversations** with streaming LLM responses (Anthropic Claude)
- **Tool execution** — generic `AgentTool<P, D>` interface with sequential or parallel execution
- **Agent loop** — inner loop (LLM -> tool calls -> recurse) + outer loop (follow-up queue drain)
- **Steering & follow-up queues** — inject messages mid-turn or post-completion
- **Event system** — 11 lifecycle event types via sealed interface, callback + reactive (`Flux`) subscriptions
- **Activity timeline** — persistent "what happened" feed, auto-recorded from agent events, agent-queryable via tool
- **Conversation compaction** — LLM-based context summarization when token limits are approached
- **Event scheduling** — immediate, one-shot, and periodic (cron) with pluggable backends (in-memory, AWS, JDBC)
- **SSE proxy endpoint** — opt-in `POST /api/stream` returning `Flux<ServerSentEvent<AgentEvent>>`

## Requirements

- Java 21+
- Spring Boot 4.0.0-M1+
- Spring AI 2.0.0-M1+

## Quick Start

Add the dependency to your Spring Boot application:

```xml
<dependency>
    <groupId>ai.new-wave</groupId>
    <artifactId>spring-agent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Configure in your `application.yml`:

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
  thinking-level: off          # off | low | medium | high | xhigh
  tool-execution-mode: parallel # parallel | sequential
  max-turns: 25
```

---

## Core Concepts

### Agent

The `Agent` class is the main public API. It manages the conversation lifecycle, tool execution, message queuing, and event streaming.

```java
@Service
public class MyService {
    private final Agent agent;

    public MyService(Agent agent) {
        this.agent = agent;
    }

    public void chat(String userMessage) {
        // Subscribe to streaming events
        agent.subscribe(event -> {
            if (event instanceof AgentEvent.MessageUpdate update) {
                System.out.print(update.delta());
            }
        });

        // Send a prompt (blocks until agent completes)
        agent.prompt(userMessage).block();

        // Or use reactively
        agent.prompt(userMessage).subscribe();
        agent.waitForIdle().block();
    }
}
```

**Key methods:**

| Method | Description |
|--------|-------------|
| `prompt(message)` | Start a new conversation turn |
| `continueConversation()` | Resume from current transcript |
| `steer(message)` | Inject a message mid-turn (next loop iteration) |
| `followUp(message)` | Queue a message for after the current run |
| `subscribe(listener)` | Listen to lifecycle events (returns `Disposable`) |
| `events()` | Get a `Flux<AgentEvent>` reactive stream |
| `abort()` | Cancel the current run |
| `waitForIdle()` | `Mono<Void>` that completes when agent finishes |
| `reset()` | Clear all state, messages, and queues |

### Agent Loop

The agent loop has two layers:

```
outerLoop:
  innerLoop:
    1. Drain steering queue into messages
    2. Apply transformContext hook (compaction, timeline injection)
    3. Stream LLM response (emit message_start/update/end events)
    4. Extract tool calls from response
    5. If no tool calls -> exit innerLoop
    6. Execute tools (parallel or sequential)
       - beforeToolCall hook (can block)
       - Execute tool
       - afterToolCall hook (can modify result)
    7. Recurse innerLoop
  Check follow-up queue
  Has follow-up? -> add to messages, recurse outerLoop
  Done -> emit agent_end
```

### Events

All agent lifecycle events are modeled as a sealed interface for exhaustive pattern matching:

```java
agent.subscribe(event -> switch (event) {
    case AgentEvent.AgentStart e      -> log.info("Agent started");
    case AgentEvent.MessageUpdate e   -> System.out.print(e.delta());
    case AgentEvent.ToolExecutionEnd e -> log.info("Tool {} completed", e.toolUse().name());
    case AgentEvent.ScheduleFired e   -> log.info("Schedule {} fired", e.scheduleId());
    // ... 11 event types total
    default -> {}
});
```

Events can also be consumed as a reactive stream:

```java
agent.events()
    .filter(e -> e instanceof AgentEvent.MessageUpdate)
    .map(e -> ((AgentEvent.MessageUpdate) e).delta())
    .subscribe(System.out::print);
```

### Tools

Implement `AgentTool<P, D>` with typed parameters and results. Tools annotated with `@Component` are auto-discovered.

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
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
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

### Hooks

Customize agent behavior via `AgentHooks`:

```java
@Component
public class MyHooks implements AgentHooks {

    @Override
    public Mono<BeforeToolCallResult> beforeToolCall(String toolName, ContentBlock.ToolUse toolUse) {
        if (toolName.equals("dangerous_tool")) {
            return Mono.just(BeforeToolCallResult.block("Tool requires approval"));
        }
        return Mono.just(BeforeToolCallResult.allow());
    }

    @Override
    public List<AgentMessage> transformContext(List<AgentMessage> messages) {
        // Prune, compact, or inject context before each LLM call
        return messages;
    }
}
```

Multiple hooks are automatically composed via `CompositeAgentHooks` (first registered = first executed).

---

## Activity Timeline

Records agent lifecycle events as a queryable activity feed. The agent can reference recent activity for situational awareness.

```yaml
agent:
  timeline:
    enabled: true
    max-store-size: 10000                   # max events in memory (default)
    max-recent-events-for-context: 20       # injected into LLM context
    context-injection-enabled: true         # auto-prepend timeline to context
    query-tool-enabled: true                # agent can query timeline via tool
```

### What gets recorded

The `TimelineRecorder` automatically converts agent events to timeline entries:

| Agent Event | Timeline Entry |
|-------------|---------------|
| `AgentStart` | "Agent started" |
| `AgentEnd` | "Agent completed" or "Agent ended with error: ..." |
| `ToolExecutionStart` | "Started executing tool 'search'" |
| `ToolExecutionEnd` | "Executed tool 'search'" |
| `ScheduleFired` | "Schedule 'daily-report' fired" |
| `TurnStart` | "Turn 3 started" |
| `MessageEnd` | "Assistant message completed" |

High-frequency events (`MessageUpdate`, `MessageStart`) are skipped to avoid noise.

### Custom events

Record your own events from application code:

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

### Agent querying

The agent can use the built-in `query_timeline` tool to ask "what happened recently?" This is registered automatically when `query-tool-enabled: true`.

### Context injection

When `context-injection-enabled: true`, recent timeline events are prepended to the LLM context as an `[Activity Timeline]` message before each call, giving the agent passive situational awareness.

### JDBC backend

For production persistence, add `spring-boot-starter-data-jdbc` to your app and provide a `JdbcTimelineStore` bean:

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

---

## Conversation Compaction

Automatically summarizes old messages when context grows too large, using the LLM itself.

```yaml
agent:
  compaction:
    enabled: true
    max-context-tokens: 100000     # trigger compaction above this
    preserve-recent-count: 10      # keep last N messages intact
    max-summary-tokens: 2000       # max tokens for the summary
    preserve-tool-results: true    # include tool results in summary
```

### How it works

1. Before each LLM call, `CompactionHook.transformContext()` estimates the token count
2. If tokens exceed `max-context-tokens`, it splits messages:
   - **Older messages** (before last N) -> sent to LLM for summarization
   - **Recent messages** (last N) -> kept intact
3. The summary replaces the older messages as a `[Conversation Summary]` user message
4. The LLM sees: `[summary] + [recent messages]` instead of the full history

### Custom token estimator

The default `SimpleTokenEstimator` uses `text.length() / 4`. Override with your own:

```java
@Bean
public TokenEstimator tokenEstimator() {
    return new MyTikTokenEstimator(); // your implementation
}
```

### Custom compaction strategy

Replace the LLM-based strategy entirely:

```java
@Bean
public CompactionStrategy compactionStrategy() {
    return new MyCustomCompactionStrategy();
}
```

---

## Event Scheduling

Schedule agent actions (prompt, steer, follow-up) for later execution. Three backends available.

```yaml
agent:
  scheduling:
    enabled: true
    provider: memory  # memory | aws | database
```

### Schedule types

| Type | Expression | Description |
|------|-----------|-------------|
| `IMMEDIATE` | *(none)* | Fire immediately |
| `ONE_SHOT` | ISO-8601 timestamp | Fire once at a specific time |
| `PERIODIC` | Cron or ISO duration | Fire on a recurring schedule |

### Payload types

Each schedule triggers an agent action:

```java
// Prompt the agent
new SchedulePayload.PromptAction("default", "Generate the daily report")

// Steer mid-conversation
new SchedulePayload.SteerAction("default", "Also check the error logs")

// Follow up after completion
new SchedulePayload.FollowUpAction("default", "Summarize what you found")

// Custom action
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
                .scheduleExpression("PT5M")  // ISO duration
                .payload(new SchedulePayload.PromptAction("default", "Check system health"))
                .build()
        ).block();
    }

    // Cancel
    public void cancel(String scheduleId) {
        scheduleService.cancel(scheduleId).block();
    }
}
```

### Backend: In-Memory (dev/test)

```yaml
agent:
  scheduling:
    enabled: true
    provider: memory
```

Uses `ScheduledExecutorService` in-process. Data lost on restart. Single instance only.

### Backend: AWS (production)

Uses EventBridge Scheduler for timing, SQS for delivery, DynamoDB for persistence and distributed locking. Safe for ECS auto-scale.

```yaml
agent:
  scheduling:
    enabled: true
    provider: aws
    aws:
      sqs-target-arn: arn:aws:sqs:us-east-1:123456789:spring-agent-schedules
      sqs-queue-url: https://sqs.us-east-1.amazonaws.com/123456789/spring-agent-schedules
      role-arn: arn:aws:iam::123456789:role/eventbridge-sqs-role
      schedule-group: spring-agent          # EventBridge schedule group
      dynamodb-table: spring-agent-schedules # DynamoDB table name
      lock-ttl: PT30S                    # distributed lock TTL
      poll-interval: PT5S                # SQS polling interval
```

**Required AWS resources:**

1. **SQS Queue** — receives messages from EventBridge when schedules fire
2. **DynamoDB Table** — persists schedule metadata and distributed locks
   - Partition key: `id` (String)
   - GSI: `enabled-nextFireTime-index` (enabled=PK, nextFireTime=SK)
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

**How it works:**

```
Your app                    AWS
  |
  |-- create schedule -->  EventBridge Scheduler (creates rule)
  |                        DynamoDB (persists metadata)
  |
  |                        [time passes...]
  |
  |                        EventBridge fires --> SQS queue
  |                                               |
  |<-- SqsScheduleListener polls SQS ------------|
  |
  |-- tryAcquireLock (DynamoDB conditional write)
  |   (only one ECS instance wins)
  |
  |-- dispatch to agent (prompt/steer/followUp)
  |-- releaseLock
  |-- delete SQS message
```

### Backend: Database (JDBC)

For setups with a shared database but no AWS. Provide a `JdbcScheduleStore` bean and a polling executor.

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

## SSE Proxy

Opt-in HTTP endpoint for streaming agent events via Server-Sent Events:

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

Returns `text/event-stream` with events like:

```
event: message_update
data: {"type":"message_update","timestamp":"...","delta":"Hello"}

event: tool_execution_start
data: {"type":"tool_execution_start","timestamp":"...","toolUse":{"id":"...","name":"search"}}

event: agent_end
data: {"type":"agent_end","timestamp":"...","error":null}
```

---

## Architecture

```
com.newwave.agent/
  core/               Agent, AgentLoop, AgentToolCallbackAdapter
  model/              ContentBlock (sealed), AgentMessage, enums
  tool/               AgentTool<P,D>, AgentToolResult<D>, ToolCallContext<P>
  config/             AgentConfig, AgentHooks, CompositeAgentHooks, auto-config
  event/              AgentEvent (sealed, 11 types), EventEmitter
  state/              AgentState, AgentStatus, MessageQueue
  compaction/         TokenEstimator (SPI), CompactionStrategy (SPI), CompactionHook
  timeline/           TimelineStore (SPI), TimelineRecorder, TimelineService, TimelineQueryTool
  scheduling/         ScheduleStore (SPI), ScheduleExecutor (SPI), ScheduleDispatcher
    aws/              AwsScheduleExecutor, AwsScheduleStore, SqsScheduleListener
    database/         JdbcScheduleStore
    memory/           InMemoryScheduleExecutor, InMemoryScheduleStore
  proxy/              StreamProxyController (opt-in)
```

### Extension Points (SPIs)

All SPIs use `@ConditionalOnMissingBean` -- provide your own bean to override the default.

| SPI | Purpose | Default |
|-----|---------|---------|
| `AgentHooks` | Lifecycle hooks (beforeToolCall, transformContext, etc.) | No-op |
| `TimelineStore` | Timeline event persistence | `InMemoryTimelineStore` |
| `TokenEstimator` | Token counting for compaction | `SimpleTokenEstimator` (length/4) |
| `CompactionStrategy` | How to summarize old messages | `LlmCompactionStrategy` |
| `ScheduleStore` | Schedule persistence + distributed locking | `InMemoryScheduleStore` |
| `ScheduleExecutor` | Schedule execution engine | `InMemoryScheduleExecutor` |

### Auto-Configuration

All features are opt-in via `application.yml` properties. The library registers auto-configuration classes via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

- `AgentAutoConfiguration` — core agent, hooks composition
- `CompactionAutoConfiguration` — `agent.compaction.enabled=true`
- `TimelineAutoConfiguration` — `agent.timeline.enabled=true`
- `SchedulingAutoConfiguration` — `agent.scheduling.enabled=true` (in-memory)
- `AwsSchedulingAutoConfiguration` — `agent.scheduling.provider=aws` + AWS SDK on classpath

---

## Configuration Reference

### Core (`agent.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `agent.system-prompt` | "You are a helpful assistant." | System prompt for the LLM |
| `agent.thinking-level` | `off` | Thinking level: off, low, medium, high, xhigh |
| `agent.tool-execution-mode` | `parallel` | Tool execution: parallel, sequential |
| `agent.max-turns` | `25` | Max LLM turns before stopping |
| `agent.max-tokens` | `8192` | Max output tokens per LLM call |

### Timeline (`agent.timeline.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `agent.timeline.enabled` | `false` | Enable timeline feature |
| `agent.timeline.max-store-size` | `10000` | Max events in in-memory store |
| `agent.timeline.max-recent-events-for-context` | `20` | Events injected into LLM context |
| `agent.timeline.context-injection-enabled` | `true` | Auto-prepend timeline to context |
| `agent.timeline.query-tool-enabled` | `true` | Register timeline query tool |

### Compaction (`agent.compaction.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `agent.compaction.enabled` | `false` | Enable compaction feature |
| `agent.compaction.max-context-tokens` | `100000` | Trigger compaction above this |
| `agent.compaction.preserve-recent-count` | `10` | Recent messages to keep intact |
| `agent.compaction.max-summary-tokens` | `2000` | Max tokens for the summary |
| `agent.compaction.preserve-tool-results` | `true` | Include tool results in summary |

### Scheduling (`agent.scheduling.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `agent.scheduling.enabled` | `false` | Enable scheduling feature |
| `agent.scheduling.provider` | `memory` | Backend: memory, aws, database |

### Scheduling AWS (`agent.scheduling.aws.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `agent.scheduling.aws.sqs-target-arn` | *(required)* | SQS queue ARN for EventBridge target |
| `agent.scheduling.aws.sqs-queue-url` | *(required)* | SQS queue URL for polling |
| `agent.scheduling.aws.role-arn` | *(required)* | IAM role for EventBridge -> SQS |
| `agent.scheduling.aws.schedule-group` | `spring-agent` | EventBridge schedule group name |
| `agent.scheduling.aws.dynamodb-table` | `spring-agent-schedules` | DynamoDB table name |
| `agent.scheduling.aws.lock-ttl` | `PT30S` | Distributed lock TTL |
| `agent.scheduling.aws.poll-interval` | `PT5S` | SQS polling interval |

### Proxy (`agent.proxy.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `agent.proxy.enabled` | `false` | Enable SSE proxy endpoint |

---

## Building

```bash
./mvnw clean compile    # Compile
./mvnw test             # Run tests
./mvnw install          # Install to local Maven repo
```

## License

TBD
