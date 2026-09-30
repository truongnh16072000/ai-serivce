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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
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
public class CodexAppServerClient implements CodexStreamingClient, CodexImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(CodexAppServerClient.class);
    private static final int MAX_ERROR_BYTES = 16 * 1024;
    private static final String IMAGE_GENERATION_INSTRUCTIONS = """
            $imagegen
            Generate exactly one image now. Treat the text under "Image brief" as an image-generation
            brief, not as a conversational request. Do not answer with text, ask a question, create a
            plan, or explain your work. Use reasonable visual defaults for underspecified details and
            always invoke image generation.

            Image brief:
            """;

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
    public CodexStreamResult stream(
            String prompt,
            Optional<String> existingThreadId,
            Consumer<String> onThreadCreated,
            Consumer<String> onDelta) {
        return execute(capacity.chat, (process, workspace) -> runProtocol(
                process, workspace, prompt, existingThreadId, onThreadCreated, onDelta));
    }

    @Override
    public CodexImageResult generate(String prompt) {
        return generate(prompt, List.of());
    }

    @Override
    public CodexImageResult generate(String prompt, List<CodexReferenceImage> referenceImages) {
        List<CodexReferenceImage> references = List.copyOf(referenceImages);
        return execute(capacity.image, (process, workspace) -> runImageProtocol(process, workspace, prompt, references));
    }

    private <T> T execute(Semaphore permits, Protocol<T> protocolRunner) {
        if (!permits.tryAcquire()) {
            throw new CodexBusyException();
        }
        Path workspace = null;
        Process process = null;
        long startupStartedAt = System.nanoTime();
        try {
            Files.createDirectories(properties.workspaceRoot());
            workspace = Files.createTempDirectory(properties.workspaceRoot(), "stream-");
            process = startProcess(workspace);
            log.info("Codex App Server process started in {} ms", elapsedMillis(startupStartedAt));

            Process runningProcess = process;
            Path runningWorkspace = workspace;
            CompletableFuture<T> protocol = CompletableFuture.supplyAsync(
                    () -> protocolRunner.run(runningProcess, runningWorkspace),
                    ioExecutor);
            CompletableFuture<Void> stderr = CompletableFuture.runAsync(
                    () -> drainStderr(runningProcess), ioExecutor);

            long protocolStartedAt = System.nanoTime();
            T result = protocol.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            log.info("Codex App Server protocol completed in {} ms", elapsedMillis(protocolStartedAt));
            terminate(process);
            await(stderr);
            return result;
        } catch (TimeoutException exception) {
            terminate(process);
            throw new CodexTimeoutException();
        } catch (ExecutionException exception) {
            terminate(process);
            Throwable cause = exception.getCause();
            if (cause instanceof CodexException codexException) {
                throw codexException;
            }
            throw new CodexException("Could not complete the Codex request.", cause);
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
            permits.release();
        }
    }

    private Process startProcess(Path workspace) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(properties.executable(), "app-server", "--stdio");
        builder.directory(workspace.toFile());
        return builder.start();
    }

    private CodexStreamResult runProtocol(
            Process process,
            Path workspace,
            String prompt,
            Optional<String> existingThreadId,
            Consumer<String> onThreadCreated,
            Consumer<String> onDelta) {
        StringBuilder answer = new StringBuilder();
        long protocolStartedAt = System.nanoTime();
        boolean firstDelta = true;
        try (BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             BoundedLineReader reader = new BoundedLineReader(
                     process.getInputStream(), properties.maxOutputBytes())) {
            initialize(writer, reader);
            if (existingThreadId.isPresent()) {
                send(writer, Map.of(
                        "method", "thread/resume",
                        "id", 1,
                        "params", Map.of(
                                "threadId", existingThreadId.get(),
                                "cwd", workspace.toString(),
                                "approvalPolicy", "never",
                                "sandbox", "read-only")));
            } else {
                send(writer, Map.of(
                        "method", "thread/start",
                        "id", 1,
                        "params", Map.of(
                                "cwd", workspace.toString(),
                                "ephemeral", false,
                                "approvalPolicy", "never",
                                "sandbox", "read-only",
                                "serviceName", "htlabs_ai_service")));
            }
            JsonNode threadResponse = awaitResponse(reader, 1);
            String threadId = threadResponse.path("result").path("thread").path("id").stringValue("");
            if (threadId.isBlank()) {
                throw new CodexException("Codex App Server did not create a thread.");
            }
            if (existingThreadId.isEmpty()) {
                onThreadCreated.accept(threadId);
            }

            send(writer, Map.of(
                    "method", "turn/start",
                    "id", 2,
                    "params", Map.of(
                            "threadId", threadId,
                            "model", properties.chatModel(),
                            "effort", properties.chatEffort(),
                            "input", List.of(Map.of("type", "text", "text", prompt)))));

            while (true) {
                JsonNode message = readMessage(reader);
                rejectProtocolError(message);
                String method = message.path("method").stringValue("");
                if ("item/agentMessage/delta".equals(method)) {
                    String delta = message.path("params").path("delta").stringValue("");
                    if (!delta.isEmpty()) {
                        answer.append(delta);
                        if (firstDelta) {
                            firstDelta = false;
                            log.info("Codex first response delta arrived in {} ms", elapsedMillis(protocolStartedAt));
                        }
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
                    return new CodexStreamResult(threadId, answer.toString());
                }
            }
        } catch (IOException exception) {
            throw new CodexException("Could not communicate with the Codex App Server.", exception);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private CodexImageResult runImageProtocol(
            Process process,
            Path workspace,
            String prompt,
            List<CodexReferenceImage> referenceImages) {
        CodexImageResult image = null;
        try (BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             BoundedLineReader reader = new BoundedLineReader(
                     process.getInputStream(), properties.maxImageOutputBytes())) {
            initialize(writer, reader);
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
                throw new CodexException("Codex App Server did not create an image-generation thread.");
            }

            List<Map<String, String>> input = new ArrayList<>(referenceImages.size() + 1);
            input.add(Map.of("type", "text", "text", IMAGE_GENERATION_INSTRUCTIONS + prompt));
            for (int index = 0; index < referenceImages.size(); index++) {
                CodexReferenceImage reference = referenceImages.get(index);
                Path referencePath = workspace.resolve("reference-" + (index + 1) + "." + reference.extension());
                Files.write(referencePath, reference.content());
                input.add(Map.of("type", "localImage", "path", referencePath.toString()));
            }

            send(writer, Map.of(
                    "method", "turn/start",
                    "id", 2,
                    "params", Map.of(
                            "threadId", threadId,
                            "input", input)));

            while (true) {
                JsonNode message = readMessage(reader);
                rejectProtocolError(message);
                String method = message.path("method").stringValue("");
                if ("item/completed".equals(method)) {
                    JsonNode item = message.path("params").path("item");
                    if ("imageGeneration".equals(item.path("type").stringValue(""))) {
                        String result = item.path("result").stringValue("");
                        String savedPath = nullableText(item.path("savedPath"));
                        if (result.isBlank() && savedPath == null) {
                            throw new CodexException("Codex completed image generation without an image.");
                        }
                        image = readGeneratedImage(result, savedPath, workspace);
                    }
                } else if ("turn/completed".equals(method)) {
                    String status = message.path("params").path("turn").path("status").stringValue("");
                    if (!"completed".equals(status)) {
                        throw new CodexException("Codex could not complete image generation.");
                    }
                    if (image == null) {
                        throw new CodexException("Codex completed without generating an image.");
                    }
                    return image;
                }
            }
        } catch (IOException exception) {
            throw new CodexException("Could not communicate with the Codex App Server.", exception);
        }
    }

    private void initialize(BufferedWriter writer, BoundedLineReader reader) throws IOException {
        send(writer, Map.of(
                "method", "initialize",
                "id", 0,
                "params", Map.of("clientInfo", Map.of(
                        "name", "htlabs_ai_service",
                        "title", "HT Labs AI Service",
                        "version", "1.0"))));
        awaitResponse(reader, 0);
        send(writer, Map.of("method", "initialized", "params", Map.of()));
    }

    private String nullableText(JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }

    private CodexImageResult readGeneratedImage(String result, String savedPath, Path workspace) {
        try {
            CodexImageResult image;
            if (result.startsWith("data:")) {
                image = decodeDataUrl(result);
            } else {
                image = decodeRawImage(result);
                if (image == null) {
                    if (savedPath == null || savedPath.isBlank()) {
                        throw new CodexException("Codex returned an unsupported generated-image result.");
                    }
                    return readSavedImage(savedPath, workspace);
                }
            }
            deleteManagedSavedImage(savedPath, workspace);
            return image;
        } catch (IllegalArgumentException exception) {
            throw new CodexException("Codex returned malformed generated-image data.", exception);
        }
    }

    private CodexImageResult decodeRawImage(String result) {
        if (result.isBlank()) {
            return null;
        }
        try {
            byte[] content = Base64.getDecoder().decode(result);
            String mediaType = detectImageType(content);
            return new CodexImageResult(content, mediaType, filenameFor(mediaType));
        } catch (IllegalArgumentException | CodexException exception) {
            return null;
        }
    }

    private CodexImageResult decodeDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw new CodexException("Codex returned a malformed generated-image data URL.");
        }
        String metadata = dataUrl.substring(5, comma);
        int separator = metadata.indexOf(';');
        String mediaType = separator < 0 ? metadata : metadata.substring(0, separator);
        if (!metadata.endsWith(";base64") || !isSupportedImageType(mediaType)) {
            throw new CodexException("Codex returned an unsupported generated-image format.");
        }
        try {
            byte[] content = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            validateImageSize(content.length);
            return new CodexImageResult(content, mediaType, filenameFor(mediaType));
        } catch (IllegalArgumentException exception) {
            throw new CodexException("Codex returned malformed generated-image data.", exception);
        }
    }

    private CodexImageResult readSavedImage(String savedPath, Path workspace) {
        try {
            Path imagePath = Path.of(savedPath).toRealPath();
            if (!isManagedImagePath(imagePath, workspace) || !Files.isRegularFile(imagePath)) {
                throw new CodexException("Codex returned an unsafe generated-image path.");
            }
            long size = Files.size(imagePath);
            if (size > properties.maxImageOutputBytes()) {
                throw new CodexException("Codex generated an image larger than the configured limit.");
            }
            byte[] content = Files.readAllBytes(imagePath);
            String mediaType = detectImageType(content);
            deleteManagedSavedImage(savedPath, workspace);
            return new CodexImageResult(content, mediaType, filenameFor(mediaType));
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof CodexException codexException) {
                throw codexException;
            }
            throw new CodexException("Could not read the generated image.", exception);
        }
    }

    private boolean isManagedImagePath(Path imagePath, Path workspace) throws IOException {
        if (imagePath.startsWith(workspace.toRealPath())) {
            return true;
        }
        Path generatedImagesRoot = properties.generatedImagesRoot();
        return Files.isDirectory(generatedImagesRoot)
                && imagePath.startsWith(generatedImagesRoot.toRealPath());
    }

    private void deleteManagedSavedImage(String savedPath, Path workspace) {
        if (savedPath == null || savedPath.isBlank()) {
            return;
        }
        try {
            Path imagePath = Path.of(savedPath).toRealPath();
            if (!isManagedImagePath(imagePath, workspace) || !Files.isRegularFile(imagePath)) {
                return;
            }
            Files.deleteIfExists(imagePath);
            Path parent = imagePath.getParent();
            if (parent != null && !parent.equals(workspace) && !parent.equals(properties.generatedImagesRoot())) {
                try (var entries = Files.list(parent)) {
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(parent);
                    }
                }
            }
        } catch (IOException exception) {
            log.warn("Could not delete generated Codex image {}", savedPath, exception);
        }
    }

    private void validateImageSize(int size) {
        if (size == 0) {
            throw new CodexException("Codex generated an empty image.");
        }
        if (size > properties.maxImageOutputBytes()) {
            throw new CodexException("Codex generated an image larger than the configured limit.");
        }
    }

    private String detectImageType(byte[] content) {
        validateImageSize(content.length);
        if (content.length >= 8
                && (content[0] & 0xff) == 0x89
                && content[1] == 'P'
                && content[2] == 'N'
                && content[3] == 'G') {
            return "image/png";
        }
        if (content.length >= 3
                && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (content.length >= 12
                && content[0] == 'R'
                && content[1] == 'I'
                && content[2] == 'F'
                && content[3] == 'F'
                && content[8] == 'W'
                && content[9] == 'E'
                && content[10] == 'B'
                && content[11] == 'P') {
            return "image/webp";
        }
        throw new CodexException("Codex generated an unsupported image format.");
    }

    private boolean isSupportedImageType(String mediaType) {
        return "image/png".equals(mediaType)
                || "image/jpeg".equals(mediaType)
                || "image/webp".equals(mediaType);
    }

    private String filenameFor(String mediaType) {
        return switch (mediaType) {
            case "image/jpeg" -> "codex-image.jpg";
            case "image/webp" -> "codex-image.webp";
            default -> "codex-image.png";
        };
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

    @FunctionalInterface
    private interface Protocol<T> {
        T run(Process process, Path workspace);
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
