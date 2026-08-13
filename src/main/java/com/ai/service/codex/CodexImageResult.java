package com.ai.service.codex;

public record CodexImageResult(
        byte[] content,
        String mediaType,
        String filename
) {
}
