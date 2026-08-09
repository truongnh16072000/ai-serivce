package com.ai.service.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class CodexCliClientTests {

    @TempDir
    Path tempDirectory;

    private CodexCliClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.closeExecutor();
        }
    }

    @Test
    void sendsPromptThroughStdinAndReadsJsonlResponse() throws IOException {
        Path executable = script("""
                #!/bin/sh
                prompt=$(cat)
                printf '{"type":"thread.started"}\\n'
                printf '{"type":"item.completed","item":{"type":"agent_message","text":"Received: %s"}}\\n' "$prompt"
                """);
        client = client(executable, 100_000);

        assertThat(client.ask("safe prompt; $(ignored)")).isEqualTo("Received: safe prompt; $(ignored)");
    }

    @Test
    void doesNotReturnCommandOutputAsAssistantText() throws IOException {
        Path executable = script("""
                #!/bin/sh
                cat >/dev/null
                printf '{"type":"item.completed","item":{"type":"command_execution","text":"private"}}\\n'
                """);
        client = client(executable, 100_000);

        assertThatThrownBy(() -> client.ask("hello"))
                .isInstanceOf(CodexException.class)
                .hasMessage("Codex completed without a response.");
    }

    @Test
    void enforcesOutputLimit() throws IOException {
        Path executable = script("""
                #!/bin/sh
                cat >/dev/null
                printf '{"type":"item.completed","item":{"type":"agent_message","text":"This is too long"}}\\n'
                """);
        client = client(executable, 32);

        assertThatThrownBy(() -> client.ask("hello"))
                .isInstanceOf(CodexException.class)
                .hasMessage("Codex exceeded the configured output limit.");
    }

    private CodexCliClient client(Path executable, int maxOutputBytes) {
        CodexProperties properties = new CodexProperties(
                executable.toString(), tempDirectory.resolve("workspaces"), Duration.ofSeconds(5), 2, maxOutputBytes);
        return new CodexCliClient(
                properties,
                new CodexEventParser(new ObjectMapper()),
                new CodexCapacity(properties));
    }

    private Path script(String content) throws IOException {
        Path script = tempDirectory.resolve("fake-codex-" + System.nanoTime());
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
    }
}
