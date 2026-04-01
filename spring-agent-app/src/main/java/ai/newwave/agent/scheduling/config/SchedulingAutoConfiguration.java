package ai.newwave.agent.scheduling.config;

import ai.newwave.agent.core.Agent;
import ai.newwave.agent.scheduling.ScheduleDispatcher;
import ai.newwave.agent.scheduling.ScheduleService;
import ai.newwave.agent.scheduling.memory.InMemoryScheduleExecutor;
import ai.newwave.agent.scheduling.memory.InMemoryScheduleStore;
import ai.newwave.agent.scheduling.spi.ScheduleExecutor;
import ai.newwave.agent.scheduling.spi.ScheduleStore;
import ai.newwave.agent.scheduling.tool.ScheduleQueryTool;
import ai.newwave.agent.tool.AgentTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "agent.scheduling", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SchedulingProperties.class)
public class SchedulingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ScheduleStore scheduleStore() {
        return new InMemoryScheduleStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleDispatcher scheduleDispatcher(Agent agent) {
        return new ScheduleDispatcher(agent, agent.getEmitter());
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleExecutor scheduleExecutor(ScheduleStore store, ScheduleDispatcher dispatcher) {
        return new InMemoryScheduleExecutor(store, dispatcher);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleService scheduleService(ScheduleExecutor executor) {
        return new ScheduleService(executor);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.scheduling", name = "query-tool-enabled", havingValue = "true", matchIfMissing = true)
    public AgentTool<?, ?> scheduleQueryTool(ScheduleService scheduleService) {
        return new ScheduleQueryTool(scheduleService);
    }
}
