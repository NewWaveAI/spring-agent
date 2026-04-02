package ai.newwave.agent.scheduling.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.newwave.agent.scheduling.ScheduleDispatcher;
import ai.newwave.agent.scheduling.model.ScheduleType;
import ai.newwave.agent.scheduling.model.ScheduledEvent;
import ai.newwave.agent.scheduling.spi.ScheduleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls SQS for schedule-fired messages from EventBridge.
 * When a message arrives, looks up the ScheduledEvent in the store,
 * acquires a distributed lock, and dispatches to the agent.
 */
public class SqsScheduleListener {

    private static final Logger log = LoggerFactory.getLogger(SqsScheduleListener.class);
    private static final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final ScheduleStore store;
    private final ScheduleDispatcher dispatcher;
    private final String instanceId = UUID.randomUUID().toString();
    private final Duration lockTtl;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SqsScheduleListener(
            SqsClient sqsClient,
            String queueUrl,
            ScheduleStore store,
            ScheduleDispatcher dispatcher,
            Duration lockTtl
    ) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
        this.store = store;
        this.dispatcher = dispatcher;
        this.lockTtl = lockTtl;
    }

    /**
     * Start polling SQS for schedule messages.
     */
    public void start(Duration pollInterval) {
        if (running.compareAndSet(false, true)) {
            poller.scheduleAtFixedRate(this::poll, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
            log.info("SQS schedule listener started (queue: {}, poll interval: {})", queueUrl, pollInterval);
        }
    }

    /**
     * Stop polling.
     */
    public void stop() {
        running.set(false);
        poller.shutdown();
        log.info("SQS schedule listener stopped");
    }

    private void poll() {
        try {
            ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(5)
                    .build();

            List<Message> messages = sqsClient.receiveMessage(request).messages();
            for (Message message : messages) {
                processMessage(message);
            }
        } catch (Exception e) {
            log.error("Error polling SQS", e);
        }
    }

    private void processMessage(Message message) {
        try {
            AwsScheduleExecutor.SqsScheduleMessage scheduleMsg =
                    objectMapper.readValue(message.body(), AwsScheduleExecutor.SqsScheduleMessage.class);

            String eventId = scheduleMsg.eventId();
            log.info("Received schedule message for event: {}", eventId);

            // Try to acquire lock (prevents duplicate processing across instances)
            Boolean locked = store.tryAcquireLock(eventId, instanceId, lockTtl).block();
            if (locked == null || !locked) {
                log.debug("Could not acquire lock for event {} — another instance is handling it", eventId);
                return;
            }

            try {
                // Look up the full event
                ScheduledEvent event = store.findById(eventId).block();
                if (event == null) {
                    log.warn("Schedule event {} not found in store — may have been cancelled", eventId);
                    return;
                }

                // Dispatch to agent
                dispatcher.dispatch(event).block();

                // Clean up one-shot/immediate events from the store
                if (event.type() == ScheduleType.IMMEDIATE || event.type() == ScheduleType.ONE_SHOT) {
                    store.delete(eventId).block();
                }
            } finally {
                store.releaseLock(eventId, instanceId).block();
            }

            // Delete the SQS message (acknowledge processing)
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());

        } catch (Exception e) {
            log.error("Failed to process schedule message: {}", message.messageId(), e);
            // Don't delete the message — SQS will redeliver after visibility timeout
        }
    }
}
