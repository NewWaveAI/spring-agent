package ai.newwave.agent.memory.config;

import ai.newwave.agent.config.AgentHooks;
import ai.newwave.agent.memory.MemoryContextHook;
import ai.newwave.agent.memory.MemoryService;
import ai.newwave.agent.memory.memory.InMemoryMemoryStore;
import ai.newwave.agent.memory.spi.MemoryStore;
import ai.newwave.agent.memory.tool.SaveMemoryTool;
import ai.newwave.agent.memory.tool.SearchMemoryTool;
import ai.newwave.agent.tool.AgentTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "agent.memory", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MemoryStore memoryStore() {
        return new InMemoryMemoryStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryService memoryService(MemoryStore store) {
        return new MemoryService(store);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory", name = "context-injection-enabled", havingValue = "true", matchIfMissing = true)
    public AgentHooks memoryContextHook(MemoryService memoryService) {
        return new MemoryContextHook(memoryService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory", name = "save-tool-enabled", havingValue = "true", matchIfMissing = true)
    public AgentTool<?, ?> saveMemoryTool(MemoryService memoryService) {
        return new SaveMemoryTool(memoryService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory", name = "search-tool-enabled", havingValue = "true", matchIfMissing = true)
    public AgentTool<?, ?> searchMemoryTool(MemoryService memoryService) {
        return new SearchMemoryTool(memoryService);
    }
}
