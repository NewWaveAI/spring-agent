package ai.newwave.agent.state.database;

import ai.newwave.agent.util.Json;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
import ai.newwave.agent.model.MessageRole;
import ai.newwave.agent.state.spi.ConversationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.util.List;

/**
 * JDBC-backed conversation store.
 * <p>
 * Required table:
 * <pre>
 * CREATE TABLE conversation_messages (
 *     id VARCHAR(255) PRIMARY KEY,
 *     agent_id VARCHAR(255) NOT NULL,
 *     conversation_id VARCHAR(255) NOT NULL,
 *     role VARCHAR(50) NOT NULL,
 *     content TEXT NOT NULL,
 *     timestamp TIMESTAMP NOT NULL,
 *     sequence BIGINT GENERATED ALWAYS AS IDENTITY
 * );
 * CREATE INDEX idx_conv_agent_conversation ON conversation_messages (agent_id, conversation_id, sequence);
 * </pre>
 *
 * The {@code sequence} column is assigned by the database on insert. Tools running in
 * {@code ToolExecutionMode.PARALLEL} can append concurrently; the DB serializes the assignment,
 * so each row gets a unique, monotonic value. Read order follows insert order via
 * {@code ORDER BY sequence}.
 */
public class JdbcConversationStore implements ConversationStore {

    private final JdbcTemplate jdbc;


    public JdbcConversationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Mono<Void> appendMessage(String agentId, String conversationId, AgentMessage message) {
        return Mono.fromRunnable(() ->
                jdbc.update(
                        "INSERT INTO conversation_messages (id, agent_id, conversation_id, role, content, timestamp) VALUES (?, ?, ?, ?, ?, ?)",
                        message.id(), agentId, conversationId, message.role().name(),
                        serialize(message.content()),
                        Timestamp.from(message.timestamp())));
    }

    @Override
    public Flux<AgentMessage> loadMessages(String agentId, String conversationId) {
        return Flux.defer(() -> {
            List<AgentMessage> messages = jdbc.query(
                    "SELECT id, role, content, timestamp FROM conversation_messages WHERE agent_id = ? AND conversation_id = ? ORDER BY sequence",
                    (rs, rowNum) -> new AgentMessage(
                            rs.getString("id"),
                            MessageRole.valueOf(rs.getString("role")),
                            deserialize(rs.getString("content")),
                            rs.getTimestamp("timestamp").toInstant()),
                    agentId, conversationId);
            return Flux.fromIterable(messages);
        });
    }

    @Override
    public Mono<Void> replaceMessages(String agentId, String conversationId, List<AgentMessage> messages) {
        return Mono.fromRunnable(() -> {
            jdbc.update("DELETE FROM conversation_messages WHERE agent_id = ? AND conversation_id = ?", agentId, conversationId);
            for (AgentMessage msg : messages) {
                jdbc.update(
                        "INSERT INTO conversation_messages (id, agent_id, conversation_id, role, content, timestamp) VALUES (?, ?, ?, ?, ?, ?)",
                        msg.id(), agentId, conversationId, msg.role().name(),
                        serialize(msg.content()),
                        Timestamp.from(msg.timestamp()));
            }
        });
    }

    @Override
    public Mono<Void> deleteConversation(String agentId, String conversationId) {
        return Mono.fromRunnable(() ->
                jdbc.update("DELETE FROM conversation_messages WHERE agent_id = ? AND conversation_id = ?", agentId, conversationId));
    }

    @Override
    public Flux<String> listConversationIds(String agentId) {
        return Flux.defer(() -> {
            List<String> ids = jdbc.queryForList(
                    "SELECT DISTINCT conversation_id FROM conversation_messages WHERE agent_id = ?", String.class, agentId);
            return Flux.fromIterable(ids);
        });
    }

    private String serialize(List<ContentBlock> content) {
        return Json.serializeContentBlocks(content);
    }

    private List<ContentBlock> deserialize(String json) {
        return Json.deserializeContentBlocks(json);
    }
}
