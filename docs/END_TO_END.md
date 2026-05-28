# spring-agent — End-to-End Guide

A walkthrough of what spring-agent is, what each piece does, and how a real
production app (`new-wave-api`) wires it together. Read this top-to-bottom and
you should know enough to drop it into your own Spring Boot 3 app.

---

## 1. What spring-agent is (and is not)

spring-agent is an **AI agent client library** for Java / Spring Boot 3, built on
[Spring AI](https://spring.io/projects/spring-ai). Think of it as the
server-side analogue of the Claude Code / Cursor agent loop:

> **LLM call → tool calls → feed results back → LLM call → … → done.**

What it gives you:

- A stateless `Agent` bean. Each `agent.stream(...)` call is self-contained:
  load history, run the loop, persist new messages, emit events.
- A **streaming event model** (`Flux<AgentEvent>`) you can pipe straight to
  Server-Sent Events.
- Pluggable **tools** (typed records, schema auto-generated).
- Pluggable **hooks** (before/after tool, transform context).
- Pluggable **state**: conversation history (`ConversationStore`) + run
  control (`ConversationStateManager` — locks, steer, follow-up, abort).
- Optional **app features**: timeline of business events, durable memory,
  cross-instance scheduling.

What it is **not**:

- An autoconfig starter. Nothing magical happens on classpath; you wire every
  bean yourself, SDK-style. This is intentional — multi-tenant apps usually
  need control over which stores, hooks, and tools are active.
- A workflow engine. There's no DAG, no retries-with-backoff for whole runs.
  The unit of work is one `stream()` call.
- Coupled to Anthropic. The default `PromptBuilder` is tuned for Anthropic
  (with prompt caching), but `PromptBuilder` is an interface — swap it.

---

## 2. The two modules

| Module | What's in it | When you need it |
|---|---|---|
| [spring-agent-core](../spring-agent-core/) | `Agent`, tools, events, hooks, compaction, `ConversationStore` (JDBC + R2DBC), Redis/DynamoDB state managers | Always |
| [spring-agent-app](../spring-agent-app/) | Timeline, memory, scheduling, AWS executors | Optional |

`new-wave-api` uses both — see [SpringAgentConfig.java](../../new-wave-ugc/new-wave-api/src/main/java/ai/new_wave/api/config/SpringAgentConfig.java).

---

## 3. Mental model

```
        ┌─────────────────────── controller (SSE) ────────────────────┐
        │                                                              │
   HTTP ┴► agent.stream(AgentRequest)                                  │
              │                                                        │
              ├─ acquire lock (ConversationStateManager)                │
              ├─ load history (ConversationStore.loadMessages)          │
              ├─ append new user message                                │
              ├─ loop (up to maxTurns):                                 │
              │     ├─ hooks.transformContext(...)                      │
              │     ├─ LLM call (ChatModel via PromptBuilder)           │
              │     ├─ emit Message* / Thinking* events                 │
              │     ├─ if tool calls →                                  │
              │     │     ├─ hooks.beforeToolCall                       │
              │     │     ├─ tool.execute(ToolCallContext)              │
              │     │     ├─ hooks.afterToolCall                        │
              │     │     └─ feed ToolResult back                       │
              │     └─ else stop                                        │
              ├─ drain follow-up queue (outer loop)                     │
              ├─ persist new messages (atomic, sequenced)               │
              └─ emit AgentEnd { usage, error? } ──► Flux<AgentEvent> ──┘
```

Two pieces of identity drive everything:

| Concept | Type | What it means |
|---|---|---|
| `agentId` | UUID | The **tenant**: a user, workspace, or whatever you isolate on. Holds many conversations. |
| `conversationId` | UUID (or `"default"`) | One chat thread under an agentId. Isolated history. |

In `new-wave-api`, `agentId = workspaceId`, so all members of a workspace
share conversations — the "agent" is the workspace's assistant.

---

## 4. The agent loop in detail

Inside one `stream()` call, the inner loop runs until the LLM stops calling
tools (or `maxTurns` is hit). Each iteration:

1. **transformContext hook** — last chance to inject dynamic data (e.g.
   workspace state, alerts, memory, timeline). Returns the full message list.
2. **LLM call** via Spring AI's `ChatModel` using the configured
   `PromptBuilder`. The default `AnthropicPromptBuilder` enables prompt
   caching across turns for the system prompt and tool definitions.
3. **Stream events**: `MessageStart`, `ThinkingUpdate`/`MessageUpdate`
   deltas, `MessageEnd`.
4. **If tool calls** are in the response:
   - `beforeToolCall` hook (can block or rewrite)
   - tool's `execute(ToolCallContext)` runs (PARALLEL or SEQUENTIAL)
   - `afterToolCall` hook (can rewrite result)
   - emit `ToolExecutionStart` / `ToolExecutionEnd`
   - feed `ToolResult` into next turn
5. **No tool calls** → loop ends.

After the inner loop, the **outer loop** drains the follow-up queue (messages
queued via `agent.followUp(...)` or that arrived during the run). Then
messages are persisted atomically and `AgentEnd` is emitted.

---

## 5. Wiring it up — annotated `new-wave-api` example

Below is the gist of [SpringAgentConfig.java](../../new-wave-ugc/new-wave-api/src/main/java/ai/new_wave/api/config/SpringAgentConfig.java),
which is the canonical "everything turned on" wiring.

### 5.1 R2DBC database client

R2DBC is used for non-blocking access to the same Postgres as JPA. **Don't
expose `ConnectionFactory` as a bean** — Spring Boot's
`DataSourceAutoConfiguration` would skip your JDBC `DataSource` and JPA
breaks. Build the factory inline and expose only `DatabaseClient`:

```java
@Bean
public DatabaseClient agentDatabaseClient() {
    ConnectionFactory cf = ConnectionFactories.get(
        ConnectionFactoryOptions.parse(r2dbcUrl).mutate()
            .option(USER, user).option(PASSWORD, pass).build());
    return DatabaseClient.create(cf);
}
```

### 5.2 Conversation store, timeline, memory (all R2DBC)

```java
@Bean ConversationStore conversationStore(DatabaseClient db) { return new R2dbcConversationStore(db); }
@Bean TimelineStore     timelineStore(DatabaseClient db)     { return new R2dbcTimelineStore(db); }
@Bean MemoryStore       memoryStore(DatabaseClient db)       { return new R2dbcMemoryStore(db); }
```

Required DDL is documented per-feature in the [README](../README.md). For
`conversation_messages` the unique index on
`(agent_id, conversation_id, sequence)` is what makes
[atomic-batch persistence](../CHANGELOG.md) safe under concurrent runs.

### 5.3 Hooks: order matters

`new-wave-api` chains five hooks via `CompositeAgentHooks`:

```java
var hooks = new CompositeAgentHooks(List.of(
    workspaceAgentHooks,    // injects "[Dynamic Context]" + "[Alerts]" block
    compactionHook,         // summarizes old messages when > 500k tokens
    memoryContextHook,      // injects relevant Memory entries
    timelineContextHook,    // injects recent timeline events (tenant-scoped)
    idWhitelistHook         // tracks valid IDs seen in tool output (anti-hallucination)
));
```

Two things to note:

- **The tenant fix.** spring-agent ships `TimelineContextHook` that queries
  the timeline by limit only — fine for single-tenant apps. In a multi-tenant
  app this leaks. `new-wave-api` replaces it with
  [WorkspaceTimelineContextHook](../../new-wave-ugc/new-wave-api/src/main/java/ai/new_wave/api/agent/WorkspaceTimelineContextHook.java)
  that scopes by `agentId`. If you go multi-tenant, do the same.
- **Hooks see `HookContext`**, which carries `agentId`, `conversationId`, and
  the per-request `attributes` map. That's where you get `workspaceId`,
  `userId`, etc. through to tools.

### 5.4 The `Agent` bean

```java
@Bean
public Agent agent(ChatModel chatModel,
                   ConversationStore store,
                   ConversationStateManager stateManager,
                   List<AgentTool<?, ?>> tools,
                   /* + hooks */) {
    AgentConfig config = AgentConfig.builder()
        .systemPrompt(loadFromResources())
        .thinkingLevel(ThinkingLevel.LOW)
        .tools(tools)
        .loopConfig(AgentLoopConfig.builder()
            .maxTurns(10)
            .maxToolResultsInContext(5)
            .toolExecutionMode(ToolExecutionMode.PARALLEL)
            .hooks(hooks)
            .build())
        .build();
    return new Agent(config, chatModel, store, stateManager);
}
```

Things worth calling out:

| Setting | Why this value | When to deviate |
|---|---|---|
| `thinkingLevel: LOW` | ~1k thinking budget. Enough for routing decisions, cheap. | Use `MEDIUM`/`HIGH` for deeper reasoning; `OFF` for chatty assistants. |
| `maxTurns: 10` | Stops runaway loops. | Bump if your tools naturally chain (e.g. research bots). |
| `maxToolResultsInContext: 5` | Only the **last 5** tool-call/result pairs are sent to the LLM next turn. Older ones are pruned to control context size. | Set `0` for unlimited, or fewer to be aggressive. |
| `toolExecutionMode: PARALLEL` | If the LLM requests N tool calls in one turn, run them concurrently. | Use `SEQUENTIAL` when tools share mutable state. |

`ConversationStateManager` (Redis here) unlocks `steer`, `followUp`, `abort`,
and `getStatus`. Without it, the agent is fire-and-forget and concurrent
messages on the same conversation can interleave.

---

## 6. Exposing it over HTTP — SSE controller

See [WorkspaceAgentController.java](../../new-wave-ugc/new-wave-api/src/main/java/ai/new_wave/api/controller/WorkspaceAgentController.java).
The chat endpoint is a textbook reactive SSE pipeline:

```java
@PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<AgentEvent>> chat(...) {
    return agent.stream(AgentRequest.builder()
                .agentId(workspaceId.toString())
                .conversationId(convId.toString())
                .message(userMessage)
                .attribute("workspaceId", workspaceId)
                .attribute("userId",      userId)
                .attribute("metadata",    metadata)
                .build())
        .map(idWhitelistRewriter::rewrite)    // post-process events
        .map(event -> ServerSentEvent.<AgentEvent>builder()
                .event(event.type().value())  // SSE event name = enum wire value
                .data(event)
                .build())
        .onErrorResume(err -> Flux.just(/* error event */));
}
```

The full service (see
[WorkspaceAgentService.java](../../new-wave-ugc/new-wave-api/src/main/java/ai/new_wave/api/service/WorkspaceAgentService.java))
also:

- Creates a `WorkspaceConversation` row before streaming (so the conversation
  shows up in the sidebar with a title even before the first response lands).
- Records token usage on `AgentEnd` via `tokenUsageService.record(...)`.
- Uses `TimelineRecorder` (a `Consumer<AgentEvent>`) to mirror agent
  activity into the timeline store.

The companion REST endpoints (`list`, `get`, `delete`, `abort`, `status`)
all delegate to `ConversationStore` and `agent.abort()/getStatus()` — no
extra plumbing required.

---

## 7. Writing tools

A tool is a Spring `@Component` implementing `AgentTool<Params, Detail>`.
`Params` is a Java `record`; the JSON schema is auto-derived from it. Use
`@Description` (spring-agent) or `@JsonPropertyDescription` (Jackson) to
document fields for the LLM.

Minimal example:

```java
@Component
public class GetCampaignTool implements AgentTool<GetCampaignTool.Params, CampaignDTO> {

    public record Params(
        @Description("Campaign UUID") String campaignId
    ) {}

    private final CampaignService campaigns;

    public String name()        { return "get_campaign"; }
    public String label()       { return "Get Campaign"; }
    public String description() { return "Fetch details of a campaign by id."; }
    public Class<Params> parameterType() { return Params.class; }

    @Override
    public Mono<AgentToolResult<CampaignDTO>> execute(ToolCallContext<Params> ctx) {
        UUID wsId = (UUID) ctx.attributes().get("workspaceId");
        CampaignDTO c = campaigns.get(wsId, UUID.fromString(ctx.parameters().campaignId()));
        return Mono.just(AgentToolResult.success(c.summary(), c));
    }
}
```

Patterns used by `new-wave-api` worth borrowing:

1. **Always read tenancy from `ctx.attributes()`**, never from a stored
   `ThreadLocal`. Tools are singletons; the request context arrives per call.
2. **Group related actions into a single tool with an `action` discriminator.**
   `ActionAgentTool` is `new-wave-api`'s base class — one tool registration,
   many sub-actions, cleaner system prompt. See
   [ActionAgentTool.java](../../new-wave-ugc/new-wave-api/src/main/java/ai/new_wave/api/agent/tools/common/ActionAgentTool.java).
3. **Use `excludeFromContext()` for "client-side" tools.** E.g.
   [AskUserQuestionTool](../../new-wave-ugc/new-wave-api/src/main/java/ai/new_wave/api/agent/tools/common/AskUserQuestionTool.java)
   renders an interactive card in the UI; its "result" is just a marker —
   sending it back to the model wastes tokens.
4. **Return both a text summary and typed detail.**
   `AgentToolResult.success("Found 3 campaigns: A, B, C", listOfCampaigns)` —
   the LLM gets the text; the frontend gets the structured detail through
   the event stream.

Register tools simply by making them `@Component`s. Spring injects
`List<AgentTool<?, ?>> tools` into the `Agent` bean factory.

---

## 8. Hooks: the extension points

Hooks are how you stop the agent from doing the wrong thing and how you
inject runtime context. All four methods are optional.

| Hook | Signature | Typical use |
|---|---|---|
| `transformContext` | `(ctx, msgs) → Mono<List<AgentMessage>>` | Inject dynamic state, prune messages, compaction. |
| `convertToLlm` | `(ctx, msgs) → List<AgentMessage>` | Last-mile formatting per provider. |
| `beforeToolCall` | `(ctx, name, use) → Mono<BeforeToolCallResult>` | Block or require approval for sensitive tools. |
| `afterToolCall` | `(ctx, name, use, result) → Mono<AgentToolResult<?>>` | Log, redact, or rewrite results. |

`new-wave-api`'s `WorkspaceAgentHooks.transformContext` does blocking JPA
work, so it uses `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`
to stay reactive. **Do this** if your hook touches JDBC/JPA. Otherwise you'll
block the Netty event loop and tank throughput.

Chain hooks with `new CompositeAgentHooks(List.of(...))`. Order of
`transformContext` is the order in `List` — the first hook sees the raw
messages, the next sees the previous hook's output. Put compaction late
(after context-injection hooks) so it sees the full picture.

---

## 9. Conversation control: steer, followUp, abort

These require a `ConversationStateManager` (Redis or DynamoDB).

| Method | Semantics |
|---|---|
| `agent.steer(agentId, convId, msg)` | Inject `msg` **into the running loop** before the next LLM call. Use for "wait, also do X." |
| `agent.followUp(agentId, convId, msg)` | Queue `msg` to run **after the current loop completes**. Use for system-triggered prompts. |
| `agent.abort(agentId, convId)` | Cooperative cancel — the loop checks between turns. |
| `agent.getStatus(agentId, convId)` | `IDLE` / `RUNNING` / `ABORTING`. |

`new-wave-api`'s
[AgentReactionDispatcher](../../new-wave-ugc/new-wave-api/src/main/java/ai/new_wave/api/agent/AgentReactionDispatcher.java)
uses `followUp` to drive a workspace's "system conversation" in response to
external events (creator applied, content submitted, etc.). The agent reacts
to the event without a user prompt — a classic ambient-agent pattern.

---

## 10. Optional features

### 10.1 Timeline (activity feed)

Records discrete business events the agent should be aware of. Different
from conversation messages — these are *what happened in the world*, not
*what was said in the chat*.

```java
timelineService.record(
    new TimelineActor.System("billing"),
    "invoice_paid",
    "Invoice #1234 paid ($500)"
).subscribe();
```

The `TimelineContextHook` (or in `new-wave-api`'s case,
`WorkspaceTimelineContextHook`) injects the N most recent events into each
LLM call so the agent knows "campaign was activated 5 minutes ago."

### 10.2 Memory (cross-conversation facts)

Durable key/tags/content store. Two built-in tools — `save_memory` and
`search_memory` — let the LLM persist facts on its own. `MemoryContextHook`
auto-injects relevant memories into each turn.

### 10.3 Scheduling

`ScheduleService` + `ScheduleDispatcher` give you cron/one-shot scheduling
that fires `PromptAction` / `SteerAction` / `FollowUpAction` payloads.
Backed by AWS EventBridge + DynamoDB + SQS in `new-wave-api`. Use for daily
reports, ramp reminders, anything time-driven.

---

## 11. Compaction — keeping context windows sane

`CompactionHook` summarizes old messages when token estimate exceeds
`maxContextTokens`. `new-wave-api` configures:

```java
CompactionConfig.builder()
    .maxContextTokens(500_000)      // trigger threshold
    .preserveRecentCount(10)        // never summarize the last 10 messages
    .maxSummaryTokens(20_000)       // budget for the LLM-generated summary
    .preserveToolResults(true)      // keep tool results unsummarized
    .build();
```

It's just a hook — wire it into your `CompositeAgentHooks` (after
context-injection hooks, before persistence). The summary becomes a synthetic
`AgentMessage` in the history and replaces the messages it summarized.

