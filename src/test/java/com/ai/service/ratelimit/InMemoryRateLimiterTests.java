package com.ai.service.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTests {

    private final AtomicLong nanoTime = new AtomicLong();
    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties(
                true, 2, Duration.ofSeconds(10), 100, Duration.ofMinutes(1));
        rateLimiter = new InMemoryRateLimiter(properties, nanoTime::get);
    }

    @Test
    void rejectsRequestsAfterTheBucketIsEmpty() {
        RateLimitDecision first = rateLimiter.tryAcquire("client-a");
        RateLimitDecision second = rateLimiter.tryAcquire("client-a");
        RateLimitDecision rejected = rateLimiter.tryAcquire("client-a");

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isZero();
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(5);
        assertThat(rejected.resetAfterSeconds()).isEqualTo(10);
    }

    @Test
    void continuouslyRefillsTokens() {
        rateLimiter.tryAcquire("client-a");
        rateLimiter.tryAcquire("client-a");
        nanoTime.addAndGet(Duration.ofSeconds(5).toNanos());

        RateLimitDecision refilled = rateLimiter.tryAcquire("client-a");

        assertThat(refilled.allowed()).isTrue();
        assertThat(refilled.remaining()).isZero();
    }

    @Test
    void keepsIndependentBucketsForEachClient() {
        rateLimiter.tryAcquire("client-a");
        rateLimiter.tryAcquire("client-a");

        RateLimitDecision otherClient = rateLimiter.tryAcquire("client-b");

        assertThat(otherClient.allowed()).isTrue();
        assertThat(otherClient.remaining()).isEqualTo(1);
    }
}
