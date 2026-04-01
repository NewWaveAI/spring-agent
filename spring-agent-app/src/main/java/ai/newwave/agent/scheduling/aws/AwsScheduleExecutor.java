package ai.newwave.agent.scheduling.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.newwave.agent.scheduling.model.ScheduleType;
import ai.newwave.agent.scheduling.model.ScheduledEvent;
import ai.newwave.agent.scheduling.spi.ScheduleExecutor;
import ai.newwave.agent.scheduling.spi.ScheduleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.*;

/**
 * AWS EventBridge Scheduler implementation.
 * Creates EventBridge schedules that deliver messages to an SQS queue.
 * The SqsScheduleListener picks up the messages and dispatches them.
 */
public class AwsScheduleExecutor implements ScheduleExecutor {

    private static final Logger log = LoggerFactory.getLogger(AwsScheduleExecutor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SchedulerClient schedulerClient;
    private final ScheduleStore store;
    private final String sqsTargetArn;
    private final String roleArn;
    private final String scheduleGroupName;

    public AwsScheduleExecutor(
            SchedulerClient schedulerClient,
            ScheduleStore store,
            String sqsTargetArn,
            String roleArn,
            String scheduleGroupName
    ) {
        this.schedulerClient = schedulerClient;
        this.store = store;
        this.sqsTargetArn = sqsTargetArn;
        this.roleArn = roleArn;
        this.scheduleGroupName = scheduleGroupName;
    }

    @Override
    public Mono<String> schedule(ScheduledEvent event) {
        return store.save(event).flatMap(saved -> Mono.fromCallable(() -> {
            String expression = toAwsExpression(saved);
            String payload;
            try {
                payload = objectMapper.writeValueAsString(
                        new SqsScheduleMessage(saved.id(), saved.type().name()));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize schedule message", e);
            }

            Target target = Target.builder()
                    .arn(sqsTargetArn)
                    .roleArn(roleArn)
                    .input(payload)
                    .build();

            CreateScheduleRequest.Builder requestBuilder = CreateScheduleRequest.builder()
                    .name(toScheduleName(saved.id()))
                    .groupName(scheduleGroupName)
                    .scheduleExpression(expression)
                    .scheduleExpressionTimezone(saved.timezone())
                    .target(target)
                    .flexibleTimeWindow(FlexibleTimeWindow.builder()
                            .mode(FlexibleTimeWindowMode.OFF)
                            .build());

            // One-shot and immediate events should auto-delete after firing
            if (saved.type() == ScheduleType.ONE_SHOT || saved.type() == ScheduleType.IMMEDIATE) {
                requestBuilder.actionAfterCompletion(ActionAfterCompletion.DELETE);
            }

            schedulerClient.createSchedule(requestBuilder.build());
            log.info("Created EventBridge schedule '{}' with expression '{}'",
                    toScheduleName(saved.id()), expression);

            return saved.id();
        }));
    }

    @Override
    public Mono<Void> cancel(String eventId) {
        return Mono.fromRunnable(() -> {
            try {
                schedulerClient.deleteSchedule(DeleteScheduleRequest.builder()
                        .name(toScheduleName(eventId))
                        .groupName(scheduleGroupName)
                        .build());
                log.info("Deleted EventBridge schedule '{}'", toScheduleName(eventId));
            } catch (ResourceNotFoundException e) {
                log.warn("Schedule '{}' not found in EventBridge", toScheduleName(eventId));
            }
        }).then(store.delete(eventId));
    }

    @Override
    public Mono<Void> pause(String eventId) {
        return Mono.fromRunnable(() -> {
            try {
                // Get existing schedule to preserve its config
                GetScheduleResponse existing = schedulerClient.getSchedule(GetScheduleRequest.builder()
                        .name(toScheduleName(eventId))
                        .groupName(scheduleGroupName)
                        .build());

                schedulerClient.updateSchedule(UpdateScheduleRequest.builder()
                        .name(toScheduleName(eventId))
                        .groupName(scheduleGroupName)
                        .scheduleExpression(existing.scheduleExpression())
                        .scheduleExpressionTimezone(existing.scheduleExpressionTimezone())
                        .target(existing.target())
                        .flexibleTimeWindow(existing.flexibleTimeWindow())
                        .state(ScheduleState.DISABLED)
                        .build());
                log.info("Paused EventBridge schedule '{}'", toScheduleName(eventId));
            } catch (ResourceNotFoundException e) {
                log.warn("Schedule '{}' not found in EventBridge", toScheduleName(eventId));
            }
        });
    }

    @Override
    public Mono<Void> resume(String eventId) {
        return Mono.fromRunnable(() -> {
            try {
                GetScheduleResponse existing = schedulerClient.getSchedule(GetScheduleRequest.builder()
                        .name(toScheduleName(eventId))
                        .groupName(scheduleGroupName)
                        .build());

                schedulerClient.updateSchedule(UpdateScheduleRequest.builder()
                        .name(toScheduleName(eventId))
                        .groupName(scheduleGroupName)
                        .scheduleExpression(existing.scheduleExpression())
                        .scheduleExpressionTimezone(existing.scheduleExpressionTimezone())
                        .target(existing.target())
                        .flexibleTimeWindow(existing.flexibleTimeWindow())
                        .state(ScheduleState.ENABLED)
                        .build());
                log.info("Resumed EventBridge schedule '{}'", toScheduleName(eventId));
            } catch (ResourceNotFoundException e) {
                log.warn("Schedule '{}' not found in EventBridge", toScheduleName(eventId));
            }
        });
    }

    @Override
    public Flux<ScheduledEvent> listActive() {
        return Flux.defer(() -> {
            ListSchedulesResponse response = schedulerClient.listSchedules(ListSchedulesRequest.builder()
                    .groupName(scheduleGroupName)
                    .state(ScheduleState.ENABLED)
                    .build());
            return Flux.fromIterable(response.schedules())
                    .map(s -> s.name().replace("spring-agent-", ""))
                    .flatMap(store::findById);
        });
    }

    // --- Helpers ---

    private String toAwsExpression(ScheduledEvent event) {
        return switch (event.type()) {
            case IMMEDIATE -> "at(" + java.time.Instant.now().plusSeconds(1).toString()
                    .replace("Z", "") + ")";
            case ONE_SHOT -> "at(" + event.scheduleExpression().replace("Z", "") + ")";
            case PERIODIC -> {
                String expr = event.scheduleExpression();
                // If it's already in AWS cron/rate format, use as-is
                if (expr.startsWith("cron(") || expr.startsWith("rate(")) {
                    yield expr;
                }
                // Try ISO duration -> rate
                try {
                    java.time.Duration d = java.time.Duration.parse(expr);
                    if (d.toMinutes() > 0) yield "rate(%d minutes)".formatted(d.toMinutes());
                    yield "rate(%d seconds)".formatted(d.toSeconds());
                } catch (Exception e) {
                    // Assume it's a cron expression
                    yield "cron(" + expr + ")";
                }
            }
        };
    }

    private String toScheduleName(String eventId) {
        // EventBridge schedule names: alphanumeric, hyphens, underscores
        return "spring-agent-" + eventId.replace("/", "-");
    }

    /**
     * Message sent to SQS when a schedule fires.
     */
    public record SqsScheduleMessage(String eventId, String scheduleType) {
    }
}
