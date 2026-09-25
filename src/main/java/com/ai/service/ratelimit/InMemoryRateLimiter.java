package com.ai.service.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class InMemoryRateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final RateLimitProperties properties;
    private final Cache<String, TokenBucket> buckets;
    private final LongSupplier nanoTime;

    @Autowired
    InMemoryRateLimiter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    InMemoryRateLimiter(RateLimitProperties properties, LongSupplier nanoTime) {
        this.properties = properties;
        this.nanoTime = nanoTime;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(properties.maximumCacheSize())
                .expireAfterAccess(properties.cacheExpiry())
                .build();
    }

    RateLimitDecision tryAcquire(String clientKey) {
        long now = nanoTime.getAsLong();
        TokenBucket bucket = buckets.asMap().computeIfAbsent(
                clientKey, ignored -> new TokenBucket(properties.capacity(), now));
        return bucket.tryAcquire(now, properties.capacity(), properties.refillPeriod().toNanos());
    }

    private static final class TokenBucket {

        private double tokens;
        private long lastRefillNanos;

        private TokenBucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefillNanos = now;
        }

        private synchronized RateLimitDecision tryAcquire(long now, int capacity, long refillPeriodNanos) {
            long elapsed = Math.max(0, now - lastRefillNanos);
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + ((double) elapsed * capacity / refillPeriodNanos));
                lastRefillNanos = now;
            }

            boolean allowed = tokens >= 1.0;
            if (allowed) {
                tokens -= 1.0;
            }

            int remaining = Math.max(0, (int) Math.floor(tokens));
            long resetAfter = secondsForTokens(capacity - tokens, capacity, refillPeriodNanos);
            long retryAfter = allowed ? 0 : secondsForTokens(1.0 - tokens, capacity, refillPeriodNanos);
            return new RateLimitDecision(allowed, capacity, remaining, resetAfter, retryAfter);
        }

        private static long secondsForTokens(double tokens, int capacity, long refillPeriodNanos) {
            if (tokens <= 0) {
                return 0;
            }
            double nanos = tokens * refillPeriodNanos / capacity;
            return Math.max(1, (long) Math.ceil(nanos / NANOS_PER_SECOND));
        }
    }
}
