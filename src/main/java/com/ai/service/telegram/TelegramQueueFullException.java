package com.ai.service.telegram;

public class TelegramQueueFullException extends RuntimeException {

    public TelegramQueueFullException() {
        super("The Telegram delivery queue is full. Try again later.");
    }
}
