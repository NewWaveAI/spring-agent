package ai.newwave.agent.config;

import ai.newwave.agent.core.Agent;
import ai.newwave.agent.core.ChannelManager;
import ai.newwave.agent.state.memory.InMemoryConversationStore;
import ai.newwave.agent.state.spi.ConversationStore;
import ai.newwave.agent.tool.AgentTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@ConditionalOnClass(Agent.class)
@EnableConfigurationProperties(AgentProperties.class)
public class AgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AgentHooks agentHooks(List<AgentHooks> hooks) {
        if (hooks.isEmpty()) {
            return new AgentHooks() {};
        }
        if (hooks.size() == 1) {
            return hooks.getFirst();
        }
        return new CompositeAgentHooks(hooks);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationStore conversationStore() {
        return new InMemoryConversationStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelManager channelManager(ConversationStore conversationStore) {
        return new ChannelManager(conversationStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentConfig agentConfig(AgentProperties props, List<AgentTool<?, ?>> tools, AgentHooks hooks) {
        return AgentConfig.builder()
                .agentId(props.getId())
                .systemPrompt(resolvePrompt(props.getSystemPrompt()))
                .thinkingLevel(props.getThinkingLevel())
                .maxTokens(props.getMaxTokens())
                .tools(tools)
                .loopConfig(AgentLoopConfig.builder()
                        .maxTurns(props.getMaxTurns())
                        .toolExecutionMode(props.getToolExecutionMode())
                        .hooks(hooks)
                        .build())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public Agent agent(AgentConfig config, ChatModel chatModel, ChannelManager channelManager) {
        return new Agent(config, chatModel, channelManager);
    }

    private String resolvePrompt(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        ResourceLoader resourceLoader = new DefaultResourceLoader();
        Resource resource = resourceLoader.getResource(value);
        if (resource.exists()) {
            try {
                return resource.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read system prompt resource: " + value, e);
            }
        }
        return value;
    }
}
