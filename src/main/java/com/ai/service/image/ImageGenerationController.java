package com.ai.service.image;

import com.ai.service.codex.CodexImageGenerator;
import com.ai.service.codex.CodexImageResult;
import com.ai.service.codex.CodexReferenceImage;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    private ResponseEntity<byte[]> download(CodexImageResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mediaType()))
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(result.filename()).build().toString())
                .body(result.content());
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
