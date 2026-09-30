package com.ai.service.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private CodexCapacity capacity;

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
                case "$turn_start" in
                  *'"model":"test-chat-model"'*) ;;
                  *) printf '{"id":2,"error":{"message":"expected chat model"}}\\n'; exit 1 ;;
                esac
                case "$turn_start" in
                  *'"effort":"low"'*) ;;
                  *) printf '{"id":2,"error":{"message":"expected chat effort"}}\\n'; exit 1 ;;
                esac
                printf '{"id":2,"result":{"turn":{"id":"turn_test"}}}\\n'
                printf '{"method":"item/agentMessage/delta","params":{"threadId":"thr_test","turnId":"turn_test","itemId":"item_1","delta":"Hel"}}\\n'
                printf '{"method":"item/agentMessage/delta","params":{"threadId":"thr_test","turnId":"turn_test","itemId":"item_1","delta":"lo"}}\\n'
                printf '{"method":"turn/completed","params":{"threadId":"thr_test","turn":{"id":"turn_test","items":[],"status":"completed"}}}\\n'
                """);
        client = client(executable);
        assertThat(capacity.image.tryAcquire()).isTrue();
        assertThatThrownBy(() -> client.generate("image"))
                .isInstanceOf(CodexBusyException.class);
        List<String> deltas = new ArrayList<>();

        List<String> createdThreads = new ArrayList<>();
        CodexStreamResult result = client.stream(
                "hello", Optional.empty(), createdThreads::add, deltas::add);

        assertThat(createdThreads).containsExactly("thr_test");
        assertThat(deltas).containsExactly("Hel", "lo");
        assertThat(result).isEqualTo(new CodexStreamResult("thr_test", "Hello"));
        assertThat(capacity.chat.availablePermits()).isEqualTo(2);
        assertThat(capacity.image.availablePermits()).isZero();
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
                case "$turn_start" in
                  *'"model":"test-chat-model"'*) ;;
                  *) printf '{"id":2,"error":{"message":"expected chat model"}}\\n'; exit 1 ;;
                esac
                case "$turn_start" in
                  *'"effort":"low"'*) ;;
                  *) printf '{"id":2,"error":{"message":"expected chat effort"}}\\n'; exit 1 ;;
                esac
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

    @Test
    void generatesAnImageThroughTheCodexImageGenerationTool() throws IOException {
        Path executable = script("""
                #!/bin/sh
                IFS= read -r initialize
                printf '{"id":0,"result":{"userAgent":"test"}}\\n'
                IFS= read -r initialized
                IFS= read -r thread_start
                case "$thread_start" in
                  *'"ephemeral":true'*) ;;
                  *) printf '{"id":1,"error":{"message":"expected ephemeral thread"}}\\n'; exit 1 ;;
                esac
                printf '{"id":1,"result":{"thread":{"id":"thr_image"}}}\\n'
                IFS= read -r turn_start
                case "$turn_start" in
                  *'"model":'*|*'"effort":'*) printf '{"id":2,"error":{"message":"chat overrides leaked into image"}}\\n'; exit 1 ;;
                esac
                case "$turn_start" in
                  *'$imagegen\\nGenerate exactly one image now.'*'Image brief:\\nA lighthouse during a storm'*) ;;
                  *) printf '{"id":2,"error":{"message":"expected forced imagegen prompt"}}\\n'; exit 1 ;;
                esac
                printf '{"id":2,"result":{"turn":{"id":"turn_image"}}}\\n'
                printf '{"method":"item/completed","params":{"threadId":"thr_image","turnId":"turn_image","completedAtMs":1,"item":{"id":"image_1","type":"imageGeneration","status":"completed","result":"data:image/png;base64,aW1hZ2U=","revisedPrompt":"A dramatic lighthouse","transparentBackground":false}}}\\n'
                printf '{"method":"turn/completed","params":{"threadId":"thr_image","turn":{"id":"turn_image","items":[],"status":"completed"}}}\\n'
                """);
        client = client(executable);

        assertThat(capacity.chat.tryAcquire(2)).isTrue();
        assertThatThrownBy(() -> client.stream("hello", Optional.empty(), ignored -> { }, ignored -> { }))
                .isInstanceOf(CodexBusyException.class);
        CodexImageResult result = client.generate("A lighthouse during a storm");
        assertThat(capacity.image.availablePermits()).isEqualTo(1);
        assertThat(capacity.chat.availablePermits()).isZero();

        assertThat(result.content()).isEqualTo("image".getBytes());
        assertThat(result.mediaType()).isEqualTo("image/png");
        assertThat(result.filename()).isEqualTo("codex-image.png");
    }

    @Test
    void suppliesReferenceImagesAsLocalImageTurnInputs() throws IOException {
        Path executable = script("""
                #!/bin/sh
                IFS= read -r initialize
                printf '{"id":0,"result":{"userAgent":"test"}}\\n'
                IFS= read -r initialized
                IFS= read -r thread_start
                printf '{"id":1,"result":{"thread":{"id":"thr_image"}}}\\n'
                IFS= read -r turn_start
                case "$turn_start" in
                  *'"type":"localImage"'*) ;;
                  *) printf '{"id":2,"error":{"message":"expected local image"}}\\n'; exit 1 ;;
                esac
                case "$turn_start" in
                  *'reference-1.png'*) ;;
                  *) printf '{"id":2,"error":{"message":"expected reference path"}}\\n'; exit 1 ;;
                esac
                test -f "$PWD/reference-1.png" || exit 1
                printf '{"id":2,"result":{"turn":{"id":"turn_image"}}}\\n'
                printf '{"method":"item/completed","params":{"threadId":"thr_image","turnId":"turn_image","completedAtMs":1,"item":{"id":"image_1","type":"imageGeneration","status":"completed","result":"data:image/png;base64,aW1hZ2U="}}}\\n'
                printf '{"method":"turn/completed","params":{"threadId":"thr_image","turn":{"id":"turn_image","items":[],"status":"completed"}}}\\n'
                """);
        client = client(executable);
        byte[] reference = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1};

        CodexImageResult result = client.generate(
                "Use the reference composition", List.of(new CodexReferenceImage(reference, "png")));

        assertThat(result.content()).isEqualTo("image".getBytes());
        try (var workspaces = Files.list(tempDirectory.resolve("workspaces"))) {
            assertThat(workspaces).isEmpty();
        }
    }

    @Test
    void readsAndDeletesAnImageFromTheCodexGeneratedImagesCache() throws IOException {
        Path imageDirectory = tempDirectory.resolve("generated-images/thread-test");
        Files.createDirectories(imageDirectory);
        Path imagePath = imageDirectory.resolve("generated.png");
        Path executable = script(("""
                #!/bin/sh
                IFS= read -r initialize
                printf '{"id":0,"result":{"userAgent":"test"}}\\n'
                IFS= read -r initialized
                IFS= read -r thread_start
                printf '{"id":1,"result":{"thread":{"id":"thr_image"}}}\\n'
                IFS= read -r turn_start
                printf '\\211PNG\\r\\n\\032\\npixels' > '%s'
                printf '{"id":2,"result":{"turn":{"id":"turn_image"}}}\\n'
                printf '{"method":"item/completed","params":{"threadId":"thr_image","turnId":"turn_image","completedAtMs":1,"item":{"id":"image_1","type":"imageGeneration","status":"completed","result":"saved","savedPath":"%s"}}}\\n'
                printf '{"method":"turn/completed","params":{"threadId":"thr_image","turn":{"id":"turn_image","items":[],"status":"completed"}}}\\n'
                """).formatted(imagePath, imagePath));
        client = client(executable);

        CodexImageResult result = client.generate("A lighthouse during a storm");

        assertThat(result.mediaType()).isEqualTo("image/png");
        assertThat(result.content()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(imagePath).doesNotExist();
        assertThat(imageDirectory).doesNotExist();
        try (var workspaces = Files.list(tempDirectory.resolve("workspaces"))) {
            assertThat(workspaces).isEmpty();
        }
    }

    @Test
    void releasesOnlyTheSelectedCapacityAfterProtocolFailure() throws IOException {
        client = client(script("#!/bin/sh\nexit 1\n"));
        assertThat(capacity.image.tryAcquire()).isTrue();
        assertThatThrownBy(() -> client.stream("hello", Optional.empty(), ignored -> { }, ignored -> { }))
                .isInstanceOf(CodexException.class);
        assertThat(capacity.chat.availablePermits()).isEqualTo(2);
        assertThat(capacity.image.availablePermits()).isZero();
        capacity.image.release();
        assertThatThrownBy(() -> client.generate("image"))
                .isInstanceOf(CodexException.class);
        assertThat(capacity.image.availablePermits()).isEqualTo(1);
        assertThat(capacity.chat.availablePermits()).isEqualTo(2);
    }

    private CodexAppServerClient client(Path executable) {
        CodexProperties properties = new CodexProperties(
                executable.toString(), tempDirectory.resolve("workspaces"), tempDirectory.resolve("generated-images"),
                Duration.ofSeconds(5), "test-chat-model", "low", 2, 1, 100_000, 1_000_000);
        try {
            Files.createDirectories(properties.workspaceRoot());
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        capacity = new CodexCapacity(properties);
        return new CodexAppServerClient(properties, capacity, new ObjectMapper());
    }

    private Path script(String content) throws IOException {
        Path script = tempDirectory.resolve("fake-app-server-" + System.nanoTime());
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
    }
}
