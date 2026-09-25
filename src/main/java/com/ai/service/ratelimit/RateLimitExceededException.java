package com.ai.service.ratelimit;

public class RateLimitExceededException extends RuntimeException {

    private final int limit;
    private final int remaining;
    private final long resetAfterSeconds;
    private final long retryAfterSeconds;

    RateLimitExceededException(RateLimitDecision decision) {
        super("API rate limit exceeded");
        this.limit = decision.limit();
        this.remaining = decision.remaining();
        this.resetAfterSeconds = decision.resetAfterSeconds();
        this.retryAfterSeconds = decision.retryAfterSeconds();
    }

    public int getLimit() {
        return limit;
    }

    public int getRemaining() {
        return remaining;
    }

    public long getResetAfterSeconds() {
        return resetAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
