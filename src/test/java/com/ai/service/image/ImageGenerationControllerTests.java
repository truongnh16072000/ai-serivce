package com.ai.service.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageGenerationControllerTests {

    private MockMvc mockMvc;
    private CodexImageGenerator imageGenerator;
    private byte[] generatedPng;

    @BeforeEach
    void setUp() throws IOException {
        generatedPng = png();
        imageGenerator = mock(CodexImageGenerator.class);
        when(imageGenerator.generate(anyString())).thenReturn(new CodexImageResult(
                generatedPng, "image/png", "codex-image.png"));
        when(imageGenerator.generate(anyString(), anyList())).thenReturn(new CodexImageResult(
                generatedPng, "image/png", "codex-image.png"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ImageGenerationController(imageGenerator))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void downloadsTheGeneratedImage() throws Exception {
        byte[] jpeg = mockMvc.perform(post("/api/v1/images/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"A lighthouse during a storm\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"codex-image.jpg\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(jpeg).startsWith((byte) 0xff, (byte) 0xd8);
        assertThat(jpeg.length).isLessThan(generatedPng.length);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(decoded.getWidth()).isEqualTo(128);
        assertThat(decoded.getHeight()).isEqualTo(128);
        assertThat(new Color(decoded.getRGB(8, 8))).isEqualTo(Color.WHITE);
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
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
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
    @Test
    void backgroundsSupportPromptsAndReferences() throws Exception {
        mockMvc.perform(post("/api/v1/images/backgrounds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"A warm studio\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
        MockMultipartFile prompt = new MockMultipartFile("prompt", "", MediaType.TEXT_PLAIN_VALUE, "Warm studio".getBytes());
        MockMultipartFile reference = new MockMultipartFile("images", "reference.png", MediaType.IMAGE_PNG_VALUE,
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1});
        mockMvc.perform(multipart("/api/v1/images/backgrounds").file(prompt).file(reference))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
        mockMvc.perform(post("/api/v1/images/backgrounds")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passesThroughJpegAndWebpOutputs() throws Exception {
        for (String type : new String[] {"image/jpeg", "image/webp"}) {
            byte[] bytes = "already compressed".getBytes();
            String filename = type.equals("image/jpeg") ? "codex-image.jpg" : "codex-image.webp";
            when(imageGenerator.generate(anyString())).thenReturn(new CodexImageResult(bytes, type, filename));
            mockMvc.perform(post("/api/v1/images/generations")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"A studio\"}"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(type))
                    .andExpect(content().bytes(bytes))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + filename + "\""));
        }
    }

    @Test
    void returnsBadGatewayForUnreadableGeneratedPng() throws Exception {
        when(imageGenerator.generate(anyString())).thenReturn(new CodexImageResult(
                "not an image".getBytes(), "image/png", "codex-image.png"));
        mockMvc.perform(post("/api/v1/images/generations")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"A studio\"}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void removesBackgroundWithoutFlatteningTransparency() throws Exception {
        byte[] png = mockMvc.perform(multipart("/api/v1/images/remove-background")
                        .file(new MockMultipartFile("image", "source.png", "image/png", generatedPng)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"codex-image.png\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(png).isEqualTo(generatedPng);
        assertThat(ImageIO.read(new ByteArrayInputStream(png)).getRGB(8, 8) >>> 24).isZero();
        verify(imageGenerator).generate(argThat(prompt -> prompt.contains("transparent_background=true")),
                argThat(images -> images.size() == 1 && java.util.Arrays.equals(images.getFirst().content(), generatedPng)));
    }

    @Test
    void rejectsOpaqueOrUnreadableBackgroundRemovalResults() throws Exception {
        ByteArrayOutputStream opaque = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", opaque);
        for (CodexImageResult result : List.of(
                new CodexImageResult(opaque.toByteArray(), "image/png", "opaque.png"),
                new CodexImageResult("bad image".getBytes(), "image/png", "bad.png"),
                new CodexImageResult("jpeg".getBytes(), "image/jpeg", "opaque.jpg"))) {
            when(imageGenerator.generate(anyString(), anyList())).thenReturn(result);
            mockMvc.perform(multipart("/api/v1/images/remove-background")
                            .file(new MockMultipartFile("image", "source.png", "image/png", generatedPng)))
                    .andExpect(status().isBadGateway());
        }
    }

    @Test
    void replacesBackgroundWithPromptAndOptionalBackgroundReference() throws Exception {
        MockMultipartFile source = new MockMultipartFile("image", "source.png", "image/png", generatedPng);
        MockMultipartFile prompt = new MockMultipartFile("prompt", "A beach".getBytes());
        mockMvc.perform(multipart("/api/v1/images/replace-background").file(source).file(prompt))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));
        byte[] background = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};
        mockMvc.perform(multipart("/api/v1/images/replace-background").file(source).file(prompt)
                        .file(new MockMultipartFile("background", "beach.jpg", "image/jpeg", background)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));
        verify(imageGenerator).generate(argThat(text -> text.contains("A beach") && text.contains("SECOND image")),
                argThat(images -> images.size() == 2
                        && java.util.Arrays.equals(images.get(0).content(), generatedPng)
                        && java.util.Arrays.equals(images.get(1).content(), background)));
    }

    @Test
    void removesObjectsDescribedByPrompt() throws Exception {
        mockMvc.perform(multipart("/api/v1/images/remove-object")
                        .file(new MockMultipartFile("image", "source.png", "image/png", generatedPng))
                        .file(new MockMultipartFile("prompt", "The yellow ball on the right".getBytes())))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));
        verify(imageGenerator).generate(argThat(text -> text.contains("The yellow ball on the right")
                        && text.contains("reconstruct")), argThat(images -> images.size() == 1));
    }

    @Test
    void rejectsInvalidEditInputsBeforeCallingCodex() throws Exception {
        MockMultipartFile valid = new MockMultipartFile("image", "source.png", "image/png", generatedPng);
        for (String path : List.of("remove-background", "replace-background", "remove-object")) {
            mockMvc.perform(multipart("/api/v1/images/" + path)
                            .file(new MockMultipartFile("prompt", "A scene".getBytes())))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(multipart("/api/v1/images/" + path)
                            .file(new MockMultipartFile("image", new byte[0]))
                            .file(new MockMultipartFile("prompt", "A scene".getBytes())))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(multipart("/api/v1/images/" + path)
                            .file(new MockMultipartFile("image", "bad.txt", "text/plain", "not an image".getBytes()))
                            .file(new MockMultipartFile("prompt", "A scene".getBytes())))
                    .andExpect(status().isBadRequest());
        }
        for (String path : List.of("replace-background", "remove-object")) {
            for (String prompt : List.of(" ", "x".repeat(9501))) {
                mockMvc.perform(multipart("/api/v1/images/" + path).file(valid)
                                .file(new MockMultipartFile("prompt", prompt.getBytes())))
                        .andExpect(status().isBadRequest());
            }
        }
        mockMvc.perform(multipart("/api/v1/images/remove-background")
                        .file(new MockMultipartFile("image", "large.png", "image/png", new byte[10 * 1024 * 1024 + 1])))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/v1/images/replace-background").file(valid)
                        .file(new MockMultipartFile("prompt", "A scene".getBytes()))
                        .file(new MockMultipartFile("background", "bad.txt", "text/plain", "bad".getBytes())))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(imageGenerator);
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(1);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, x < 32 && y < 32 ? 0 : 0xff000000 | random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
