package ai.newwave.agent.state.r2dbc;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.MessageRole;
import ai.newwave.agent.state.spi.ConversationStore;
import ai.newwave.agent.util.Json;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * R2DBC-backed conversation store. Fully non-blocking.
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
public class R2dbcConversationStore implements ConversationStore {

    private static final String INSERT_COLUMNS =
            "INSERT INTO conversation_messages (id, agent_id, conversation_id, role, content, timestamp) VALUES ";

    private final DatabaseClient db;

    public R2dbcConversationStore(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Void> appendMessage(String agentId, String conversationId, AgentMessage message) {
        return appendMessages(agentId, conversationId, List.of(message));
    }

    @Override
    public Mono<Void> appendMessages(String agentId, String conversationId, List<AgentMessage> messages) {
        if (messages.isEmpty()) return Mono.empty();

        // Build a single multi-row INSERT: VALUES (:id0, :agentId0, ...), (:id1, :agentId1, ...), ...
        // DatabaseClient has no native batch API, so this is the portable way to get one wire
        // round-trip while keeping :name parameter style (driver-agnostic). Ordering is preserved
        // because the row tuples are emitted in list iteration order, so IDENTITY assigns
        // sequence values in the same order.
        StringBuilder sql = new StringBuilder(INSERT_COLUMNS);
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("(:id").append(i)
                    .append(", :agentId").append(i)
                    .append(", :conversationId").append(i)
                    .append(", :role").append(i)
                    .append(", :content").append(i)
                    .append(", :timestamp").append(i)
                    .append(')');
        }

        DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString());
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage m = messages.get(i);
            spec = spec
                    .bind("id" + i, m.id())
                    .bind("agentId" + i, agentId)
                    .bind("conversationId" + i, conversationId)
                    .bind("role" + i, m.role().name())
                    .bind("content" + i, Json.serializeContentBlocks(m.content()))
                    .bind("timestamp" + i, m.timestamp());
        }
        return spec.then();
    }

    @Override
    public Flux<AgentMessage> loadMessages(String agentId, String conversationId) {
        return db.sql("SELECT id, role, content, timestamp FROM conversation_messages WHERE agent_id = :agentId AND conversation_id = :conversationId ORDER BY sequence")
                .bind("agentId", agentId)
                .bind("conversationId", conversationId)
                .map(row -> new AgentMessage(
                        row.get("id", String.class),
                        MessageRole.valueOf(row.get("role", String.class)),
                        Json.deserializeContentBlocks(row.get("content", String.class)),
                        row.get("timestamp", Instant.class)))
                .all();
    }

    @Override
    public Mono<Void> replaceMessages(String agentId, String conversationId, List<AgentMessage> messages) {
        return db.sql("DELETE FROM conversation_messages WHERE agent_id = :agentId AND conversation_id = :conversationId")
                .bind("agentId", agentId)
                .bind("conversationId", conversationId)
                .then()
                .then(appendMessages(agentId, conversationId, messages));
    }

    @Override
    public Mono<Void> deleteConversation(String agentId, String conversationId) {
        return db.sql("DELETE FROM conversation_messages WHERE agent_id = :agentId AND conversation_id = :conversationId")
                .bind("agentId", agentId)
                .bind("conversationId", conversationId)
                .then();
    }

    @Override
    public Flux<String> listConversationIds(String agentId) {
        return db.sql("SELECT DISTINCT conversation_id FROM conversation_messages WHERE agent_id = :agentId")
                .bind("agentId", agentId)
                .map(row -> row.get("conversation_id", String.class))
                .all();
    }
}
