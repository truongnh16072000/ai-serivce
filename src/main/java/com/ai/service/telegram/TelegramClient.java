package com.ai.service.telegram;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TelegramClient {

    private static final String SEND_MESSAGE_URL = "https://api.telegram.org/bot%s/sendMessage";

    private final TelegramProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public TelegramClient(TelegramProperties properties, ObjectMapper objectMapper, RestClient restClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    public TelegramSendResponse send(String chatId, String text, String parseMode) {
        String targetChatId = resolveChatId(chatId);
        validateBotToken();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", targetChatId);
        payload.put("text", text);
        if (parseMode != null && !parseMode.isBlank()) {
            payload.put("parse_mode", parseMode);
        }
        try {
            String body = restClient.post()
                    .uri(URI.create(SEND_MESSAGE_URL.formatted(properties.botToken())))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleErrorResponse)
                    .body(String.class);
            return parseResponse(body);
        } catch (RestClientException exception) {
            throw new TelegramException("Could not reach the Telegram Bot API.", exception);
        }
    }

    public void validateDestination(String chatId) {
        validateBotToken();
        resolveChatId(chatId);
    }

    private void validateBotToken() {
        if (properties.botToken() == null || properties.botToken().isBlank()) {
            throw new TelegramException("The Telegram bot token is not configured.");
        }
    }

    private String resolveChatId(String requestedChatId) {
        if (requestedChatId != null && !requestedChatId.isBlank()) {
            return requestedChatId;
        }
        String configured = properties.defaultChatId();
        if (configured == null || configured.isBlank()) {
            throw new TelegramException("No Telegram chat id is configured and chatId was not provided.");
        }
        return configured;
    }

    private void handleErrorResponse(HttpRequest request, ClientHttpResponse response) throws IOException {
        throw new TelegramException(readErrorDescription(response), response.getStatusCode().value());
    }

    private String readErrorDescription(ClientHttpResponse response) throws IOException {
        int statusCode = response.getStatusCode().value();
        String body = "";
        try {
            body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "Telegram returned HTTP " + statusCode;
        }
        if (body.isBlank()) {
            return "Telegram returned HTTP " + statusCode;
        }
        try {
            JsonNode json = objectMapper.readTree(body);
            String description = json.path("description").stringValue("");
            return description.isBlank() ? body : description;
        } catch (JacksonException exception) {
            return body;
        }
    }

    private TelegramSendResponse parseResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new TelegramException("Telegram returned an empty response.");
        }
        try {
            JsonNode json = objectMapper.readTree(body);
            if (!json.path("ok").booleanValue(false)) {
                String description = json.path("description")
                        .stringValue("Telegram rejected the message");
                throw new TelegramException(description, json.path("error_code").intValue(0));
            }
            long messageId = json.path("result").path("message_id").longValue(0);
            long chatId = json.path("result").path("chat").path("id").longValue(0);
            if (messageId == 0) {
                throw new TelegramException("Telegram returned an unexpected response.");
            }
            return new TelegramSendResponse(messageId, chatId);
        } catch (JacksonException exception) {
            throw new TelegramException("Telegram returned a malformed response.", exception);
        }
    }
}
