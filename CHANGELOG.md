# Changelog

## [1.2.4] - 2026-04-02

### Added
- `AgentToolResult.excludeFromContext` flag — tool_use/tool_result pair stored in history but stripped from LLM context
- `AgentToolResult.terminate(text, excludeFromContext)` factory method

## [1.2.3] - 2026-04-02

### Changed
- Removed strict tool_use/tool_result pair matching — all blocks pass through to LLM

### Added
- `AgentLoopConfig.maxToolResultsInContext` — limit older tool pairs sent to LLM (default `0` = unlimited)

## [1.2.2] - 2026-04-02

### Added
- `AgentLoopConfig.maxToolResultsInContext` config with tool_use/tool_result pair sanitization

## [1.2.1] - 2026-04-02

### Added
- `AgentToolResult.terminatesLoop` flag — tools can signal the agent loop to stop after execution
- `AgentToolResult.terminate(text)` and `terminate(text, details)` factory methods
- 2 new tests in `AgentLoopTest` for loop termination behavior

## [1.2.0] - 2026-04-02

### Added
- `ThinkingUpdate` event type — streams thinking deltas separately from text via SSE
- `THINKING_UPDATE("thinking_update")` in `AgentEventType`
- `AgentLoopTest` — 6 tests covering thinking/text separation and event ordering

### Changed
- Thinking content now streams incrementally as `ThinkingUpdate` events (not bundled into `MessageUpdate`)
- Multi-turn context now includes thinking via `AssistantMessage.properties("reasoningContent")` for Anthropic compatibility
- Final `AgentMessage` orders blocks as: `Thinking` → `Text` → `ToolUse`

## [1.1.1] - 2026-04-02

### Changed
- `AgentTool.parameterSchema()` now uses Spring AI's `JsonSchemaGenerator` (backed by victools) instead of custom `SchemaGenerator`
- `@Description` annotation replaced by Jackson standard `@JsonPropertyDescription` (`com.fasterxml.jackson.annotation`)

### Removed
- Custom `SchemaGenerator` class (was in `AgentTool.java`)
- `@Description` annotation (`ai.newwave.agent.tool.Description`)

### Added
- `SchemaGeneratorTest` — 6 test cases for schema generation (flat, nested, deeply nested records)

## [1.1.0] - 2026-04-02

### Added
- R2DBC store implementations — fully non-blocking end-to-end:
  - `R2dbcConversationStore` (`ai.newwave.agent.state.r2dbc`)
  - `R2dbcTimelineStore` (`ai.newwave.agent.timeline.r2dbc`)
  - `R2dbcMemoryStore` (`ai.newwave.agent.memory.r2dbc`)

### Changed
- `AgentHooks.transformContext()` and `convertToLlm()` now return `Mono<List<AgentMessage>>` (reactive, no blocking)
- `CompactionHook`, `MemoryContextHook`, `TimelineContextHook` fully reactive — zero `.block()` calls
- Default model updated to `claude-sonnet-4-6`
- Temperature automatically set to `1.0` when extended thinking is enabled

## [1.0.5] - 2026-04-02

### Fixed
- `TimelineActor` and `SchedulePayload` now have `@JsonTypeInfo` + `@JsonSubTypes` for proper serialization

### Changed
- `TimelineRecorder` gutted to empty shell — no auto-recording of agent lifecycle events
- Timeline is now purely for custom business events (invoices, campaigns, alerts, etc.)

### Added
- `SealedTypeSerializationTest` — roundtrip tests for `TimelineActor` and `SchedulePayload`

## [1.0.4] - 2026-04-02

### Fixed
- ContentBlock serialization now includes `"type"` discriminator via `Json.serializeContentBlocks()` using `writerFor(TypeReference<List<ContentBlock>>)`

### Added
- `Json.serializeContentBlocks()` and `Json.deserializeContentBlocks()` helpers
- `ContentBlockSerializationTest` — roundtrip tests for all 4 ContentBlock subtypes

## [1.0.3] - 2026-04-02

### Changed
- Single shared `Json.MAPPER` (`ai.newwave.agent.util.Json`) replaces 13 separate ObjectMapper instances

## [1.0.2] - 2026-04-02

### Fixed
- `ContentBlock` now has `@JsonTypeInfo` + `@JsonSubTypes` annotations for proper Jackson serialization/deserialization

### Changed
- Single shared `Json.MAPPER` (`ai.newwave.agent.util.Json`) replaces 13 separate ObjectMapper instances

### Changed
- JDBC stores work with any database (PostgreSQL, MySQL, H2, etc.) — README updated accordingly

## [1.0.0] - 2026-04-01

### Changed
- Downgraded to Spring Boot 3.5.9 + Spring AI 1.1.4 for wider adoption
- Removed Spring Milestones repository (Spring AI 1.1.4 is GA on Maven Central)
- Thinking API: `thinkingEnabled()` → `thinking(AnthropicApi.ThinkingType.ENABLED, budgetTokens)`

## [0.3.0] - 2026-04-01

