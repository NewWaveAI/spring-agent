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
 * <p>The {@code sequence} column is assigned by the database on insert. IDENTITY assigns values
 * in <em>commit</em> order, not begin order — concurrent {@link #appendMessage} calls from the
 * same logical turn can therefore land out of logical order. Callers that need ordering (such
 * as the agent loop flushing an assistant message with its tool_results) must use
 * {@link #appendMessages} to submit the batch serially within one caller.
 */
public class JdbcConversationStore implements ConversationStore {

    private static final String INSERT_SQL =
            "INSERT INTO conversation_messages (id, agent_id, conversation_id, role, content, timestamp) VALUES (?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;


    public JdbcConversationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Mono<Void> appendMessage(String agentId, String conversationId, AgentMessage message) {
        return Mono.fromRunnable(() -> jdbc.update(INSERT_SQL, toArgs(agentId, conversationId, message)));
    }

    @Override
    public Mono<Void> appendMessages(String agentId, String conversationId, List<AgentMessage> messages) {
        if (messages.isEmpty()) return Mono.empty();
        List<Object[]> batch = messages.stream()
                .map(m -> toArgs(agentId, conversationId, m))
                .toList();
        return Mono.fromRunnable(() -> jdbc.batchUpdate(INSERT_SQL, batch));
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
            if (messages.isEmpty()) return;
            List<Object[]> batch = messages.stream()
                    .map(m -> toArgs(agentId, conversationId, m))
                    .toList();
            jdbc.batchUpdate(INSERT_SQL, batch);
        });
    }

    private Object[] toArgs(String agentId, String conversationId, AgentMessage message) {
        return new Object[] {
                message.id(),
                agentId,
                conversationId,
                message.role().name(),
                serialize(message.content()),
                Timestamp.from(message.timestamp())
        };
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
