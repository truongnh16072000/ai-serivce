package com.ai.service.telegram;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ai.service.error.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TelegramControllerTests {

    private MockMvc mockMvc;
    private TelegramMessageDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = mock(TelegramMessageDispatcher.class);
        TelegramController controller = new TelegramController(dispatcher);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void sendsTheMessage() throws Exception {
        mockMvc.perform(post("/api/v1/telegram/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hello\"}"))
                .andExpect(status().isAccepted());

        verify(dispatcher).dispatch(null, "Hello", null);
    }

    @Test
    void sendsToTheRequestedChat() throws Exception {
        mockMvc.perform(post("/api/v1/telegram/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"123\",\"text\":\"Hi\",\"parseMode\":\"HTML\"}"))
                .andExpect(status().isAccepted());

        verify(dispatcher).dispatch("123", "Hi", "HTML");
    }

    @Test
    void rejectsBlankText() throws Exception {
        mockMvc.perform(post("/api/v1/telegram/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.text").value("text is required"));
    }

    @Test
    void rejectsInvalidChatId() throws Exception {
        mockMvc.perform(post("/api/v1/telegram/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"not-a-chat\",\"text\":\"Hi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.chatId").value("chatId must be a valid Telegram chat id"));
    }

    @Test
    void rejectsUnsupportedParseMode() throws Exception {
        mockMvc.perform(post("/api/v1/telegram/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hi\",\"parseMode\":\"MarkdownV9\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.parseMode").value("parseMode must be HTML, Markdown, or MarkdownV2"));
    }

    @Test
    void mapsTelegramClientErrorsToBadRequest() throws Exception {
        doThrow(new TelegramException("The Telegram bot token is not configured."))
                .when(dispatcher).dispatch("123", "Hi", null);

        mockMvc.perform(post("/api/v1/telegram/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"123\",\"text\":\"Hi\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The Telegram bot token is not configured."));
    }

    @Test
    void mapsAFullDeliveryQueueToServiceUnavailable() throws Exception {
        doThrow(new TelegramQueueFullException()).when(dispatcher).dispatch("123", "Hi", null);

        mockMvc.perform(post("/api/v1/telegram/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"123\",\"text\":\"Hi\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The Telegram delivery queue is full. Try again later."));
    }
}