---

## 12. Producing events on the wire

Every event implements `AgentEvent` (sealed) and serializes cleanly to JSON.
SSE event names come from `AgentEventType.value()`. The wire types you'll
likely care about on the frontend:

| Event | What it carries | UI use |
|---|---|---|
| `message_update` | text delta | Stream assistant text token-by-token. |
| `thinking_update` | thinking delta | Optional "agent is thinking..." indicator. |
| `tool_execution_start` | `toolUse` (name, input) | Show "Running `get_campaign`…" pill. |
| `tool_execution_end` | `toolUse`, `result` | Replace pill with result summary; render typed `details` in cards. |
| `agent_end` | `error?`, `usage` (TokenUsage) | Final state; close the SSE stream; record tokens. |

Pro tip from `new-wave-api`: postprocess events server-side before SSE-ing
them. The `IdWhitelistRewriter` strips hallucinated IDs from streamed text
using IDs accumulated by `IdWhitelistHook` from tool outputs.

---

## 13. Operations checklist

Before shipping to prod:

- [ ] **Tables created** — `conversation_messages` (+ unique index on
      `(agent_id, conversation_id, sequence)`), and any of
      `timeline_events`, `memories`, `scheduled_events` you use.
- [ ] **Redis or DynamoDB up** — required for `steer`/`followUp`/`abort`.
- [ ] **Per-tenant timeline scoping** — if multi-tenant, replace
      `TimelineContextHook` with a tenant-scoped version.
