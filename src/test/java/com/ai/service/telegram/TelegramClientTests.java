package com.ai.service.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class TelegramClientTests {

    private static final String SEND_URL = "https://api.telegram.org/bot123456:TEST-TOKEN/sendMessage";

    private MockRestServiceServer server;
    private TelegramClient client;

    @BeforeEach
    void setUp() {
        TelegramProperties properties = new TelegramProperties(
                "123456:TEST-TOKEN", "default_chat", Duration.ofSeconds(10), 1, 100);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TelegramClient(properties, new ObjectMapper(), builder.build());
    }

    @Test
    void sendsTheMessageToTheConfiguredChat() {
        server.expect(requestTo(SEND_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"chat_id\":\"default_chat\",\"text\":\"Hello\"}"))
                .andRespond(withSuccess(successResponse(42, 123), MediaType.APPLICATION_JSON));

        TelegramSendResponse response = client.send(null, "Hello", null);

        assertThat(response.messageId()).isEqualTo(42);
        assertThat(response.chatId()).isEqualTo(123);
        server.verify();
    }

    @Test
    void sendsToTheRequestedChatWithAParseMode() {
        server.expect(requestTo(SEND_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        "{\"chat_id\":\"-1001234567890\",\"text\":\"<b>Hi</b>\",\"parse_mode\":\"HTML\"}"))
                .andRespond(withSuccess(successResponse(7, -1001234567890L), MediaType.APPLICATION_JSON));

        TelegramSendResponse response = client.send("-1001234567890", "<b>Hi</b>", "HTML");

        assertThat(response.messageId()).isEqualTo(7);
        assertThat(response.chatId()).isEqualTo(-1001234567890L);
        server.verify();
    }

    @Test
    void rejectsTheMessageWhenTelegramReportsAnApiError() {
        server.expect(requestTo(SEND_URL))
                .andRespond(withSuccess(
                        "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.send("999", "Hello", null))
                .isInstanceOfSatisfying(TelegramException.class, exception -> {
                    assertThat(exception.getMessage()).isEqualTo("Bad Request: chat not found");
                    assertThat(exception.getStatusCode()).isEqualTo(400);
                });
    }

    @Test
    void rejectsTheMessageWhenTelegramReturnsAnHttpError() {
        server.expect(requestTo(SEND_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(
                        "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}"));

        assertThatThrownBy(() -> client.send("999", "Hello", null))
                .isInstanceOfSatisfying(TelegramException.class, exception -> {
                    assertThat(exception.getMessage()).isEqualTo("Unauthorized");
                    assertThat(exception.getStatusCode()).isEqualTo(401);
                });
    }

    @Test
    void failsWhenNoChatIdIsConfiguredOrProvided() {
        TelegramProperties properties = new TelegramProperties(
                "123456:TEST-TOKEN", null, Duration.ofSeconds(10), 1, 100);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TelegramClient(properties, new ObjectMapper(), builder.build());

        assertThatThrownBy(() -> client.send(null, "Hello", null))
                .isInstanceOfSatisfying(TelegramException.class, exception ->
                        assertThat(exception.getMessage())
                                .isEqualTo("No Telegram chat id is configured and chatId was not provided."));
    }

    private String successResponse(long messageId, long chatId) {
        return """
                {"ok":true,"result":{"message_id":%d,"chat":{"id":%d}}}
                """.formatted(messageId, chatId);
    }
}
