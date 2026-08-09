package com.ai.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ai.service.codex.CodexProperties;
import com.ai.service.codex.CodexStreamingClient;
import com.ai.service.error.ApiExceptionHandler;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatStreamControllerTests {

    private MockMvc mockMvc;
    private ChatStreamService streamService;

    @BeforeEach
    void setUp() {
        CodexStreamingClient client = (prompt, onDelta) -> {
            onDelta.accept("Hel");
            onDelta.accept("lo");
            return "Hello";
        };
        CodexProperties properties = new CodexProperties(
                "codex", Path.of("/tmp"), Duration.ofSeconds(5), 2, 100_000);
        streamService = new ChatStreamService(client, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatStreamController(streamService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        streamService.closeExecutor();
    }

    @Test
    void streamsStartedDeltaAndCompletedEvents() throws Exception {
        MvcResult pending = mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"conversationId\":\"abc123\",\"message\":\"Hello\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn();
        String body = completed.getResponse().getContentAsString();

        assertThat(body)
                .contains("event:started", "event:delta", "event:completed")
                .contains("abc123", "Hel", "lo", "Hello");
    }
}
