package com.ai.service.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60") @Min(1) int capacity,
        @DefaultValue("1m") @NotNull Duration refillPeriod,
        @DefaultValue("10000") @Min(1) long maximumCacheSize,
        @DefaultValue("10m") @NotNull Duration cacheExpiry) {

    public RateLimitProperties {
        if (refillPeriod != null && (refillPeriod.isZero() || refillPeriod.isNegative())) {
            throw new IllegalArgumentException("app.rate-limit.refill-period must be positive");
        }
        if (cacheExpiry != null && (cacheExpiry.isZero() || cacheExpiry.isNegative())) {
            throw new IllegalArgumentException("app.rate-limit.cache-expiry must be positive");
        }
    }
}
