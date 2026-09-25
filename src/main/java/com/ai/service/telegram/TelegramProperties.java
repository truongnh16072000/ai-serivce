package com.ai.service.telegram;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(
        @DefaultValue("")
        @Pattern(
                regexp = "^$|^\\d+:[A-Za-z0-9_-]+$",
                message = "botToken must be a valid Telegram bot token")
        String botToken,
        @DefaultValue("")
        @Pattern(
                regexp = "^$|^-?\\d{1,64}$",
                message = "defaultChatId must be a valid Telegram chat id")
        String defaultChatId,
        @DefaultValue("10s") @NotNull Duration timeout,
        @DefaultValue("1") @Min(1) @Max(1) int asyncThreads,
        @DefaultValue("100") @Min(1) @Max(10000) int queueCapacity
) {
    @AssertTrue(message = "app.telegram.timeout must be between 1 second and 60 seconds")
    public boolean isTimeoutValid() {
        return timeout != null
                && timeout.compareTo(Duration.ofSeconds(1)) >= 0
                && timeout.compareTo(Duration.ofSeconds(60)) <= 0;
    }
}
