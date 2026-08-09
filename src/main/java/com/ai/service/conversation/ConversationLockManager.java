package com.ai.service.conversation;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
class ConversationLockManager {

    private static final int STRIPE_COUNT = 1_024;

    private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];

    ConversationLockManager() {
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock(true);
        }
    }

    Lease tryAcquire(String conversationId) {
        ReentrantLock lock = locks[Math.floorMod(conversationId.hashCode(), locks.length)];
        if (!lock.tryLock()) {
            throw new ConversationBusyException();
        }
        return lock::unlock;
    }

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
