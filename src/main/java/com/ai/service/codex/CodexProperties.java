package com.ai.service.codex;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.codex")
public record CodexProperties(
        @NotBlank String executable,
        @NotNull Path workspaceRoot,
        @NotNull Duration timeout,
        @Min(1) @Max(100) int maxConcurrentRequests,
        @Min(1_024) @Max(100_000_000) int maxOutputBytes
) {
    @AssertTrue(message = "app.codex.timeout must be between 1 second and 10 minutes")
    public boolean isTimeoutValid() {
        return timeout != null
                && timeout.compareTo(Duration.ofSeconds(1)) >= 0
                && timeout.compareTo(Duration.ofMinutes(10)) <= 0;
    }
}
