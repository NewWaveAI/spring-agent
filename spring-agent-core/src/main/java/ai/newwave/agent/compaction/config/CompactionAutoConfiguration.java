package ai.newwave.agent.compaction.config;

import ai.newwave.agent.compaction.CompactionHook;
import ai.newwave.agent.compaction.LlmCompactionStrategy;
import ai.newwave.agent.compaction.SimpleTokenEstimator;
import ai.newwave.agent.compaction.model.CompactionConfig;
import ai.newwave.agent.compaction.spi.CompactionStrategy;
import ai.newwave.agent.compaction.spi.TokenEstimator;
import ai.newwave.agent.config.AgentHooks;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "agent.compaction", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CompactionProperties.class)
public class CompactionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TokenEstimator tokenEstimator() {
        return new SimpleTokenEstimator();
    }

    @Bean
    @ConditionalOnMissingBean
    public CompactionConfig compactionConfig(CompactionProperties props) {
        return CompactionConfig.builder()
                .maxContextTokens(props.getMaxContextTokens())
                .preserveRecentCount(props.getPreserveRecentCount())
                .maxSummaryTokens(props.getMaxSummaryTokens())
                .preserveToolResults(props.isPreserveToolResults())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public CompactionStrategy compactionStrategy(ChatModel chatModel, TokenEstimator tokenEstimator) {
        return new LlmCompactionStrategy(chatModel, tokenEstimator);
    }

    @Bean
    public AgentHooks compactionHook(CompactionStrategy strategy, CompactionConfig config, TokenEstimator tokenEstimator) {
        return new CompactionHook(strategy, config, tokenEstimator);
    }
}
