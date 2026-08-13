package com.ai.service.image;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenerateImageRequest(
        @NotBlank(message = "prompt is required")
        @Size(max = 10_000, message = "prompt must be at most 10000 characters")
        String prompt
) {
}
