package com.ai.service.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConversationRepository {

    private final JdbcClient jdbcClient;

    public ConversationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<String> findCodexThreadId(String conversationId) {
        return jdbcClient.sql("SELECT codex_thread_id FROM conversations WHERE id = :id")
                .param("id", conversationId)
                .query(String.class)
                .optional();
    }

    @Transactional
    public void create(String conversationId, String codexThreadId) {
        try {
            jdbcClient.sql("""
                            INSERT INTO conversations (id, codex_thread_id)
                            VALUES (:id, :threadId)
                            """)
                    .param("id", conversationId)
                    .param("threadId", codexThreadId)
                    .update();
        } catch (DuplicateKeyException exception) {
            String existingThreadId = findCodexThreadId(conversationId).orElseThrow(() -> exception);
            if (!existingThreadId.equals(codexThreadId)) {
                throw new IllegalStateException("Conversation was concurrently mapped to another Codex thread");
            }
        }
    }

    @Transactional
    public void recordCompletedTurn(String conversationId, String prompt, String answer) {
        int updated = jdbcClient.sql("""
                        UPDATE conversations
                        SET updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        """)
                .param("id", conversationId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Conversation mapping disappeared while recording a turn");
        }

        addMessage(conversationId, "user", prompt);
        addMessage(conversationId, "assistant", answer);
    }

    public List<ConversationMessage> findMessages(String conversationId) {
        return jdbcClient.sql("""
                        SELECT role, content
                        FROM conversation_messages
                        WHERE conversation_id = :conversationId
                        ORDER BY created_at, id
                        """)
                .param("conversationId", conversationId)
                .query((resultSet, rowNumber) -> new ConversationMessage(
                        resultSet.getString("role"), resultSet.getString("content")))
                .list();
    }

    private void addMessage(String conversationId, String role, String content) {
        jdbcClient.sql("""
                        INSERT INTO conversation_messages (conversation_id, role, content)
                        VALUES (:conversationId, :role, :content)
                        """)
                .param("conversationId", conversationId)
                .param("role", role)
                .param("content", content)
                .update();
    }

    public record ConversationMessage(String role, String content) {
    }
}
