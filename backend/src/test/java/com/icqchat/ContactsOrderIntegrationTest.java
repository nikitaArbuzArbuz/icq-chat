package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.support.Api;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Порядок контактов по последнему сообщению")
class ContactsOrderIntegrationTest {

    @Test
    @DisplayName("диалог с новым сообщением поднимается наверх, «Избранное» остаётся первым")
    void newestDialogFirst() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        TestUser carol = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice);
             ChatWs bobTab = ChatWs.connect(bob);
             ChatWs carolTab = ChatWs.connect(carol)) {
            aliceTab.sendMessage(alice, "заметка себе");
            aliceTab.sendMessage(bob, "привет, Боб");
            JsonNode fromCarol = carolTab.sendMessage(alice, "привет от Кэрол");
            assertEquals(List.of(alice.id(), carol.id(), bob.id()), firstIds(alice, 3));
            assertEquals(fromCarol.path("createdAt").asText(), Api.contact(alice, carol).path("lastMessageAt").asText());

            // Ответ Боба поднимает его диалог выше Кэрол.
            bobTab.sendMessage(alice, "и тебе привет");
            assertEquals(List.of(alice.id(), bob.id(), carol.id()), firstIds(alice, 3));

            // Своё сообщение тоже поднимает диалог.
            aliceTab.sendMessage(carol, "как дела?");
            assertEquals(List.of(alice.id(), carol.id(), bob.id()), firstIds(alice, 3));
        }
    }

    @Test
    @DisplayName("пользователи без переписки идут после диалогов, у них lastMessageAt = null")
    void usersWithoutMessagesGoLast() {
        // У обоих общее начало логина: поиск по нему вернёт только их, сколько бы пользователей ни создали
        // другие тесты (без поиска в список попадают только первые 100). По логину stranger раньше bob.
        String prefix = Api.uniqueLogin();
        TestUser alice = Api.newUser();
        TestUser stranger = Api.newUser(prefix + "a");
        TestUser bob = Api.newUser(prefix + "b");

        try (ChatWs aliceTab = ChatWs.connect(alice)) {
            aliceTab.sendMessage(bob, "привет");
        }

        JsonNode contacts = Api.contacts(alice, prefix);
        assertEquals(List.of(alice.id(), bob.id(), stranger.id()), ids(contacts));
        assertTrue(contacts.get(1).path("lastMessageAt").isTextual());
        assertTrue(contacts.get(2).path("lastMessageAt").isNull());
    }

    private static List<Long> firstIds(TestUser user, int count) {
        return ids(Api.contacts(user, null)).subList(0, count);
    }

    private static List<Long> ids(JsonNode contacts) {
        List<Long> ids = new ArrayList<>();
        contacts.forEach(contact -> ids.add(contact.path("id").asLong()));
        return ids;
    }
}
