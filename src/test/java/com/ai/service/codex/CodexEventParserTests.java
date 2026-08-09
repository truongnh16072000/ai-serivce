package com.ai.service.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CodexEventParserTests {

    private final CodexEventParser parser = new CodexEventParser(new ObjectMapper());

    @Test
    void collectsCompletedAssistantMessages() {
        List<String> messages = new ArrayList<>();

        parser.accept("{\"type\":\"thread.started\",\"thread_id\":\"123\"}", messages);
        parser.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Hello\"}}", messages);

        assertThat(messages).containsExactly("Hello");
    }

    @Test
    void ignoresNonAssistantItems() {
        List<String> messages = new ArrayList<>();

        parser.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\",\"text\":\"secret\"}}", messages);

        assertThat(messages).isEmpty();
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.accept("not-json", new ArrayList<>()))
                .isInstanceOf(CodexException.class)
                .hasMessage("Codex returned malformed JSON output.");
    }
}
