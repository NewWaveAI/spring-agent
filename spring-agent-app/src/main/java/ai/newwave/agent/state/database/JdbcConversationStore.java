package ai.newwave.agent.state.database;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.model.MessageRole;
import ai.newwave.agent.state.spi.ConversationStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.util.List;

/**
 * JDBC-backed conversation store.
 *
 * Required table:
 * <pre>
 * CREATE TABLE conversation_messages (
 *     id VARCHAR(255) PRIMARY KEY,
 *     channel_id VARCHAR(255) NOT NULL,
 *     role VARCHAR(50) NOT NULL,
 *     content TEXT NOT NULL,
 *     timestamp TIMESTAMP NOT NULL,
 *     sequence INT NOT NULL
 * );
 * CREATE INDEX idx_conv_channel ON conversation_messages (channel_id, sequence);
 * </pre>
 */
public class JdbcConversationStore implements ConversationStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbcConversationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Mono<Void> appendMessage(String channelId, AgentMessage message) {
        return Mono.fromRunnable(() -> {
            int sequence = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sequence), -1) + 1 FROM conversation_messages WHERE channel_id = ?",
                    Integer.class, channelId);
            jdbc.update(
                    "INSERT INTO conversation_messages (id, channel_id, role, content, timestamp, sequence) VALUES (?, ?, ?, ?, ?, ?)",
                    message.id(), channelId, message.role().name(),
                    serialize(message.content()),
                    Timestamp.from(message.timestamp()), sequence);
        });
    }

    @Override
    public Flux<AgentMessage> loadMessages(String channelId) {
        return Flux.defer(() -> {
            List<AgentMessage> messages = jdbc.query(
                    "SELECT id, role, content, timestamp FROM conversation_messages WHERE channel_id = ? ORDER BY sequence",
                    (rs, rowNum) -> new AgentMessage(
                            rs.getString("id"),
                            MessageRole.valueOf(rs.getString("role")),
                            deserialize(rs.getString("content")),
                            rs.getTimestamp("timestamp").toInstant()),
                    channelId);
            return Flux.fromIterable(messages);
        });
    }

    @Override
    public Mono<Void> replaceMessages(String channelId, List<AgentMessage> messages) {
        return Mono.fromRunnable(() -> {
            jdbc.update("DELETE FROM conversation_messages WHERE channel_id = ?", channelId);
            for (int i = 0; i < messages.size(); i++) {
                AgentMessage msg = messages.get(i);
                jdbc.update(
                        "INSERT INTO conversation_messages (id, channel_id, role, content, timestamp, sequence) VALUES (?, ?, ?, ?, ?, ?)",
                        msg.id(), channelId, msg.role().name(),
                        serialize(msg.content()),
                        Timestamp.from(msg.timestamp()), i);
            }
        });
    }

    @Override
    public Mono<Void> deleteChannel(String channelId) {
        return Mono.fromRunnable(() ->
                jdbc.update("DELETE FROM conversation_messages WHERE channel_id = ?", channelId));
    }

    @Override
    public Flux<String> listChannelIds() {
        return Flux.defer(() -> {
            List<String> ids = jdbc.queryForList(
                    "SELECT DISTINCT channel_id FROM conversation_messages", String.class);
            return Flux.fromIterable(ids);
        });
    }

    private String serialize(List<ContentBlock> content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize content blocks", e);
        }
    }

    private List<ContentBlock> deserialize(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize content blocks", e);
        }
    }
}
