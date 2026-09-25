package com.ai.service.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TelegramMessageDispatcherTests {

    @Test
    void spacesQueuedMessagesOneSecondApart() throws InterruptedException {
        TelegramClient client = mock(TelegramClient.class);
        List<Long> sentAt = new CopyOnWriteArrayList<>();
        CountDownLatch sent = new CountDownLatch(2);
        doAnswer(invocation -> {
                    sentAt.add(System.nanoTime());
                    sent.countDown();
                    return new TelegramSendResponse(1, 1);
                })
                .when(client)
                .send(any(), any(), any());
        TelegramMessageDispatcher dispatcher = new TelegramMessageDispatcher(
                client, new TelegramProperties("token", "chat", Duration.ofSeconds(10), 1, 2));

        dispatcher.dispatch("chat", "first", null);
        dispatcher.dispatch("chat", "second", null);

        assertThat(sent.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(sentAt.get(1) - sentAt.get(0)).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
        dispatcher.close();
    }
}
