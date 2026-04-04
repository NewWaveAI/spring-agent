package ai.newwave.agent.core;

import ai.newwave.agent.config.AgentConfig;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Strategy for building LLM prompts from conversation messages.
 * Provider-specific implementations handle model options, thinking, caching, etc.
 */
public interface PromptBuilder {

    /**
     * Build a prompt from the system message, conversation messages, and tool callbacks.
     *
     * @param messages       Conversation messages (already converted to Spring AI format)
     * @param config         Agent configuration (model, thinking level, system prompt, etc.)
     * @param toolCallbacks  Tool callbacks registered with the agent
     * @return A prompt ready to send to the ChatModel
     */
    Prompt buildPrompt(List<Message> messages, AgentConfig config, List<ToolCallback> toolCallbacks);
}
