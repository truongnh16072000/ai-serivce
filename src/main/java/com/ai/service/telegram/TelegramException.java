package com.ai.service.telegram;

public class TelegramException extends RuntimeException {

    private final int statusCode;

    public TelegramException(String message) {
        this(message, 0);
    }

    public TelegramException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public TelegramException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    private TelegramException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}