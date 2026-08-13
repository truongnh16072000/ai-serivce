package com.ai.service.image;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ai.service.codex.CodexImageGenerator;
import com.ai.service.codex.CodexImageResult;
import com.ai.service.error.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageGenerationControllerTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CodexImageGenerator imageGenerator = mock(CodexImageGenerator.class);
        when(imageGenerator.generate(anyString())).thenReturn(new CodexImageResult(
                "image".getBytes(), "image/png", "codex-image.png"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ImageGenerationController(imageGenerator))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void downloadsTheGeneratedImage() throws Exception {
        mockMvc.perform(post("/api/v1/images/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"A lighthouse during a storm\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes("image".getBytes()))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"codex-image.png\""))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void rejectsBlankPrompts() throws Exception {
        mockMvc.perform(post("/api/v1/images/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.prompt").value("prompt is required"));
    }
}
