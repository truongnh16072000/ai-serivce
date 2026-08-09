package com.ai.service.codex;

import java.util.function.Consumer;

public interface CodexStreamingClient {

    String stream(String prompt, Consumer<String> onDelta);
}
