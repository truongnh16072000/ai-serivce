package com.ai.service.codex;

public class CodexTimeoutException extends CodexException {

    public CodexTimeoutException() {
        super("The AI response timed out.");
    }
}
