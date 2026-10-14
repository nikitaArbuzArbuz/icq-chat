package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.support.Api;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@DisplayName("Идемпотентность отправки по clientId")
class SendIdempotencyIntegrationTest {

    @Test
    @DisplayName("повторная отправка с тем же clientId: тот же id, один экземпляр в базе, одно уведомление")
    void repeatedSendReturnsSameMessage() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        String clientId = UUID.randomUUID().toString();

        try (ChatWs aliceTab = ChatWs.connect(alice);
             ChatWs aliceOtherTab = ChatWs.connect(alice);
             ChatWs bobTab = ChatWs.connect(bob)) {
            JsonNode firstAck = aliceTab.sendMessage(bob.id(), clientId, "привет");
            JsonNode secondAck = aliceTab.sendMessage(bob.id(), clientId, "привет");

            assertEquals(firstAck.path("id").asLong(), secondAck.path("id").asLong());
            assertEquals(firstAck.path("createdAt").asText(), secondAck.path("createdAt").asText());
            aliceTab.assertNoEvent("error");

            long id = firstAck.path("id").asLong();
            bobTab.awaitMessage(id);
            bobTab.assertNoEvent("message");
            aliceOtherTab.awaitMessage(id);
            aliceOtherTab.assertNoEvent("message");
        }
        assertEquals(1, countByClientId(alice, bob, clientId));
    }

    @Test
    @DisplayName("повтор после переподключения, как при обрыве сети, тоже не создаёт дубль")
    void repeatedSendAfterReconnect() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        String clientId = UUID.randomUUID().toString();

        long firstId;
        try (ChatWs tab = ChatWs.connect(alice)) {
            firstId = tab.sendMessage(bob.id(), clientId, "привет").path("id").asLong();
        }
        try (ChatWs reconnected = ChatWs.connect(alice)) {
            assertEquals(firstId, reconnected.sendMessage(bob.id(), clientId, "привет").path("id").asLong());
        }
        assertEquals(1, countByClientId(alice, bob, clientId));
    }

    @Test
    @DisplayName("одинаковый clientId у разных отправителей: это разные сообщения")
    void sameClientIdFromDifferentSenders() {
        TestUser alice = Api.newUser();
        TestUser carol = Api.newUser();
        TestUser bob = Api.newUser();
        String clientId = UUID.randomUUID().toString();

        try (ChatWs aliceTab = ChatWs.connect(alice); ChatWs carolTab = ChatWs.connect(carol)) {
            long fromAlice = aliceTab.sendMessage(bob.id(), clientId, "от Алисы").path("id").asLong();
            long fromCarol = carolTab.sendMessage(bob.id(), clientId, "от Кэрол").path("id").asLong();
            assertNotEquals(fromAlice, fromCarol);
        }
    }

    @Test
    @DisplayName("невалидное сообщение: ошибка с clientId, в базу ничего не попадает")
    void invalidMessages() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        long missingUserId = Long.MAX_VALUE;

        try (ChatWs tab = ChatWs.connect(alice)) {
            assertSendError(tab, bob.id(), "   ", "EMPTY_MESSAGE");
            assertSendError(tab, bob.id(), "x".repeat(4001), "MESSAGE_TOO_LONG");
            assertSendError(tab, missingUserId, "кому-то", "USER_NOT_FOUND");
        }
        assertEquals(0, Api.messageIds(Api.history(alice, bob.id(), null, null)).size());
    }

    private static void assertSendError(ChatWs tab, long to, String text, String code) {
        String clientId = UUID.randomUUID().toString();
        tab.send(ChatWs.sendPayload(to, clientId, text));
        JsonNode error = tab.await("error", e -> clientId.equals(e.path("clientId").asText()));
        assertEquals(code, error.path("code").asText());
    }

    private static long countByClientId(TestUser user, TestUser peer, String clientId) {
        JsonNode page = Api.history(user, peer.id(), null, null);
        long count = 0;
        for (JsonNode message : page.path("messages")) {
            if (clientId.equals(message.path("clientId").asText())) {
                count++;
            }
        }
        return count;
    }
}
