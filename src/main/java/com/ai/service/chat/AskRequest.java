package com.ai.service.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskRequest(
        @Size(max = 100, message = "conversationId must be at most 100 characters")
        String conversationId,

        @NotBlank(message = "message is required")
        @Size(max = 10_000, message = "message must be at most 10000 characters")
        String message
) {
}
