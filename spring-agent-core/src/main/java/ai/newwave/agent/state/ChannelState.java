package ai.newwave.agent.state;

import ai.newwave.agent.model.AgentMessage;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

/**
 * Per-channel state container. Each channel has its own message history,
 * status, queues, and active run — fully isolated from other channels.
 */
public class ChannelState {

    private final String channelId;
    private volatile AgentStatus status = AgentStatus.IDLE;
    private volatile String errorMessage;

    private final List<AgentMessage> messages = new CopyOnWriteArrayList<>();
    private final Set<String> pendingToolCalls = Collections.synchronizedSet(new HashSet<>());
    private final MessageQueue steeringQueue = new MessageQueue();
    private final MessageQueue followUpQueue = new MessageQueue();
    private final AtomicReference<Disposable> currentRun = new AtomicReference<>();
    private volatile Sinks.One<Void> idleSink = Sinks.one();

    public ChannelState(String channelId) {
        this.channelId = channelId;
    }

    public ChannelState(String channelId, List<AgentMessage> existingMessages) {
        this.channelId = channelId;
        if (existingMessages != null) {
            this.messages.addAll(existingMessages);
        }
    }

    // --- Identity ---

    public String getChannelId() {
        return channelId;
    }

    // --- Status ---

    public AgentStatus getStatus() {
        return status;
    }

    public void setStatus(AgentStatus status) {
        this.status = status;
    }

    public boolean isRunning() {
        return status == AgentStatus.RUNNING;
    }

    public boolean isAborting() {
        return status == AgentStatus.ABORTING;
    }

    // --- Error ---

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // --- Messages ---

    public List<AgentMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public void addMessage(AgentMessage message) {
        messages.add(message);
    }

    public void setMessages(List<AgentMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
    }

    public void clearMessages() {
        messages.clear();
    }

    // --- Pending Tool Calls ---

    public Set<String> getPendingToolCalls() {
        return Collections.unmodifiableSet(new HashSet<>(pendingToolCalls));
    }

    public void addPendingToolCall(String toolUseId) {
        pendingToolCalls.add(toolUseId);
    }

    public void removePendingToolCall(String toolUseId) {
        pendingToolCalls.remove(toolUseId);
    }

    public void clearPendingToolCalls() {
        pendingToolCalls.clear();
    }

    // --- Queues ---

    public MessageQueue getSteeringQueue() {
        return steeringQueue;
    }

    public MessageQueue getFollowUpQueue() {
        return followUpQueue;
    }

    // --- Run Management ---

    public AtomicReference<Disposable> getCurrentRun() {
        return currentRun;
    }

    public Sinks.One<Void> getIdleSink() {
        return idleSink;
    }

    public void resetIdleSink() {
        this.idleSink = Sinks.one();
    }

    // --- Reset ---

    public void reset() {
        status = AgentStatus.IDLE;
        errorMessage = null;
        messages.clear();
        pendingToolCalls.clear();
        steeringQueue.clear();
        followUpQueue.clear();
        idleSink = Sinks.one();
        Disposable run = currentRun.getAndSet(null);
        if (run != null && !run.isDisposed()) {
            run.dispose();
        }
    }
}