### Removed
- All auto-configuration classes (`AgentAutoConfiguration`, `CompactionAutoConfiguration`, `TimelineAutoConfiguration`, `MemoryAutoConfiguration`, `SchedulingAutoConfiguration`, `AwsSchedulingAutoConfiguration`)
- All properties classes (`AgentProperties`, `CompactionProperties`, `TimelineProperties`, `MemoryProperties`, `SchedulingProperties`)
- Spring Boot `META-INF/spring/AutoConfiguration.imports` files
- No more YAML `agent.*` config — everything is wired explicitly in Java

### Changed
- Library is now just classes and interfaces — no Spring Boot magic
- Users create `AgentConfig`, `Agent`, stores, hooks, and tools explicitly via `@Configuration`

## [0.2.3] - 2026-04-01

### Added
- Auto-generated `parameterSchema()` from record types — no more manual JSON schema
- `@Description` annotation for tool parameter fields
- Package structure documentation in README with import paths on all code examples

### Changed
- `AgentTool.parameterSchema()` now has a default implementation that uses reflection
- All record components are marked as required by default in generated schema

## [0.2.2] - 2026-04-01

### Added
- `HookContext` record — carries `agentId`, `conversationId`, and `attributes` to all hooks
- All `AgentHooks` methods now receive `HookContext` as first parameter
- Hooks can now access tenant/workspace context for dynamic prompt injection, scoped approvals, etc.

### Changed
- `beforeToolCall(String toolName, ToolUse toolUse)` → `beforeToolCall(HookContext ctx, String toolName, ToolUse toolUse)`
- `afterToolCall(String toolName, ToolUse toolUse, result)` → `afterToolCall(HookContext ctx, String toolName, ToolUse toolUse, result)`
- `transformContext(List<AgentMessage> messages)` → `transformContext(HookContext ctx, List<AgentMessage> messages)`
- `convertToLlm(List<AgentMessage> messages)` → `convertToLlm(HookContext ctx, List<AgentMessage> messages)`

## [0.2.1] - 2026-04-01

### Added
- `ToolCallContext` now includes `agentId`, `conversationId`, and `attributes` for multi-tenant tool context
- `AgentRequest.attributes` for passing custom request-scoped data (e.g., workspaceId)
- `HookContext` passed to all `AgentHooks` methods (`beforeToolCall`, `afterToolCall`, `transformContext`, `convertToLlm`)
- `AgentEventType` enum for type-safe event comparison

### Changed
- `AgentHooks` methods now receive `HookContext` as first parameter
- `Agent` redesigned as a stateless client — single `stream(AgentRequest)` method returning `Flux<AgentEvent>`
- `agentId` is always required (no default) — typically from authenticated user
- Removed `agent.id` from YAML configuration
- Renamed `channelId` to `conversationId` throughout
- Renamed `ChannelState` to `ConversationState`, `ChannelManager` to `ConversationManager`
- `ConversationStore` methods now accept `(agentId, conversationId)` instead of single key
- `SchedulePayload` actions now include both `agentId` and `conversationId`
- `StreamProxyController` request now requires `agentId`

### Removed
- All in-memory store implementations (`InMemoryConversationStore`, `InMemoryTimelineStore`, `InMemoryMemoryStore`, `InMemoryScheduleStore`, `InMemoryScheduleExecutor`)
- In-memory scheduling provider (`provider: memory`)
- Stateful agent internals: `ConversationState`, `ConversationManager`, `AgentState`, `EventEmitter`, `AgentEventListener`, `MessageQueue`, `AgentStatus`
- `subscribe()`, `events()`, `abort()`, `steer()`, `followUp()`, `waitForIdle()`, `reset()`, `getMessages()`, `getStatus()`, `listConversations()`, `deleteConversation()` methods from `Agent`
- `AgentConfig.agentId` field

## [0.2.0] - 2026-04-01

### Added
- Dynamic per-conversation `agentId` (hierarchy: agentId > conversationId)
- Multi-tenant support — each user gets their own agent identity
- `@Qualifier("anthropicChatModel")` for ChatModel bean resolution

### Changed
- Renamed `channel` to `conversation` throughout codebase (22 files)
- `ConversationStore` methods now accept `(agentId, conversationId)`
- Repository renamed from `pi-mono-java` to `spring-agent`

### Fixed
- `AnthropicApi.ThinkingType` removed in Spring AI 2.0.0-M4 — replaced with `thinkingEnabled()`

## [0.1.0] - 2026-04-01

### Added
- Initial release
- Stateful multi-turn conversations with streaming LLM responses (Anthropic Claude)
- Tool execution via `AgentTool<P, D>` with parallel or sequential execution
- Agent loop: inner loop (LLM → tool calls → recurse) + outer loop (follow-up queue drain)
- Event system with 11 lifecycle event types via sealed interface
- Activity timeline with auto-recording from agent events
- Conversation compaction via LLM-based summarization
- Event scheduling with in-memory, AWS (EventBridge + SQS + DynamoDB), and JDBC backends
- Agent memory (cross-conversation knowledge store)
- SSE proxy endpoint
- Spring Boot auto-configuration
