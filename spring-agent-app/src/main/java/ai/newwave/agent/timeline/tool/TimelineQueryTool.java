package ai.newwave.agent.timeline.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ai.newwave.agent.timeline.TimelineService;
import ai.newwave.agent.timeline.model.TimelineEvent;
import ai.newwave.agent.timeline.model.TimelineQuery;
import ai.newwave.agent.tool.AgentTool;
import ai.newwave.agent.tool.AgentToolResult;
import ai.newwave.agent.tool.ToolCallContext;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent tool that allows the agent to explicitly query the activity timeline.
 */
public class TimelineQueryTool implements AgentTool<TimelineQueryParams, List<TimelineEvent>> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final TimelineService timelineService;

    public TimelineQueryTool(TimelineService timelineService) {
        this.timelineService = timelineService;
    }

    @Override
    public String name() {
        return "query_timeline";
    }

    @Override
    public String label() {
        return "Query Activity Timeline";
    }

    @Override
    public String description() {
        return "Query the activity timeline to understand what has happened recently. " +
                "Returns a list of events with timestamps, types, and descriptions.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode eventTypes = properties.putObject("eventTypes");
        eventTypes.put("type", "array");
        eventTypes.putObject("items").put("type", "string");
        eventTypes.put("description", "Filter by event types (e.g., 'tool_executed', 'schedule_fired')");

        ObjectNode since = properties.putObject("since");
        since.put("type", "string");
        since.put("description", "ISO-8601 timestamp to filter events from");

        ObjectNode until = properties.putObject("until");
        until.put("type", "string");
        until.put("description", "ISO-8601 timestamp to filter events until");

        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "Maximum number of events to return (default 20)");

        return schema;
    }

    @Override
    public Class<TimelineQueryParams> parameterType() {
        return TimelineQueryParams.class;
    }

    @Override
    public Mono<AgentToolResult<List<TimelineEvent>>> execute(ToolCallContext<TimelineQueryParams> context) {
        TimelineQueryParams params = context.parameters();

        TimelineQuery.Builder queryBuilder = TimelineQuery.builder()
                .limit(params.limit() > 0 ? params.limit() : 20);

        if (params.eventTypes() != null && !params.eventTypes().isEmpty()) {
            queryBuilder.eventTypes(params.eventTypes());
        }
        if (params.since() != null) {
            queryBuilder.since(Instant.parse(params.since()));
        }
        if (params.until() != null) {
            queryBuilder.until(Instant.parse(params.until()));
        }

        return timelineService.query(queryBuilder.build())
                .collectList()
                .map(events -> {
                    String formatted = events.stream()
                            .map(e -> "[%s] %s: %s".formatted(e.timestamp(), e.eventType(), e.summary()))
                            .collect(Collectors.joining("\n"));

                    if (formatted.isEmpty()) {
                        formatted = "No events found matching the query.";
                    }

                    return AgentToolResult.success(formatted, events);
                });
    }
}
