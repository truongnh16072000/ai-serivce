package com.ai.service.conversation;

import com.ai.service.codex.CodexStreamResult;
import com.ai.service.codex.CodexStreamingClient;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

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
        long startedAt = System.nanoTime();
        try (ConversationLockManager.Lease ignored = lockManager.tryAcquire(conversationId)) {
            long lookupStartedAt = System.nanoTime();
            Optional<String> existingThreadId = repository.findCodexThreadId(conversationId);
            log.info("Conversation lookup finished for {} in {} ms",
                    conversationId, elapsedMillis(lookupStartedAt));
            long codexStartedAt = System.nanoTime();
            CodexStreamResult result = codexClient.stream(
                    prompt,
                    existingThreadId,
                    threadId -> repository.create(conversationId, threadId),
                    onDelta);
            log.info("Codex stream finished for conversation {} in {} ms",
                    conversationId, elapsedMillis(codexStartedAt));

            existingThreadId.ifPresent(threadId -> {
                if (!threadId.equals(result.threadId())) {
                    throw new IllegalStateException("Codex resumed an unexpected conversation thread");
                }
            });
            long saveStartedAt = System.nanoTime();
            repository.recordCompletedTurn(conversationId, prompt, result.answer());
            log.info("Conversation save finished for {} in {} ms; service time {} ms",
                    conversationId, elapsedMillis(saveStartedAt), elapsedMillis(startedAt));
            return result.answer();
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
