package com.ai.service.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ai.service.error.ApiExceptionHandler;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class RateLimitInterceptorTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties(
                true, 2, Duration.ofMinutes(1), 100, Duration.ofMinutes(10));
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
                properties, new InMemoryRateLimiter(properties));
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .addInterceptors(interceptor)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsQuotaHeadersAndAProblemResponseWhenExhausted() throws Exception {
        mockMvc.perform(get("/api/test").with(request -> {
                    request.setRemoteAddr("192.0.2.1");
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitInterceptor.LIMIT_HEADER, "2"))
                .andExpect(header().string(RateLimitInterceptor.REMAINING_HEADER, "1"));

        mockMvc.perform(get("/api/test").with(request -> {
                    request.setRemoteAddr("192.0.2.1");
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitInterceptor.REMAINING_HEADER, "0"));

        mockMvc.perform(get("/api/test").with(request -> {
                    request.setRemoteAddr("192.0.2.1");
                    return request;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value("API rate limit exceeded"))
                .andExpect(jsonPath("$.limit").value(2));
    }

    @Test
    void doesNotShareQuotaBetweenRemoteAddresses() throws Exception {
        for (int requestNumber = 0; requestNumber < 2; requestNumber++) {
            mockMvc.perform(get("/api/test").with(request -> {
                        request.setRemoteAddr("192.0.2.1");
                        return request;
                    }))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/test").with(request -> {
                    request.setRemoteAddr("192.0.2.2");
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitInterceptor.REMAINING_HEADER, "1"));
    }

    @RestController
    private static class TestController {

        @GetMapping("/api/test")
        String test() {
            return "ok";
        }
    }
}
