package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.support.Api;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Регрессия NPE в SessionRegistry.sendToUserExcept: рассылка событию пользователю, который онлайн,
 * падала на первой же сессии. Сообщения не доходили в реальном времени, отправитель получал ошибку.
 */
@DisplayName("Доставка онлайн-получателю и синхронизация вкладок")
class OnlineDeliveryIntegrationTest {

    @Test
    @DisplayName("сообщение сразу приходит во все вкладки получателя и в другие вкладки отправителя, без ошибок")
    void messageReachesAllTabs() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice);
             ChatWs aliceOtherTab = ChatWs.connect(alice);
             ChatWs bobTab = ChatWs.connect(bob);
             ChatWs bobOtherTab = ChatWs.connect(bob)) {
            long id = aliceTab.sendMessage(bob, "привет").path("id").asLong();

            bobTab.awaitMessage(id);
            bobOtherTab.awaitMessage(id);
            aliceOtherTab.awaitMessage(id);

            // Вкладка-отправитель получает только ack, без эха и без INTERNAL_ERROR, как было при NPE.
            aliceTab.assertNoEvent("message");
            aliceTab.assertNoEvent("error");
        }
    }

    @Test
    @DisplayName("доставка и прочтение приходят во все вкладки отправителя, прочтение синхронизирует вкладки читателя")
    void statusUpdatesReachAllTabs() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice);
             ChatWs aliceOtherTab = ChatWs.connect(alice);
             ChatWs bobTab = ChatWs.connect(bob);
             ChatWs bobOtherTab = ChatWs.connect(bob)) {
            long id = aliceTab.sendMessage(bob, "привет").path("id").asLong();
            bobTab.awaitMessage(id);

            bobTab.delivered(id);
            aliceTab.await("delivered");
            aliceOtherTab.await("delivered");

            bobTab.read(alice.id(), id);
            aliceTab.await("read");
            aliceOtherTab.await("read");
            // Во второй вкладке Боба сбрасывается счётчик непрочитанных, сама читающая вкладка события не ждёт.
            bobOtherTab.await("read");
            bobTab.assertNoEvent("read");
            bobTab.assertNoEvent("error");
        }
    }

    @Test
    @DisplayName("онлайн, пока открыта хотя бы одна вкладка: presence приходит только при первом входе и последнем выходе")
    void presenceWithSeveralTabs() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice);
             ChatWs bobTab = ChatWs.connect(bob)) {
            assertTrue(aliceTab.await("presence", e -> isAbout(e, bob)).path("online").asBoolean());
            assertTrue(Api.contact(alice, bob).path("online").asBoolean());

            ChatWs bobOtherTab = ChatWs.connect(bob);
            aliceTab.assertNoEvent("presence", e -> isAbout(e, bob));
            bobTab.close();
            aliceTab.assertNoEvent("presence", e -> isAbout(e, bob));
            assertTrue(Api.contact(alice, bob).path("online").asBoolean());

            bobOtherTab.close();
            assertFalse(aliceTab.await("presence", e -> isAbout(e, bob)).path("online").asBoolean());
            assertFalse(Api.contact(alice, bob).path("online").asBoolean());
        }
    }

    private static boolean isAbout(JsonNode event, TestUser user) {
        return event.path("userId").asLong() == user.id();
    }

    @Test
    @DisplayName("уведомление другому пользователю не уходит в чужие сессии")
    void eventsGoOnlyToParticipants() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        TestUser carol = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice);
             ChatWs bobTab = ChatWs.connect(bob);
             ChatWs carolTab = ChatWs.connect(carol)) {
            long id = aliceTab.sendMessage(bob, "только Бобу").path("id").asLong();
            bobTab.awaitMessage(id);
            bobTab.delivered(id);
            aliceTab.await("delivered");

            carolTab.assertNoEvent("message");
            carolTab.assertNoEvent("delivered");
            assertEquals(0, Api.messageIds(Api.history(carol, alice.id(), null, null)).size());
        }
    }
}
