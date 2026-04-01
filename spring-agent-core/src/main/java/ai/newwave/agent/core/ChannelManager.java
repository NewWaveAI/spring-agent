package ai.newwave.agent.core;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.state.ChannelState;
import ai.newwave.agent.state.spi.ConversationStore;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages channel lifecycle. Channels are created on-demand and
 * hydrated from the ConversationStore on first access.
 */
public class ChannelManager {

    private final ConcurrentHashMap<String, ChannelState> channels = new ConcurrentHashMap<>();
    private final ConversationStore conversationStore;

    public ChannelManager(ConversationStore conversationStore) {
        this.conversationStore = conversationStore;
    }

    /**
     * Get or create a channel. On first access, loads existing messages
     * from the conversation store.
     */
    public ChannelState getOrCreate(String channelId) {
        return channels.computeIfAbsent(channelId, id -> {
            List<AgentMessage> existing = conversationStore.loadMessages(id)
                    .collectList()
                    .block();
            return new ChannelState(id, existing);
        });
    }

    /**
     * Get a channel if it exists in memory.
     */
    public ChannelState get(String channelId) {
        return channels.get(channelId);
    }

    /**
     * Archive a channel: persist current state and remove from memory.
     */
    public void archive(String channelId) {
        ChannelState channel = channels.remove(channelId);
        if (channel != null) {
            channel.reset();
        }
    }

    /**
     * Delete a channel entirely (memory + store).
     */
    public void delete(String channelId) {
        ChannelState channel = channels.remove(channelId);
        if (channel != null) {
            channel.reset();
        }
        conversationStore.deleteChannel(channelId).block();
    }

    /**
     * List all active (in-memory) channel IDs.
     */
    public List<String> listActive() {
        return List.copyOf(channels.keySet());
    }

    public ConversationStore getConversationStore() {
        return conversationStore;
    }
}
