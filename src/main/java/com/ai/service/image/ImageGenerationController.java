package com.ai.service.image;

import com.ai.service.codex.CodexException;
import com.ai.service.codex.CodexImageGenerator;
import com.ai.service.codex.CodexImageResult;
import com.ai.service.codex.CodexReferenceImage;
import jakarta.validation.Valid;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/images")
public class ImageGenerationController {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationController.class);

    private static final int MAX_REFERENCE_IMAGES = 5;
    private static final int MAX_REFERENCE_IMAGE_BYTES = 10 * 1024 * 1024;

    private final CodexImageGenerator imageGenerator;

    public ImageGenerationController(CodexImageGenerator imageGenerator) {
        this.imageGenerator = imageGenerator;
    }

    @PostMapping(
            path = "/generations",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> generate(@Valid @RequestBody GenerateImageRequest request) {
        return download(imageGenerator.generate(request.prompt()));
    }

    @PostMapping(
            path = "/generations",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> generateWithReferences(
            @RequestPart("prompt") String prompt,
            @RequestPart("images") List<MultipartFile> images) {
        if (prompt == null || prompt.isBlank()) {
            throw new ImageRequestException("prompt is required");
        }
        if (prompt.length() > 10_000) {
            throw new ImageRequestException("prompt must be at most 10000 characters");
        }
        if (images == null || images.isEmpty()) {
            throw new ImageRequestException("at least one reference image is required");
        }
        if (images.size() > MAX_REFERENCE_IMAGES) {
            throw new ImageRequestException("at most 5 reference images are allowed");
        }

        List<CodexReferenceImage> references = new ArrayList<>(images.size());
        for (MultipartFile image : images) {
            references.add(toReferenceImage(image));
        }
        return download(imageGenerator.generate(prompt, references));
    }

    @PostMapping(path = "/backgrounds", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> generateBackground(@Valid @RequestBody GenerateImageRequest request) {
        return generate(new GenerateImageRequest(backgroundPrompt(request.prompt())));
    }

    @PostMapping(path = "/backgrounds", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> generateBackgroundWithReference(
            @RequestPart("prompt") String prompt,
            @RequestPart("images") List<MultipartFile> images) {
        // Validate the user's prompt before adding server-owned instructions.
        if (prompt == null || prompt.isBlank() || prompt.length() > 9500) {
            throw new ImageRequestException("prompt must contain 1 to 9500 characters");
        }
        return generateWithReferences(backgroundPrompt(prompt), images);
    }

    @PostMapping(path = "/remove-background", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> removeBackground(@RequestPart("image") MultipartFile image) {
        CodexReferenceImage source = toReferenceImage(image);
        CodexImageResult result = imageGenerator.generate(
                "EDIT the supplied source image. Remove only its background and retain the main foreground subject. "
                        + "Preserve the subject's appearance, fine edges, colors, pose and original canvas size. "
                        + "Return exactly one PNG cutout with a real transparent alpha channel. "
                        + "Set transparent_background=true in the image-generation tool. "
                        + "Do not add a white background, checkerboard, scenery or extra objects.",
                List.of(source));
        requireTransparentPng(result);
        return attachment(result);
    }

    @PostMapping(path = "/replace-background", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> replaceBackground(
            @RequestPart("image") MultipartFile image,
            @RequestPart("prompt") String prompt,
            @RequestPart(name = "background", required = false) MultipartFile background) {
        validateEditPrompt(prompt);
        List<CodexReferenceImage> references = new ArrayList<>();
        references.add(toReferenceImage(image));
        if (background != null) {
            references.add(toReferenceImage(background));
        }
        return download(imageGenerator.generate(
                "EDIT the FIRST supplied image as the source. Replace only its background. "
                        + "Preserve the foreground subject's identity, shape, pose, details and framing. "
                        + "Keep the source canvas size. Match lighting, perspective and contact shadows naturally. "
                        + "If a SECOND image is supplied, use it only as the replacement background. "
                        + "Return exactly one opaque edited image. Background instructions: " + prompt,
                references));
    }

    @PostMapping(path = "/remove-object", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> removeObject(
            @RequestPart("image") MultipartFile image,
            @RequestPart("prompt") String prompt) {
        validateEditPrompt(prompt);
        return download(imageGenerator.generate(
                "EDIT the supplied source image. Remove only the objects described below and their shadows or reflections. "
                        + "Naturally reconstruct the newly exposed background. Preserve all other subjects, details, "
                        + "composition and source canvas size. Return exactly one opaque edited image. "
                        + "Objects to remove (including their locations): " + prompt,
                List.of(toReferenceImage(image))));
    }

    private void validateEditPrompt(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 9500) {
            throw new ImageRequestException("prompt must contain 1 to 9500 characters");
        }
    }

    private void requireTransparentPng(CodexImageResult result) {
        if (!MediaType.IMAGE_PNG_VALUE.equals(result.mediaType())) {
            throw new CodexException("Background removal did not return a transparent PNG.");
        }
        try (var input = new ByteArrayInputStream(result.content())) {
            BufferedImage image = ImageIO.read(input);
            if (image != null && image.getColorModel().hasAlpha()) {
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        if ((image.getRGB(x, y) >>> 24) < 255) {
                            return;
                        }
                    }
                }
            }
        } catch (IOException exception) {
            throw new CodexException("Could not read the background-removal result.", exception);
        }
        throw new CodexException("Background removal returned an image without transparent pixels.");
    }

    private String backgroundPrompt(String prompt) {
        if (prompt.length() > 9500) {
            throw new ImageRequestException("prompt must contain 1 to 9500 characters");
        }
        return "Create only a photographic BACKGROUND for compositing a separate foreground subject. "
                + "Leave the central foreground empty, with no people, products, lettering or watermark. "
                + "Use any reference only for scenery, palette and lighting; do not copy its foreground subject. "
                + "Requested scene: " + prompt;
    }

    private ResponseEntity<byte[]> download(CodexImageResult result) {
        return attachment(compressPng(result));
    }

    private ResponseEntity<byte[]> attachment(CodexImageResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mediaType()))
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(result.filename()).build().toString())
                .body(result.content());
    }

    private CodexImageResult compressPng(CodexImageResult result) {
        if (!MediaType.IMAGE_PNG_VALUE.equals(result.mediaType())) {
            return result;
        }
        long startedAt = System.nanoTime();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var input = new ByteArrayInputStream(result.content());
             var bytes = new ByteArrayOutputStream();
             var output = new MemoryCacheImageOutputStream(bytes)) {
            BufferedImage source = ImageIO.read(input);
            if (source == null) {
                throw new CodexException("Codex returned an unreadable PNG image.");
            }
            BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.85f);
            writer.setOutput(output);
            writer.write(null, new IIOImage(rgb, null, null), parameters);
            output.flush();
            byte[] jpeg = bytes.toByteArray();
            log.info("Image compressed from PNG {} bytes to JPEG {} bytes in {} ms",
                    result.content().length, jpeg.length, (System.nanoTime() - startedAt) / 1_000_000);
            return new CodexImageResult(jpeg, MediaType.IMAGE_JPEG_VALUE, "codex-image.jpg");
        } catch (IOException exception) {
            throw new CodexException("Could not encode the generated image as JPEG.", exception);
        } finally {
            writer.dispose();
        }
    }

    private CodexReferenceImage toReferenceImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new ImageRequestException("reference images must not be empty");
        }
        if (image.getSize() > MAX_REFERENCE_IMAGE_BYTES) {
            throw new ImageRequestException("each reference image must be at most 10 MB");
        }
        try {
            byte[] content = image.getBytes();
            return new CodexReferenceImage(content, detectExtension(content));
        } catch (IOException exception) {
            throw new ImageRequestException("could not read a reference image");
        }
    }

    private String detectExtension(byte[] content) {
        if (content.length >= 8
                && (content[0] & 0xff) == 0x89
                && content[1] == 'P'
                && content[2] == 'N'
                && content[3] == 'G') {
            return "png";
        }
        if (content.length >= 3
                && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (content.length >= 12
                && content[0] == 'R'
                && content[1] == 'I'
                && content[2] == 'F'
                && content[3] == 'F'
                && content[8] == 'W'
                && content[9] == 'E'
                && content[10] == 'B'
                && content[11] == 'P') {
            return "webp";
        }
        throw new ImageRequestException("reference images must be PNG, JPEG, or WebP");
    }
}
