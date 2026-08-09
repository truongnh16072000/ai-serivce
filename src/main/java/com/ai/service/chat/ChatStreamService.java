package com.ai.service.chat;

import com.ai.service.codex.CodexException;
import com.ai.service.codex.CodexProperties;
import com.ai.service.codex.CodexStreamingClient;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private final CodexStreamingClient codexClient;
    private final CodexProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatStreamService(CodexStreamingClient codexClient, CodexProperties properties) {
        this.codexClient = codexClient;
        this.properties = properties;
    }

    public SseEmitter stream(String conversationId, String prompt) {
        SseEmitter emitter = new SseEmitter(properties.timeout().plusSeconds(10).toMillis());
        AtomicLong eventId = new AtomicLong();
        send(emitter, eventId, "started", Map.of("conversationId", conversationId));

        executor.submit(() -> {
            try {
                String answer = codexClient.stream(prompt,
                        delta -> send(emitter, eventId, "delta", Map.of("text", delta)));
                send(emitter, eventId, "completed", Map.of(
                        "conversationId", conversationId,
                        "answer", answer));
                emitter.complete();
            } catch (ClientDisconnectedException exception) {
                log.debug("SSE client disconnected from conversation {}", conversationId);
                emitter.complete();
            } catch (CodexException exception) {
                log.warn("Streamed Codex request failed for conversation {}: {}",
                        conversationId, exception.getMessage());
                sendError(emitter, eventId, exception.getMessage());
            } catch (RuntimeException exception) {
                log.error("Unexpected streamed Codex failure for conversation {}", conversationId, exception);
                sendError(emitter, eventId, "The AI stream failed unexpectedly.");
            }
        });
        return emitter;
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
