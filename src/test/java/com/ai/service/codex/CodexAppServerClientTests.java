package com.ai.service.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

        String answer = client.stream("hello", deltas::add);

        assertThat(deltas).containsExactly("Hel", "lo");
        assertThat(answer).isEqualTo("Hello");
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
