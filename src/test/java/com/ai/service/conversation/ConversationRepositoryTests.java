package com.ai.service.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ConversationRepositoryTests {

    @Autowired
    private ConversationRepository repository;

    @Test
    void persistsThreadMappingAndCompletedMessagesInOrder() {
        String conversationId = UUID.randomUUID().toString();

        repository.create(conversationId, "thr_" + UUID.randomUUID());
        repository.recordCompletedTurn(conversationId, "My name is Linh", "Nice to meet you, Linh");

        assertThat(repository.findCodexThreadId(conversationId)).isPresent();
        assertThat(repository.findMessages(conversationId))
                .containsExactly(
                        new ConversationRepository.ConversationMessage("user", "My name is Linh"),
                        new ConversationRepository.ConversationMessage("assistant", "Nice to meet you, Linh"));
    }
}
