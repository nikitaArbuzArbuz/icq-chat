package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.support.Api;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.icqchat.support.Api.statusOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Досылка сообщений офлайн-получателю")
class OfflineDeliveryIntegrationTest {

    @Test
    @DisplayName("при подключении получатель получает всё недоставленное по порядку, отправитель узнаёт о доставке")
    void undeliveredMessagesArriveOnConnect() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice)) {
            long first = aliceTab.sendMessage(bob, "первое").path("id").asLong();
            long second = aliceTab.sendMessage(bob, "второе").path("id").asLong();
            assertEquals("SENT", statusOf(alice, bob.id(), first));
            assertEquals("SENT", statusOf(alice, bob.id(), second));
            aliceTab.assertNoEvent("delivered");

            try (ChatWs bobTab = ChatWs.connect(bob)) {
                bobTab.awaitMessage(first);
                bobTab.awaitMessage(second);
                List<Long> order = bobTab.received("message").stream().map(e -> e.at("/message/id").asLong()).toList();
                assertEquals(List.of(first, second), order);

                bobTab.delivered(first, second);
                JsonNode delivered = aliceTab.await("delivered");
                Set<Long> deliveredIds = new HashSet<>();
                delivered.path("messageIds").forEach(id -> deliveredIds.add(id.asLong()));
                assertEquals(Set.of(first, second), deliveredIds);
            }
            assertEquals("DELIVERED", statusOf(alice, bob.id(), first));
            assertEquals("DELIVERED", statusOf(alice, bob.id(), second));
        }

        // Уже доставленное повторно не присылается.
        try (ChatWs bobAgain = ChatWs.connect(bob)) {
            bobAgain.assertNoEvent("message");
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
            bobTab.awaitMessage(id);
            // Вкладку закрыли, не успев подтвердить доставку.
        }
        try (ChatWs bobTab = ChatWs.connect(bob)) {
            bobTab.awaitMessage(id);
        }
        assertEquals("SENT", statusOf(alice, bob.id(), id));
    }
}
