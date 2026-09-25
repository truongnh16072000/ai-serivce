package com.ai.service.telegram;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/telegram")
public class TelegramController {

    private final TelegramMessageDispatcher dispatcher;

    public TelegramController(TelegramMessageDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping(
            path = "/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> sendMessage(@Valid @RequestBody TelegramMessageRequest request) {
        dispatcher.dispatch(request.chatId(), request.text(), request.parseMode());
        return ResponseEntity.accepted().build();
    }
}
