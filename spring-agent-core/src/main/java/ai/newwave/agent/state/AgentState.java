package ai.newwave.agent.state;

import ai.newwave.agent.config.AgentConfig;
import ai.newwave.agent.core.ChannelManager;

/**
 * Shared agent state. Holds the immutable config and the channel manager.
 * Per-channel state (messages, status, queues) lives in {@link ChannelState}.
 */
public class AgentState {

    private final AgentConfig config;
    private final ChannelManager channelManager;

    public AgentState(AgentConfig config, ChannelManager channelManager) {
        this.config = config;
        this.channelManager = channelManager;
    }

    public AgentConfig getConfig() {
        return config;
    }

    public ChannelManager getChannelManager() {
        return channelManager;
    }
}
