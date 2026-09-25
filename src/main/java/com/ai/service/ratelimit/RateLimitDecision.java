package com.ai.service.ratelimit;

record RateLimitDecision(
        boolean allowed,
        int limit,
        int remaining,
        long resetAfterSeconds,
        long retryAfterSeconds) {
}
