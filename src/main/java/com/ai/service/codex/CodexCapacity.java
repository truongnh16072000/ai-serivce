package com.ai.service.codex;

import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

@Component
class CodexCapacity {

    final Semaphore chat;
    final Semaphore image;

    CodexCapacity(CodexProperties properties) {
        chat = new Semaphore(properties.maxConcurrentChatRequests(), true);
        image = new Semaphore(properties.maxConcurrentImageRequests(), true);
    }
}
