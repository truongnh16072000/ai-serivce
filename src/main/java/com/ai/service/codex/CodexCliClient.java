package com.ai.service.codex;

import jakarta.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CodexCliClient implements CodexClient {

    private static final Logger log = LoggerFactory.getLogger(CodexCliClient.class);
    private static final int MAX_ERROR_BYTES = 16 * 1024;

    private final CodexProperties properties;
    private final CodexEventParser eventParser;
    private final Semaphore concurrencyLimit;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public CodexCliClient(CodexProperties properties, CodexEventParser eventParser) {
        this.properties = properties;
        this.eventParser = eventParser;
        this.concurrencyLimit = new Semaphore(properties.maxConcurrentRequests(), true);
        createWorkspaceRoot();
    }

    @Override
    public String ask(String prompt) {
        if (!concurrencyLimit.tryAcquire()) {
            throw new CodexBusyException();
        }

        Path requestWorkspace = null;
        Process process = null;
        try {
            requestWorkspace = Files.createTempDirectory(properties.workspaceRoot(), "request-");
            process = startProcess(requestWorkspace);
            writePrompt(process, prompt);

            Process runningProcess = process;
            CompletableFuture<List<String>> stdout = CompletableFuture.supplyAsync(
                    () -> readEvents(runningProcess), streamExecutor);
            CompletableFuture<Void> stderr = CompletableFuture.runAsync(
                    () -> drainStderr(runningProcess), streamExecutor);

            boolean exited = process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                terminate(process);
                throw new CodexTimeoutException();
            }

            List<String> messages = await(stdout);
            await(stderr);
            if (process.exitValue() != 0) {
                log.warn("Codex exited with code {}", process.exitValue());
                throw new CodexException("Codex could not produce a response.");
            }
            if (messages.isEmpty()) {
                log.warn("Codex completed without an assistant message");
                throw new CodexException("Codex completed without a response.");
            }
            return String.join("\n", messages);
        } catch (CodexException exception) {
            throw exception;
        } catch (IOException exception) {
            if (process != null) {
                terminate(process);
            }
            throw new CodexException("Could not communicate with the Codex CLI.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminate(process);
            }
            throw new CodexException("The AI request was interrupted.", exception);
        } finally {
            concurrencyLimit.release();
            deleteWorkspace(requestWorkspace);
        }
    }

    private Process startProcess(Path workspace) throws IOException {
        List<String> command = List.of(
                properties.executable(), "exec",
                "--json",
                "--ephemeral",
                "--ignore-user-config",
                "--sandbox", "read-only",
                "--skip-git-repo-check",
                "--cd", workspace.toString(),
                "-");
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workspace.toFile());
        return processBuilder.start();
    }

    private void writePrompt(Process process, String prompt) throws IOException {
        try (Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(prompt);
        }
    }

    private List<String> readEvents(Process process) {
        List<String> messages = eventParser.newMessageList();
        int totalBytes = 0;
        try (InputStream input = new BufferedInputStream(process.getInputStream());
             ByteArrayOutputStream line = new ByteArrayOutputStream()) {
            int nextByte;
            while ((nextByte = input.read()) != -1) {
                totalBytes++;
                if (totalBytes > properties.maxOutputBytes()) {
                    terminate(process);
                    throw new CodexException("Codex exceeded the configured output limit.");
                }
                if (nextByte == '\n') {
                    acceptLine(line, messages);
                } else {
                    line.write(nextByte);
                }
            }
            if (line.size() > 0) {
                acceptLine(line, messages);
            }
            return messages;
        } catch (IOException exception) {
            throw new CodexException("Could not read the Codex response.", exception);
        }
    }

    private void acceptLine(ByteArrayOutputStream line, List<String> messages) {
        eventParser.accept(line.toString(StandardCharsets.UTF_8), messages);
        line.reset();
    }

    private void drainStderr(Process process) {
        int totalBytes = 0;
        try (InputStream input = new BufferedInputStream(process.getErrorStream())) {
            while (input.read() != -1) {
                totalBytes++;
                if (totalBytes > MAX_ERROR_BYTES) {
                    terminate(process);
                    throw new CodexException("Codex exceeded the configured error output limit.");
                }
            }
        } catch (IOException exception) {
            throw new CodexException("Could not read Codex error output.", exception);
        }
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof CodexException codexException) {
                throw codexException;
            }
            throw new CodexException("Could not process the Codex response.", cause);
        } catch (TimeoutException exception) {
            throw new CodexException("Timed out while reading the Codex process output.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CodexException("The AI request was interrupted.", exception);
        }
    }

    private void terminate(Process process) {
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

    private void createWorkspaceRoot() {
        try {
            Files.createDirectories(properties.workspaceRoot());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the Codex workspace root", exception);
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
        streamExecutor.close();
    }
}
