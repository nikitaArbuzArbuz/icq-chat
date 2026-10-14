package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.support.Api;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Поиск контактов по началу логина")
class ContactsSearchIntegrationTest {

    @Test
    @DisplayName("находит по началу логина без учёта регистра, по середине логина не находит")
    void byLoginPrefixIgnoringCase() {
        String prefix = Api.uniqueLogin();
        TestUser alice = Api.newUser();
        TestUser lowerCase = Api.newUser(prefix + "a");
        TestUser upperCase = Api.newUser(prefix.toUpperCase() + "B");
        Api.newUser("x" + prefix);

        // «Избранное» первым, дальше совпадения по логину. Логин x... содержит строку поиска, но не начинается с неё.
        List<Long> expected = List.of(alice.id(), lowerCase.id(), upperCase.id());
        assertEquals(expected, ids(Api.contacts(alice, prefix)));
        assertEquals(expected, ids(Api.contacts(alice, prefix.toUpperCase())));
    }

    @Test
    @DisplayName("_ и % в строке поиска означают сами себя, а не любые символы")
    void likeWildcardsAreLiteral() {
        String prefix = Api.uniqueLogin();
        TestUser alice = Api.newUser();
        TestUser underscore = Api.newUser(prefix + "_x");
        Api.newUser(prefix + "1x");

        assertEquals(List.of(alice.id(), underscore.id()), ids(Api.contacts(alice, prefix + "_")));
        assertEquals(List.of(alice.id()), ids(Api.contacts(alice, prefix + "%")));
    }

    private static List<Long> ids(JsonNode contacts) {
        List<Long> ids = new ArrayList<>();
        contacts.forEach(contact -> ids.add(contact.path("id").asLong()));
        return ids;
    }
}
