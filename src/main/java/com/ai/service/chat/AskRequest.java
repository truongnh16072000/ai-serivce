package com.ai.service.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AskRequest(
        @Size(max = 100, message = "conversationId must be at most 100 characters")
        @Pattern(
                regexp = "^$|^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$",
                message = "conversationId contains unsupported characters")
        String conversationId,

        @NotBlank(message = "message is required")
        @Size(max = 10_000, message = "message must be at most 10000 characters")
        String message
) {
}
