package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.support.Api;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Онлайн-статус: только подписчикам")
class PresenceIntegrationTest {

    @Test
    @DisplayName("presence получают только подписанные на пользователя, остальные ничего не получают")
    void presenceGoesOnlyToWatchers() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        TestUser carol = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice); ChatWs carolTab = ChatWs.connect(carol)) {
            aliceTab.watch(bob.id());

            ChatWs bobTab = ChatWs.connect(bob);
            assertTrue(aliceTab.await("presence", e -> isAbout(e, bob)).path("online").asBoolean());

            bobTab.close();
            assertFalse(aliceTab.await("presence", e -> isAbout(e, bob)).path("online").asBoolean());

            carolTab.assertNoEvent("presence", e -> isAbout(e, bob));
        }
    }

    @Test
    @DisplayName("в ответ на подписку приходит снимок: на кого подписаны и кто из них онлайн, себя в нём нет")
    void snapshotOnWatch() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        TestUser dave = Api.newUser();

        try (ChatWs bobTab = ChatWs.connect(bob); ChatWs aliceTab = ChatWs.connect(alice)) {
            JsonNode snapshot = aliceTab.watch(dave.id(), bob.id(), alice.id());
            assertEquals(sorted(bob.id(), dave.id()), longs(snapshot.path("watched")));
            assertEquals(List.of(bob.id()), longs(snapshot.path("online")));
        }
    }

    @Test
    @DisplayName("онлайн, пока открыта хотя бы одна вкладка: presence только при первом входе и последнем выходе")
    void severalTabs() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice)) {
            aliceTab.watch(bob.id());

            ChatWs bobTab = ChatWs.connect(bob);
            assertTrue(aliceTab.await("presence", e -> isAbout(e, bob)).path("online").asBoolean());

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

    @Test
    @DisplayName("новая подписка заменяет прежнюю")
    void watchReplacesPreviousSubscription() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        TestUser carol = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice)) {
            aliceTab.watch(bob.id());
            aliceTab.watch(carol.id());

            try (ChatWs bobTab = ChatWs.connect(bob); ChatWs carolTab = ChatWs.connect(carol)) {
                aliceTab.await("presence", e -> isAbout(e, carol));
                aliceTab.assertNoEvent("presence", e -> isAbout(e, bob));
            }
        }
    }

    @Test
    @DisplayName("подписка больше чем на 200 пользователей: ошибка VALIDATION_ERROR")
    void tooManyWatchedUsers() {
        TestUser alice = Api.newUser();
        try (ChatWs aliceTab = ChatWs.connect(alice)) {
            aliceTab.send(Map.of("type", "watch", "userIds", LongStream.rangeClosed(1, 201).boxed().toList()));
            assertEquals("VALIDATION_ERROR", aliceTab.await("error").path("code").asText());
            aliceTab.assertNoEvent("presenceSnapshot");
        }
    }

    private static boolean isAbout(JsonNode event, TestUser user) {
        return event.path("userId").asLong() == user.id();
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asLong()));
        return result;
    }

    private static List<Long> sorted(long... ids) {
        return LongStream.of(ids).sorted().boxed().toList();
    }
}
