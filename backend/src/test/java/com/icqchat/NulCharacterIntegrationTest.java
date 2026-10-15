package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.support.Api;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.icqchat.support.Api.PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PostgreSQL не принимает символ U+0000 в тексте. Без проверки такой ввод доходил до базы,
 * и вместо понятной ошибки клиент получал 500, а в логе оставался стектрейс.
 */
@DisplayName("Символ U+0000 во вводе: ошибка валидации, а не 500")
class NulCharacterIntegrationTest {
    private static final String NUL = "\u0000";

    @Test
    @DisplayName("в тексте сообщения: VALIDATION_ERROR, в базу ничего не попадает")
    void inMessageText() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        try (ChatWs tab = ChatWs.connect(alice)) {
            String clientId = UUID.randomUUID().toString();
            tab.send(ChatWs.sendPayload(bob.id(), clientId, "при" + NUL + "вет"));
            assertEquals("VALIDATION_ERROR", errorFor(tab, clientId).path("code").asText());
        }
        assertEquals(0, Api.messageIds(Api.history(alice, bob.id(), null, null)).size());
    }

    @Test
    @DisplayName("в clientId: INVALID_CLIENT_ID")
    void inClientId() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        try (ChatWs tab = ChatWs.connect(alice)) {
            String clientId = "id" + NUL;
            tab.send(ChatWs.sendPayload(bob.id(), clientId, "привет"));
            assertEquals("INVALID_CLIENT_ID", errorFor(tab, clientId).path("code").asText());
        }
    }

    @Test
    @DisplayName("в строке поиска контактов: 400 VALIDATION_ERROR")
    void inContactsSearch() {
        TestUser alice = Api.newUser();
        Api.Response response = Api.get("/api/users?search=a%00b", alice.token());
        assertEquals(400, response.status(), response.body()::toString);
        assertEquals("VALIDATION_ERROR", response.errorCode());
    }

    @Test
    @DisplayName("в логине при входе: обычный 401 INVALID_CREDENTIALS")
    void inLogin() {
        TestUser alice = Api.newUser();
        Api.Response response = Api.login(alice.login() + NUL, PASSWORD);
        assertEquals(401, response.status(), response.body()::toString);
        assertEquals("INVALID_CREDENTIALS", response.errorCode());
    }

    private static JsonNode errorFor(ChatWs tab, String clientId) {
        return tab.await("error", e -> clientId.equals(e.path("clientId").asText()));
    }
}
