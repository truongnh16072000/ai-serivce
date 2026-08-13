package com.ai.service.codex;

import java.util.List;

public interface CodexImageGenerator {

    CodexImageResult generate(String prompt);

    CodexImageResult generate(String prompt, List<CodexReferenceImage> referenceImages);
}
