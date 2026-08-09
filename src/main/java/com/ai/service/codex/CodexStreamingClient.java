package com.ai.service.codex;

import java.util.Optional;
import java.util.function.Consumer;

public interface CodexStreamingClient {

    CodexStreamResult stream(
            String prompt,
            Optional<String> existingThreadId,
            Consumer<String> onThreadCreated,
            Consumer<String> onDelta);
}
