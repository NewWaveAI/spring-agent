package ai.newwave.agent.scheduling.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ai.newwave.agent.scheduling.ScheduleService;
import ai.newwave.agent.scheduling.model.ScheduleType;
import ai.newwave.agent.scheduling.model.ScheduledEvent;
import ai.newwave.agent.tool.AgentTool;
import ai.newwave.agent.tool.AgentToolResult;
import ai.newwave.agent.tool.ToolCallContext;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent tool for querying and managing schedules.
 */
public class ScheduleQueryTool implements AgentTool<ScheduleQueryParams, List<ScheduledEvent>> {

    private static final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final ScheduleService scheduleService;

    public ScheduleQueryTool(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Override
    public String name() {
        return "query_schedules";
    }

    @Override
    public String label() {
        return "Query Schedules";
    }

    @Override
    public String description() {
        return "Query and manage scheduled events. Actions: 'list' (list active schedules), " +
                "'get' (get a specific schedule by ID), 'cancel' (cancel a schedule by ID).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "Action to perform: 'list', 'get', or 'cancel'");
        action.putArray("enum").add("list").add("get").add("cancel");

        ObjectNode scheduleId = properties.putObject("scheduleId");
        scheduleId.put("type", "string");
        scheduleId.put("description", "Schedule ID (required for 'get' and 'cancel')");

        ObjectNode type = properties.putObject("type");
        type.put("type", "string");
        type.put("description", "Filter by schedule type: 'IMMEDIATE', 'ONE_SHOT', 'PERIODIC'");

        schema.putArray("required").add("action");

        return schema;
    }

    @Override
    public Class<ScheduleQueryParams> parameterType() {
        return ScheduleQueryParams.class;
    }

    @Override
    public Mono<AgentToolResult<List<ScheduledEvent>>> execute(ToolCallContext<ScheduleQueryParams> context) {
        ScheduleQueryParams params = context.parameters();

        return switch (params.action()) {
            case "list" -> scheduleService.listActive()
                    .filter(e -> params.type() == null || e.type() == ScheduleType.valueOf(params.type()))
                    .collectList()
                    .map(events -> {
                        String formatted = events.stream()
                                .map(e -> "[%s] %s | type=%s | expr=%s | next=%s".formatted(
                                        e.id(), describePayload(e), e.type(),
                                        e.scheduleExpression(), e.nextFireTime()))
                                .collect(Collectors.joining("\n"));
                        if (formatted.isEmpty()) formatted = "No active schedules found.";
                        return AgentToolResult.success(formatted, events);
                    });

            case "get" -> {
                if (params.scheduleId() == null) {
                    yield Mono.just(AgentToolResult.<List<ScheduledEvent>>error("scheduleId is required for 'get'"));
                }
                yield scheduleService.listActive()
                        .filter(e -> e.id().equals(params.scheduleId()))
                        .collectList()
                        .map(events -> {
                            if (events.isEmpty()) {
                                return AgentToolResult.<List<ScheduledEvent>>error(
                                        "Schedule not found: " + params.scheduleId());
                            }
                            ScheduledEvent e = events.getFirst();
                            String formatted = "ID: %s\nType: %s\nExpression: %s\nNext fire: %s\nPayload: %s".formatted(
                                    e.id(), e.type(), e.scheduleExpression(), e.nextFireTime(), e.payload());
                            return AgentToolResult.success(formatted, events);
                        });
            }

            case "cancel" -> {
                if (params.scheduleId() == null) {
                    yield Mono.just(AgentToolResult.<List<ScheduledEvent>>error("scheduleId is required for 'cancel'"));
                }
                yield scheduleService.cancel(params.scheduleId())
                        .then(Mono.just(AgentToolResult.<List<ScheduledEvent>>success(
                                "Schedule cancelled: " + params.scheduleId(), List.of())));
            }

            default -> Mono.just(AgentToolResult.error("Unknown action: " + params.action()));
        };
    }

    private String describePayload(ScheduledEvent event) {
        return switch (event.payload()) {
            case ai.newwave.agent.scheduling.model.SchedulePayload.PromptAction a ->
                    "prompt(agent=%s, conversation=%s)".formatted(a.agentId(), a.conversationId());
            case ai.newwave.agent.scheduling.model.SchedulePayload.SteerAction a ->
                    "steer(agent=%s, conversation=%s)".formatted(a.agentId(), a.conversationId());
            case ai.newwave.agent.scheduling.model.SchedulePayload.FollowUpAction a ->
                    "followUp(agent=%s, conversation=%s)".formatted(a.agentId(), a.conversationId());
            case ai.newwave.agent.scheduling.model.SchedulePayload.CustomAction a ->
                    "custom(%s)".formatted(a.actionType());
        };
    }
}
