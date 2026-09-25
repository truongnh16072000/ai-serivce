package com.ai.service.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ai.service.error.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class AppAccessInterceptorTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AppAccessInterceptor interceptor = new AppAccessInterceptor(new AppAccessProperties("work_planner"));
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .addInterceptors(interceptor)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void allowsTheConfiguredAppCode() throws Exception {
        mockMvc.perform(get("/api/test").header(AppAccessInterceptor.APP_CODE_HEADER, "work_planner"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAMissingAppCode() throws Exception {
        mockMvc.perform(get("/api/test"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("A valid X-APP-CODE header is required"));
    }

    @Test
    void rejectsAnIncorrectAppCode() throws Exception {
        mockMvc.perform(get("/api/test").header(AppAccessInterceptor.APP_CODE_HEADER, "another_app"))
                .andExpect(status().isForbidden());
    }

    @RestController
    private static class TestController {

        @GetMapping("/api/test")
        String test() {
            return "ok";
        }
    }
}
