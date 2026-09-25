package com.ai.service.telegram;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TelegramMessageRequest(
        @Pattern(
                regexp = "^$|^-?\\d{1,64}$",
                message = "chatId must be a valid Telegram chat id")
        String chatId,

        @NotBlank(message = "text is required")
        @Size(max = 4096, message = "text must be at most 4096 characters")
        String text,

        @Pattern(
                regexp = "^$|^(HTML|Markdown|MarkdownV2)$",
                message = "parseMode must be HTML, Markdown, or MarkdownV2")
        String parseMode
) {
}