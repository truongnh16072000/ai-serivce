package com.ai.service.codex;

import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

@Component
class CodexCapacity {

    private final Semaphore permits;

    CodexCapacity(CodexProperties properties) {
        permits = new Semaphore(properties.maxConcurrentRequests(), true);
    }

    void acquire() {
        if (!permits.tryAcquire()) {
            throw new CodexBusyException();
        }
    }

    void release() {
        permits.release();
    }
}
