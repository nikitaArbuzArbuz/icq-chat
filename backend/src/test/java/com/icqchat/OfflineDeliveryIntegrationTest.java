package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.service.MessageService;
import com.icqchat.support.Api;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.icqchat.support.Api.statusOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("Досылка сообщений офлайн-получателю")
class OfflineDeliveryIntegrationTest {

    @Test
    @DisplayName("при подключении получатель получает недоставленное одной пачкой по порядку, отправитель узнаёт о доставке")
    void undeliveredMessagesArriveOnConnect() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice)) {
            long first = aliceTab.sendMessage(bob, "первое").path("id").asLong();
            long second = aliceTab.sendMessage(bob, "второе").path("id").asLong();
            assertEquals("SENT", statusOf(alice, bob.id(), first));
            aliceTab.assertNoEvent("delivered");

            try (ChatWs bobTab = ChatWs.connect(bob)) {
                JsonNode batch = bobTab.await("undelivered");
                assertEquals(List.of(first, second), ChatWs.messageIds(batch));
                assertFalse(batch.path("hasMore").asBoolean());

                bobTab.delivered(first, second);
                Set<Long> deliveredIds = new HashSet<>();
                aliceTab.await("delivered").path("messageIds").forEach(id -> deliveredIds.add(id.asLong()));
                assertEquals(Set.of(first, second), deliveredIds);
            }
            assertEquals("DELIVERED", statusOf(alice, bob.id(), first));
            assertEquals("DELIVERED", statusOf(alice, bob.id(), second));
        }

        // Уже доставленное повторно не присылается.
        try (ChatWs bobAgain = ChatWs.connect(bob)) {
            bobAgain.assertNoEvent("undelivered");
        }
    }

    @Test
    @DisplayName("пока получатель не подтвердил доставку, сообщение присылается при каждом подключении")
    void resentUntilConfirmed() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();

        long id;
        try (ChatWs aliceTab = ChatWs.connect(alice)) {
            id = aliceTab.sendMessage(bob, "дойди до меня").path("id").asLong();
        }
        try (ChatWs bobTab = ChatWs.connect(bob)) {
            assertEquals(List.of(id), ChatWs.messageIds(bobTab.await("undelivered")));
            // Вкладку закрыли, не успев подтвердить доставку.
        }
        try (ChatWs bobTab = ChatWs.connect(bob)) {
            assertEquals(List.of(id), ChatWs.messageIds(bobTab.await("undelivered")));
        }
        assertEquals("SENT", statusOf(alice, bob.id(), id));
    }

    @Test
    @DisplayName("много недоставленного приходит пачками по 100, следующая только после подтверждения предыдущей")
    void batchesWithFlowControl() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        int batchSize = MessageService.UNDELIVERED_BATCH_SIZE;
        int total = batchSize * 2 + 50;

        List<Long> sent = new ArrayList<>();
        try (ChatWs aliceTab = ChatWs.connect(alice)) {
            for (int i = 0; i < total; i++) {
                sent.add(aliceTab.sendMessage(bob, "сообщение " + i).path("id").asLong());
            }
        }

        List<Long> received = new ArrayList<>();
        List<Integer> batchSizes = new ArrayList<>();
        try (ChatWs bobTab = ChatWs.connect(bob)) {
            boolean hasMore = true;
            while (hasMore) {
                JsonNode batch = bobTab.await("undelivered");
                List<Long> ids = ChatWs.messageIds(batch);
                batchSizes.add(ids.size());
                received.addAll(ids);
                hasMore = batch.path("hasMore").asBoolean();
                if (hasMore) {
                    // Пока пачка не подтверждена, следующая не приходит.
                    bobTab.assertNoEvent("undelivered");
                }
                bobTab.delivered(ids.stream().mapToLong(Long::longValue).toArray());
            }
            bobTab.assertNoEvent("undelivered");
        }

        assertEquals(sent, received);
        assertEquals(List.of(batchSize, batchSize, 50), batchSizes);
        assertEquals("DELIVERED", statusOf(alice, bob.id(), sent.get(0)));
        assertEquals("DELIVERED", statusOf(alice, bob.id(), sent.get(total - 1)));
    }
}
