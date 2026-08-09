package com.ai.service.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class CodexAppServerClientTests {

    @TempDir
    Path tempDirectory;

    private CodexAppServerClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.closeExecutor();
        }
    }

    @Test
    void streamsAgentMessageDeltasAndReturnsCompleteAnswer() throws IOException {
        Path executable = script("""
                #!/bin/sh
                IFS= read -r initialize
                printf '{"id":0,"result":{"userAgent":"test"}}\\n'
                IFS= read -r initialized
                IFS= read -r thread_start
                printf '{"id":1,"result":{"thread":{"id":"thr_test"}}}\\n'
                IFS= read -r turn_start
                printf '{"id":2,"result":{"turn":{"id":"turn_test"}}}\\n'
                printf '{"method":"item/agentMessage/delta","params":{"threadId":"thr_test","turnId":"turn_test","itemId":"item_1","delta":"Hel"}}\\n'
                printf '{"method":"item/agentMessage/delta","params":{"threadId":"thr_test","turnId":"turn_test","itemId":"item_1","delta":"lo"}}\\n'
                printf '{"method":"turn/completed","params":{"threadId":"thr_test","turn":{"id":"turn_test","items":[],"status":"completed"}}}\\n'
                """);
        client = client(executable);
        List<String> deltas = new ArrayList<>();

        List<String> createdThreads = new ArrayList<>();
        CodexStreamResult result = client.stream(
                "hello", Optional.empty(), createdThreads::add, deltas::add);

        assertThat(createdThreads).containsExactly("thr_test");
        assertThat(deltas).containsExactly("Hel", "lo");
        assertThat(result).isEqualTo(new CodexStreamResult("thr_test", "Hello"));
    }

    @Test
    void resumesTheStoredThreadInsteadOfStartingANewConversation() throws IOException {
        Path executable = script("""
                #!/bin/sh
                IFS= read -r initialize
                printf '{"id":0,"result":{"userAgent":"test"}}\\n'
                IFS= read -r initialized
                IFS= read -r thread_request
                case "$thread_request" in
                  *'"method":"thread/resume"'*) ;;
                  *) printf '{"id":1,"error":{"message":"expected resume"}}\\n'; exit 1 ;;
                esac
                case "$thread_request" in
                  *'"threadId":"thr_existing"'*) ;;
                  *) printf '{"id":1,"error":{"message":"expected thread id"}}\\n'; exit 1 ;;
                esac
                printf '{"id":1,"result":{"thread":{"id":"thr_existing"}}}\\n'
                IFS= read -r turn_start
                printf '{"id":2,"result":{"turn":{"id":"turn_test"}}}\\n'
                printf '{"method":"item/agentMessage/delta","params":{"threadId":"thr_existing","turnId":"turn_test","itemId":"item_1","delta":"Remembered"}}\\n'
                printf '{"method":"turn/completed","params":{"threadId":"thr_existing","turn":{"id":"turn_test","items":[],"status":"completed"}}}\\n'
                """);
        client = client(executable);
        List<String> createdThreads = new ArrayList<>();

        CodexStreamResult result = client.stream(
                "what did I say?", Optional.of("thr_existing"), createdThreads::add, ignored -> { });

        assertThat(createdThreads).isEmpty();
        assertThat(result).isEqualTo(new CodexStreamResult("thr_existing", "Remembered"));
    }

    private CodexAppServerClient client(Path executable) {
        CodexProperties properties = new CodexProperties(
                executable.toString(), tempDirectory.resolve("workspaces"), Duration.ofSeconds(5), 2, 100_000);
        try {
            Files.createDirectories(properties.workspaceRoot());
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        return new CodexAppServerClient(properties, new CodexCapacity(properties), new ObjectMapper());
    }

    private Path script(String content) throws IOException {
        Path script = tempDirectory.resolve("fake-app-server-" + System.nanoTime());
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
    }
}
