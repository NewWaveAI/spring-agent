package ai.newwave.agent.timeline.config;

import ai.newwave.agent.config.AgentHooks;
import ai.newwave.agent.timeline.TimelineContextHook;
import ai.newwave.agent.timeline.TimelineRecorder;
import ai.newwave.agent.timeline.TimelineService;
import ai.newwave.agent.timeline.spi.TimelineStore;
import ai.newwave.agent.timeline.tool.TimelineQueryTool;
import ai.newwave.agent.tool.AgentTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "agent.timeline", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TimelineProperties.class)
public class TimelineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TimelineService timelineService(TimelineStore store) {
        return new TimelineService(store);
    }

    @Bean
    @ConditionalOnMissingBean
    public TimelineRecorder timelineRecorder(TimelineStore store) {
        return new TimelineRecorder(store);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.timeline", name = "context-injection-enabled", havingValue = "true", matchIfMissing = true)
    public AgentHooks timelineContextHook(TimelineService timelineService, TimelineProperties props) {
        return new TimelineContextHook(timelineService, props.getMaxRecentEventsForContext());
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.timeline", name = "query-tool-enabled", havingValue = "true", matchIfMissing = true)
    public AgentTool<?, ?> timelineQueryTool(TimelineService timelineService) {
        return new TimelineQueryTool(timelineService);
    }
}
