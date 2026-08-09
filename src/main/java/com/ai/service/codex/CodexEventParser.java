package com.ai.service.codex;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class CodexEventParser {

    private final ObjectMapper objectMapper;

    CodexEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void accept(String line, List<String> assistantMessages) {
        if (line.isBlank()) {
            return;
        }

        final JsonNode event;
        try {
            event = objectMapper.readTree(line);
        } catch (JacksonException exception) {
            throw new CodexException("Codex returned malformed JSON output.", exception);
        }

        if ("error".equals(event.path("type").stringValue(""))) {
            String message = event.path("message").stringValue("Codex reported an execution error.");
            throw new CodexException(message);
        }

        JsonNode item = event.path("item");
        if ("item.completed".equals(event.path("type").stringValue(""))
                && "agent_message".equals(item.path("type").stringValue(""))) {
            String text = item.path("text").stringValue("");
            if (!text.isBlank()) {
                assistantMessages.add(text);
            }
        }
    }

    List<String> newMessageList() {
        return new ArrayList<>();
    }
}
