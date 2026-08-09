package com.ai.service.conversation;

public class ConversationBusyException extends RuntimeException {

    public ConversationBusyException() {
        super("This conversation is already processing another message.");
    }
}