- [ ] **Token usage recorded** — observe `AgentEnd.usage` and bill/limit.
- [ ] **Abort wired** — frontend "stop" button calls
      `POST /conversations/{id}/abort`.
- [ ] **`maxTurns` set conservatively** — start at 10, raise if you see
      legitimate truncation.
- [ ] **Tools authorize per call** — every tool re-validates the caller can
      touch the resource it reads/writes. Don't trust the LLM.
- [ ] **Blocking work in hooks/tools** runs on `boundedElastic`.

---

## 14. Quick reference — putting a new agent in your app

1. Add the deps:

   ```xml
   <dependency>
     <groupId>ai.new-wave</groupId>
     <artifactId>spring-agent-core</artifactId>
     <version>1.5.1</version>
   </dependency>
   <!-- optional -->
   <dependency>
     <groupId>ai.new-wave</groupId>
     <artifactId>spring-agent-app</artifactId>
     <version>1.5.1</version>
   </dependency>
   ```

2. Create the SQL tables you need (`conversation_messages` is the minimum).

3. Add an `@Configuration` class modeled on
   [SpringAgentConfig.java](../../new-wave-ugc/new-wave-api/src/main/java/ai/new_wave/api/config/SpringAgentConfig.java).
   Start small: `ConversationStore` + `Agent`, no state manager, no
   compaction. Add features as you need them.

4. Implement your first tool as a `@Component`. It'll be auto-injected.

5. Build a controller that calls `agent.stream(...)` and pipes events to SSE.

6. Open a connection and send a message. You're done.

---

## 15. Where to look next

- **What changed when** → [CHANGELOG.md](../CHANGELOG.md). The 1.5.x line
  introduced atomic batch persistence with sequence-ordered writes — this is
  what prevents message reordering when two requests race on the same
  conversation.
- **Reference docs for every config field, hook, event type** →
  [README.md](../README.md). Treat as the authoritative API spec; treat
  this doc as the conceptual map.
- **A real, production wiring** →
  [new-wave-api/src/main/java/ai/new_wave/api/](../../new-wave-ugc/new-wave-api/src/main/java/ai/new_wave/api/).
  In particular `config/SpringAgentConfig.java`, `controller/WorkspaceAgentController.java`,
  `service/WorkspaceAgentService.java`, `agent/`.
