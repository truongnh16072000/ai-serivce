package com.ai.service.codex;

import jakarta.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CodexAppServerClient implements CodexStreamingClient {

    private static final Logger log = LoggerFactory.getLogger(CodexAppServerClient.class);
    private static final int MAX_ERROR_BYTES = 16 * 1024;

    private final CodexProperties properties;
    private final CodexCapacity capacity;
    private final ObjectMapper objectMapper;
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CodexAppServerClient(CodexProperties properties, CodexCapacity capacity, ObjectMapper objectMapper) {
        this.properties = properties;
        this.capacity = capacity;
        this.objectMapper = objectMapper;
    }

    @Override
    public String stream(String prompt, Consumer<String> onDelta) {
        capacity.acquire();
        Path workspace = null;
        Process process = null;
        try {
            workspace = Files.createTempDirectory(properties.workspaceRoot(), "stream-");
            process = startProcess(workspace);

            Process runningProcess = process;
            Path runningWorkspace = workspace;
            CompletableFuture<String> protocol = CompletableFuture.supplyAsync(
                    () -> runProtocol(runningProcess, runningWorkspace, prompt, onDelta), ioExecutor);
            CompletableFuture<Void> stderr = CompletableFuture.runAsync(
                    () -> drainStderr(runningProcess), ioExecutor);

            String answer = protocol.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            terminate(process);
            await(stderr);
            return answer;
        } catch (TimeoutException exception) {
            terminate(process);
            throw new CodexTimeoutException();
        } catch (ExecutionException exception) {
            terminate(process);
            Throwable cause = exception.getCause();
            if (cause instanceof CodexException codexException) {
                throw codexException;
            }
            throw new CodexException("Could not stream the Codex response.", cause);
        } catch (IOException exception) {
            terminate(process);
            throw new CodexException("Could not start the Codex App Server.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            terminate(process);
            throw new CodexException("The AI request was interrupted.", exception);
        } finally {
            terminate(process);
            deleteWorkspace(workspace);
            capacity.release();
        }
    }

    private Process startProcess(Path workspace) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(properties.executable(), "app-server", "--stdio");
        builder.directory(workspace.toFile());
        return builder.start();
    }

    private String runProtocol(Process process, Path workspace, String prompt, Consumer<String> onDelta) {
        StringBuilder answer = new StringBuilder();
        try (BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             BoundedLineReader reader = new BoundedLineReader(
                     process.getInputStream(), properties.maxOutputBytes())) {
            send(writer, Map.of(
                    "method", "initialize",
                    "id", 0,
                    "params", Map.of("clientInfo", Map.of(
                            "name", "htlabs_ai_service",
                            "title", "HT Labs AI Service",
                            "version", "1.0"))));
            awaitResponse(reader, 0);

            send(writer, Map.of("method", "initialized", "params", Map.of()));
            send(writer, Map.of(
                    "method", "thread/start",
                    "id", 1,
                    "params", Map.of(
                            "cwd", workspace.toString(),
                            "ephemeral", true,
                            "approvalPolicy", "never",
                            "sandbox", "read-only",
                            "serviceName", "htlabs_ai_service")));
            JsonNode threadResponse = awaitResponse(reader, 1);
            String threadId = threadResponse.path("result").path("thread").path("id").stringValue("");
            if (threadId.isBlank()) {
                throw new CodexException("Codex App Server did not create a thread.");
            }

            send(writer, Map.of(
                    "method", "turn/start",
                    "id", 2,
                    "params", Map.of(
                            "threadId", threadId,
                            "input", List.of(Map.of("type", "text", "text", prompt)))));

            while (true) {
                JsonNode message = readMessage(reader);
                rejectProtocolError(message);
                String method = message.path("method").stringValue("");
                if ("item/agentMessage/delta".equals(method)) {
                    String delta = message.path("params").path("delta").stringValue("");
                    if (!delta.isEmpty()) {
                        answer.append(delta);
                        onDelta.accept(delta);
                    }
                } else if ("turn/completed".equals(method)) {
                    String status = message.path("params").path("turn").path("status").stringValue("");
                    if (!"completed".equals(status)) {
                        throw new CodexException("Codex could not complete the streamed response.");
                    }
                    if (answer.isEmpty()) {
                        throw new CodexException("Codex completed without a streamed response.");
                    }
                    return answer.toString();
                }
            }
        } catch (IOException exception) {
            throw new CodexException("Could not communicate with the Codex App Server.", exception);
        }
    }

    private JsonNode awaitResponse(BoundedLineReader reader, int expectedId) throws IOException {
        while (true) {
            JsonNode message = readMessage(reader);
            rejectProtocolError(message);
            if (message.path("id").isInt() && message.path("id").intValue() == expectedId) {
                return message;
            }
        }
    }

    private JsonNode readMessage(BoundedLineReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new CodexException("Codex App Server closed the stream unexpectedly.");
        }
        try {
            return objectMapper.readTree(line);
        } catch (JacksonException exception) {
            throw new CodexException("Codex App Server returned malformed JSON.", exception);
        }
    }

    private void rejectProtocolError(JsonNode message) {
        if (!message.path("error").isMissingNode() || "error".equals(message.path("method").stringValue(""))) {
            throw new CodexException("Codex App Server reported an error.");
        }
    }

    private void send(BufferedWriter writer, Map<String, ?> message) throws IOException {
        writer.write(objectMapper.writeValueAsString(message));
        writer.newLine();
        writer.flush();
    }

    private void drainStderr(Process process) {
        int totalBytes = 0;
        try (InputStream input = new BufferedInputStream(process.getErrorStream())) {
            while (input.read() != -1) {
                if (++totalBytes > MAX_ERROR_BYTES) {
                    terminate(process);
                    throw new CodexException("Codex exceeded the configured error output limit.");
                }
            }
        } catch (IOException exception) {
            if (process.isAlive()) {
                throw new CodexException("Could not read Codex error output.", exception);
            }
        }
    }

    private void await(CompletableFuture<Void> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof CodexException codexException) {
                throw codexException;
            }
            throw new CodexException("Could not process Codex error output.", exception.getCause());
        } catch (TimeoutException exception) {
            throw new CodexException("Timed out while closing the Codex App Server.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CodexException("The AI request was interrupted.", exception);
        }
    }

    private void terminate(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void deleteWorkspace(Path workspace) {
        if (workspace == null || !workspace.startsWith(properties.workspaceRoot())) {
            return;
        }
        try (var paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.warn("Could not delete temporary Codex path {}", path, exception);
                }
            });
        } catch (IOException exception) {
            log.warn("Could not clean temporary Codex workspace {}", workspace, exception);
        }
    }

    @PreDestroy
    void closeExecutor() {
        ioExecutor.close();
    }

    private static final class BoundedLineReader implements AutoCloseable {

        private final InputStream input;
        private final int maxBytes;
        private int totalBytes;

        private BoundedLineReader(InputStream input, int maxBytes) {
            this.input = new BufferedInputStream(input);
            this.maxBytes = maxBytes;
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int nextByte;
            while ((nextByte = input.read()) != -1) {
                if (++totalBytes > maxBytes) {
                    throw new CodexException("Codex exceeded the configured output limit.");
                }
                if (nextByte == '\n') {
                    return line.toString(StandardCharsets.UTF_8);
                }
                line.write(nextByte);
            }
            return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
