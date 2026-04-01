package ai.newwave.agent.state;

import ai.newwave.agent.model.AgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe message queue for steering and follow-up messages.
 */
public class MessageQueue {

    private final ConcurrentLinkedQueue<AgentMessage> queue = new ConcurrentLinkedQueue<>();

    public void add(AgentMessage message) {
        queue.add(message);
    }

    public void add(String text) {
        queue.add(AgentMessage.user(text));
    }

    public AgentMessage poll() {
        return queue.poll();
    }

    /**
     * Drain all messages from the queue.
     */
    public List<AgentMessage> drainAll() {
        List<AgentMessage> messages = new ArrayList<>();
        AgentMessage msg;
        while ((msg = queue.poll()) != null) {
            messages.add(msg);
        }
        return messages;
    }

    public boolean hasMessages() {
        return !queue.isEmpty();
    }

    public void clear() {
        queue.clear();
    }

    public int size() {
        return queue.size();
    }
}
