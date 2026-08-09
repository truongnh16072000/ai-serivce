package com.ai.service.conversation;

import com.ai.service.codex.CodexStreamResult;
import com.ai.service.codex.CodexStreamingClient;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class ConversationService {

    private final ConversationRepository repository;
    private final ConversationLockManager lockManager;
    private final CodexStreamingClient codexClient;

    public ConversationService(
            ConversationRepository repository,
            ConversationLockManager lockManager,
            CodexStreamingClient codexClient) {
        this.repository = repository;
        this.lockManager = lockManager;
        this.codexClient = codexClient;
    }

    public String ask(String conversationId, String prompt) {
        return stream(conversationId, prompt, ignored -> { });
    }

    public String stream(String conversationId, String prompt, Consumer<String> onDelta) {
        try (ConversationLockManager.Lease ignored = lockManager.tryAcquire(conversationId)) {
            Optional<String> existingThreadId = repository.findCodexThreadId(conversationId);
            CodexStreamResult result = codexClient.stream(
                    prompt,
                    existingThreadId,
                    threadId -> repository.create(conversationId, threadId),
                    onDelta);

            existingThreadId.ifPresent(threadId -> {
                if (!threadId.equals(result.threadId())) {
                    throw new IllegalStateException("Codex resumed an unexpected conversation thread");
                }
            });
            repository.recordCompletedTurn(conversationId, prompt, result.answer());
            return result.answer();
        }
    }
}
