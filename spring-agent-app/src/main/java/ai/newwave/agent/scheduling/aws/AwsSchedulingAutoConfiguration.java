package ai.newwave.agent.scheduling.aws;

import ai.newwave.agent.core.Agent;
import ai.newwave.agent.scheduling.ScheduleDispatcher;
import ai.newwave.agent.scheduling.ScheduleService;
import ai.newwave.agent.scheduling.spi.ScheduleExecutor;
import ai.newwave.agent.scheduling.spi.ScheduleStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(prefix = "agent.scheduling", name = "provider", havingValue = "aws")
@ConditionalOnClass({SchedulerClient.class, SqsClient.class, DynamoDbClient.class})
public class AwsSchedulingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SchedulerClient schedulerClient() {
        return SchedulerClient.create();
    }

    @Bean
    @ConditionalOnMissingBean
    public SqsClient sqsClient() {
        return SqsClient.create();
    }

    @Bean
    @ConditionalOnMissingBean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.create();
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleStore scheduleStore(
            DynamoDbClient dynamoDbClient,
            @Value("${agent.scheduling.aws.dynamodb-table:spring-agent-schedules}") String tableName
    ) {
        return new AwsScheduleStore(dynamoDbClient, tableName);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleDispatcher scheduleDispatcher(Agent agent) {
        return new ScheduleDispatcher(agent);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleExecutor scheduleExecutor(
            SchedulerClient schedulerClient,
            ScheduleStore store,
            @Value("${agent.scheduling.aws.sqs-target-arn}") String sqsTargetArn,
            @Value("${agent.scheduling.aws.role-arn}") String roleArn,
            @Value("${agent.scheduling.aws.schedule-group:spring-agent}") String scheduleGroup
    ) {
        return new AwsScheduleExecutor(schedulerClient, store, sqsTargetArn, roleArn, scheduleGroup);
    }

    @Bean
    public SqsScheduleListener sqsScheduleListener(
            SqsClient sqsClient,
            ScheduleStore store,
            ScheduleDispatcher dispatcher,
            @Value("${agent.scheduling.aws.sqs-queue-url}") String queueUrl,
            @Value("${agent.scheduling.aws.lock-ttl:PT30S}") String lockTtl,
            @Value("${agent.scheduling.aws.poll-interval:PT5S}") String pollInterval
    ) {
        SqsScheduleListener listener = new SqsScheduleListener(
                sqsClient, queueUrl, store, dispatcher, Duration.parse(lockTtl));
        listener.start(Duration.parse(pollInterval));
        return listener;
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleService scheduleService(ScheduleExecutor executor) {
        return new ScheduleService(executor);
    }
}
