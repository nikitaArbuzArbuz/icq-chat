package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.support.Api;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.icqchat.support.Api.statusOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Избранное: переписка с самим собой")
class FavoritesIntegrationTest {

    @Test
    @DisplayName("в списке контактов «Избранное» всегда первое, даже если не подходит под поиск")
    void favoritesFirstInContacts() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();

        JsonNode contacts = Api.contacts(alice, bob.login());
        assertEquals(2, contacts.size());

        JsonNode self = contacts.get(0);
        assertEquals(alice.id(), self.path("id").asLong());
        assertTrue(self.path("self").asBoolean());
        assertFalse(self.path("online").asBoolean());
        assertEquals(0, self.path("unread").asInt());

        assertEquals(bob.id(), contacts.get(1).path("id").asLong());
        assertFalse(contacts.get(1).path("self").asBoolean());
    }

    @Test
    @DisplayName("сообщение себе сразу READ, появляется в других вкладках, без уведомлений о доставке и прочтении")
    void messageToYourself() {
        TestUser alice = Api.newUser();
        long id;

        try (ChatWs tab = ChatWs.connect(alice); ChatWs otherTab = ChatWs.connect(alice)) {
            id = tab.sendMessage(alice, "купить молоко").path("id").asLong();

            assertEquals("READ", otherTab.awaitMessage(id).at("/message/status").asText());
            otherTab.assertNoEvent("message");
            tab.assertNoEvent("message");

            tab.read(alice.id(), id);
            for (ChatWs t : new ChatWs[]{tab, otherTab}) {
                t.assertNoEvent("delivered");
                t.assertNoEvent("read");
                t.assertNoEvent("error");
            }
        }
        assertEquals("READ", statusOf(alice, alice.id(), id));
        assertEquals(0, Api.contacts(alice, null).get(0).path("unread").asInt());
    }

    @Test
    @DisplayName("сообщения себе не досылаются при подключении как недоставленные")
    void notResentOnConnect() {
        TestUser alice = Api.newUser();
        try (ChatWs tab = ChatWs.connect(alice)) {
            tab.sendMessage(alice, "заметка");
        }
        try (ChatWs reconnected = ChatWs.connect(alice)) {
            reconnected.assertNoEvent("message");
        }
    }
}
