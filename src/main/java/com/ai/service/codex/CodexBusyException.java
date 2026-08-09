package com.ai.service.codex;

public class CodexBusyException extends CodexException {

    public CodexBusyException() {
        super("The AI service is at capacity. Please try again later.");
    }
}
