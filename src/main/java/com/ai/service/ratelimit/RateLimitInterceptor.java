package com.ai.service.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
class RateLimitInterceptor implements HandlerInterceptor {

    static final String LIMIT_HEADER = "RateLimit-Limit";
    static final String REMAINING_HEADER = "RateLimit-Remaining";
    static final String RESET_HEADER = "RateLimit-Reset";

    private static final String APPLIED_ATTRIBUTE = RateLimitInterceptor.class.getName() + ".applied";

    private final RateLimitProperties properties;
    private final InMemoryRateLimiter rateLimiter;

    RateLimitInterceptor(RateLimitProperties properties, InMemoryRateLimiter rateLimiter) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.enabled()
                || HttpMethod.OPTIONS.matches(request.getMethod())
                || request.getAttribute(APPLIED_ATTRIBUTE) != null) {
            return true;
        }

        request.setAttribute(APPLIED_ATTRIBUTE, Boolean.TRUE);
        RateLimitDecision decision = rateLimiter.tryAcquire(clientKey(request));
        writeHeaders(response, decision);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision);
        }
        return true;
    }

    static void writeHeaders(HttpServletResponse response, RateLimitDecision decision) {
        response.setHeader(LIMIT_HEADER, Integer.toString(decision.limit()));
        response.setHeader(REMAINING_HEADER, Integer.toString(decision.remaining()));
        response.setHeader(RESET_HEADER, Long.toString(decision.resetAfterSeconds()));
        if (!decision.allowed()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        }
    }

    private String clientKey(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }
}
