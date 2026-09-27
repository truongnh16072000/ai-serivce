package com.ai.service.chat;

import com.ai.service.codex.CodexException;
import com.ai.service.codex.CodexProperties;
import com.ai.service.conversation.ConversationService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private final ConversationService conversationService;
    private final CodexProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatStreamService(ConversationService conversationService, CodexProperties properties) {
        this.conversationService = conversationService;
        this.properties = properties;
    }

    public SseEmitter stream(String conversationId, String prompt) {
        long startedAt = System.nanoTime();
        SseEmitter emitter = new SseEmitter(properties.timeout().plusSeconds(10).toMillis());
        AtomicLong eventId = new AtomicLong();
        send(emitter, eventId, "started", Map.of("conversationId", conversationId));

        executor.submit(() -> {
            try {
                String answer = conversationService.stream(
                        conversationId,
                        prompt,
                        delta -> send(emitter, eventId, "delta", Map.of("text", delta)));
                send(emitter, eventId, "completed", Map.of(
                        "conversationId", conversationId,
                        "answer", answer));
                log.info("Chat stream completed for conversation {} in {} ms",
                        conversationId, elapsedMillis(startedAt));
                emitter.complete();
            } catch (ClientDisconnectedException exception) {
                log.info("Chat stream client disconnected for conversation {} after {} ms",
                        conversationId, elapsedMillis(startedAt));
                emitter.complete();
            } catch (CodexException exception) {
                log.warn("Chat stream failed for conversation {} after {} ms: {}",
                        conversationId, elapsedMillis(startedAt), exception.getMessage());
                sendError(emitter, eventId, exception.getMessage());
            } catch (RuntimeException exception) {
                log.error("Chat stream failed unexpectedly for conversation {} after {} ms",
                        conversationId, elapsedMillis(startedAt), exception);
                sendError(emitter, eventId, "The AI stream failed unexpectedly.");
            }
        });
        return emitter;
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void sendError(SseEmitter emitter, AtomicLong eventId, String message) {
        try {
            send(emitter, eventId, "error", Map.of("message", message));
        } catch (ClientDisconnectedException ignored) {
            // The connection is already gone.
        } finally {
            emitter.complete();
        }
    }

    private void send(SseEmitter emitter, AtomicLong eventId, String name, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(eventId.incrementAndGet()))
                    .name(name)
                    .data(data));
        } catch (IOException | IllegalStateException exception) {
            throw new ClientDisconnectedException(exception);
        }
    }

    @PreDestroy
    void closeExecutor() {
        executor.close();
    }

    private static final class ClientDisconnectedException extends RuntimeException {
        private ClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }
}
