package com.ai.service.telegram;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TelegramMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TelegramMessageDispatcher.class);
    private static final AtomicInteger threadSequence = new AtomicInteger();

    private final TelegramClient telegramClient;
    private final ThreadPoolExecutor executor;

    public TelegramMessageDispatcher(TelegramClient telegramClient, TelegramProperties properties) {
        this.telegramClient = telegramClient;
        this.executor = new ThreadPoolExecutor(
                properties.asyncThreads(),
                properties.asyncThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("telegram-sender-" + threadSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void dispatch(String chatId, String text, String parseMode) {
        telegramClient.validateDestination(chatId);
        try {
            executor.execute(() -> send(chatId, text, parseMode));
        } catch (RejectedExecutionException exception) {
            throw new TelegramQueueFullException();
        }
    }

    private void send(String chatId, String text, String parseMode) {
        try {
            TelegramSendResponse response = telegramClient.send(chatId, text, parseMode);
            log.debug("Telegram message {} delivered to chat {}", response.messageId(), response.chatId());
        } catch (TelegramException exception) {
            // Do not log the cause: HTTP client exception messages can contain the bot-token URL.
            log.error("Asynchronous Telegram delivery failed: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("Asynchronous Telegram delivery failed", exception);
        } finally {
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @PreDestroy
    void close() {
        executor.shutdown();
    }
}
