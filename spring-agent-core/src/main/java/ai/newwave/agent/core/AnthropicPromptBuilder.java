package ai.newwave.agent.core;

import ai.newwave.agent.config.AgentConfig;
import ai.newwave.agent.model.ThinkingLevel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds prompts for Anthropic Claude models.
 * Supports extended thinking, prompt caching (system + tools), and tool callbacks.
 */
public class AnthropicPromptBuilder implements PromptBuilder {

    @Override
    public Prompt buildPrompt(List<Message> messages, AgentConfig config, List<ToolCallback> toolCallbacks) {
        List<Message> allMessages = new ArrayList<>();
        allMessages.add(new SystemMessage(config.systemPrompt()));
        allMessages.addAll(messages);

        var optionsBuilder = AnthropicChatOptions.builder()
                .model(config.model())
                .maxTokens(config.maxTokens());

        // Extended thinking
        if (config.thinkingLevel() != ThinkingLevel.OFF) {
            optionsBuilder.thinking(AnthropicApi.ThinkingType.ENABLED, config.thinkingLevel().getBudgetTokens());
            optionsBuilder.temperature(1.0);
        }

        // Prompt caching — cache system prompt and tool definitions
        optionsBuilder.cacheOptions(AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)
                .build());

        // Tool callbacks
        if (!toolCallbacks.isEmpty()) {
            optionsBuilder.toolCallbacks(toolCallbacks);
            optionsBuilder.internalToolExecutionEnabled(false);
        }

        return new Prompt(allMessages, optionsBuilder.build());
    }
}
