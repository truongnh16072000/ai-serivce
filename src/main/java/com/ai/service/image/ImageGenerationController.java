package com.ai.service.image;

import com.ai.service.codex.CodexImageGenerator;
import com.ai.service.codex.CodexImageResult;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/images")
public class ImageGenerationController {

    private final CodexImageGenerator imageGenerator;

    public ImageGenerationController(CodexImageGenerator imageGenerator) {
        this.imageGenerator = imageGenerator;
    }

    @PostMapping(
            path = "/generations",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> generate(@Valid @RequestBody GenerateImageRequest request) {
        CodexImageResult result = imageGenerator.generate(request.prompt());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mediaType()))
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(result.filename()).build().toString())
                .body(result.content());
    }
}
