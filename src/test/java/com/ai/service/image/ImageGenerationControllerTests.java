package com.ai.service.image;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ai.service.codex.CodexImageGenerator;
import com.ai.service.codex.CodexImageResult;
import com.ai.service.error.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageGenerationControllerTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CodexImageGenerator imageGenerator = mock(CodexImageGenerator.class);
        when(imageGenerator.generate(anyString())).thenReturn(new CodexImageResult(
                "image".getBytes(), "image/png", "codex-image.png"));
        when(imageGenerator.generate(anyString(), anyList())).thenReturn(new CodexImageResult(
                "referenced image".getBytes(), "image/png", "codex-image.png"));
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

    @Test
    void generatesAnImageFromMultipartReferenceImages() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1};
        MockMultipartFile prompt = new MockMultipartFile(
                "prompt", "", MediaType.TEXT_PLAIN_VALUE, "Use this color palette".getBytes());
        MockMultipartFile image = new MockMultipartFile(
                "images", "reference.png", MediaType.IMAGE_PNG_VALUE, png);

        mockMvc.perform(multipart("/api/v1/images/generations").file(prompt).file(image))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes("referenced image".getBytes()));
    }

    @Test
    void rejectsUnsupportedReferenceImageContent() throws Exception {
        MockMultipartFile prompt = new MockMultipartFile(
                "prompt", "", MediaType.TEXT_PLAIN_VALUE, "Use this".getBytes());
        MockMultipartFile image = new MockMultipartFile(
                "images", "reference.txt", MediaType.TEXT_PLAIN_VALUE, "not an image".getBytes());

        mockMvc.perform(multipart("/api/v1/images/generations").file(prompt).file(image))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("reference images must be PNG, JPEG, or WebP"));
    }
}
