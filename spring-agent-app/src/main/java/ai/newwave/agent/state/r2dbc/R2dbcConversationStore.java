package ai.newwave.agent.state.r2dbc;

import ai.newwave.agent.model.AgentMessage;
import ai.newwave.agent.model.ContentBlock;
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
 *
 * Required table:
 * <pre>
 * CREATE TABLE conversation_messages (
 *     id VARCHAR(255) PRIMARY KEY,
 *     agent_id VARCHAR(255) NOT NULL,
 *     conversation_id VARCHAR(255) NOT NULL,
 *     role VARCHAR(50) NOT NULL,
 *     content TEXT NOT NULL,
 *     timestamp TIMESTAMP NOT NULL,
 *     sequence INT NOT NULL
 * );
 * CREATE INDEX idx_conv_agent_conversation ON conversation_messages (agent_id, conversation_id, sequence);
 * </pre>
 */
public class R2dbcConversationStore implements ConversationStore {

    private final DatabaseClient db;

    public R2dbcConversationStore(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Void> appendMessage(String agentId, String conversationId, AgentMessage message) {
        return db.sql("SELECT COALESCE(MAX(sequence), -1) + 1 FROM conversation_messages WHERE agent_id = :agentId AND conversation_id = :conversationId")
                .bind("agentId", agentId)
                .bind("conversationId", conversationId)
                .map(row -> row.get(0, Integer.class))
                .one()
                .defaultIfEmpty(0)
                .flatMap(sequence -> db.sql("INSERT INTO conversation_messages (id, agent_id, conversation_id, role, content, timestamp, sequence) VALUES (:id, :agentId, :conversationId, :role, :content, :timestamp, :sequence)")
                        .bind("id", message.id())
                        .bind("agentId", agentId)
                        .bind("conversationId", conversationId)
                        .bind("role", message.role().name())
                        .bind("content", Json.serializeContentBlocks(message.content()))
                        .bind("timestamp", message.timestamp())
                        .bind("sequence", sequence)
                        .then());
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
                .thenMany(Flux.range(0, messages.size())
                        .flatMap(i -> {
                            AgentMessage msg = messages.get(i);
                            return db.sql("INSERT INTO conversation_messages (id, agent_id, conversation_id, role, content, timestamp, sequence) VALUES (:id, :agentId, :conversationId, :role, :content, :timestamp, :sequence)")
                                    .bind("id", msg.id())
                                    .bind("agentId", agentId)
                                    .bind("conversationId", conversationId)
                                    .bind("role", msg.role().name())
                                    .bind("content", Json.serializeContentBlocks(msg.content()))
                                    .bind("timestamp", msg.timestamp())
                                    .bind("sequence", i)
                                    .then();
                        }))
                .then();
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
