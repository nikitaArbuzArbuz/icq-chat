package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.support.Api;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Сообщения и пароли из одних невидимых символов")
class InvisibleTextIntegrationTest {

    static Stream<Arguments> invisibleTexts() {
        return Stream.of(
                arguments("неразрывные пробелы", "\u00A0".repeat(8)),
                arguments("пробелы нулевой ширины", "\u200B".repeat(8)),
                arguments("хангыльский заполнитель", "\u3164".repeat(8)),
                arguments("вперемешку", "\u00A0\u200B\u3164\u2800\uFEFF\u202F  "));
    }

    @ParameterizedTest(name = "{0}: EMPTY_MESSAGE")
    @MethodSource("invisibleTexts")
    @DisplayName("сообщение без видимых символов отклоняется и не сохраняется")
    void invisibleMessageRejected(String description, String text) {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        String clientId = UUID.randomUUID().toString();

        try (ChatWs tab = ChatWs.connect(alice)) {
            tab.send(ChatWs.sendPayload(bob.id(), clientId, text));
            JsonNode error = tab.await("error", e -> clientId.equals(e.path("clientId").asText()));
            assertEquals("EMPTY_MESSAGE", error.path("code").asText());
        }
        assertEquals(0, Api.messageIds(Api.history(alice, bob.id(), null, null)).size());
    }

    @Test
    @DisplayName("видимый текст с невидимыми символами вокруг принимается как есть")
    void visibleTextWithInvisibleCharsAccepted() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        try (ChatWs tab = ChatWs.connect(alice)) {
            String text = "\u200B\u00A0привет\u00A0\u200B";
            long id = tab.sendMessage(bob, text).path("id").asLong();
            JsonNode saved = Api.history(alice, bob.id(), null, null).path("messages").get(0);
            assertEquals(id, saved.path("id").asLong());
            assertEquals(text, saved.path("text").asText());
        }
    }

    @ParameterizedTest(name = "{0}: 400")
    @MethodSource("invisibleTexts")
    @DisplayName("пароль без видимых символов при регистрации: 400")
    void invisiblePasswordRejected(String description, String password) {
        Api.Response response = Api.register(Api.uniqueLogin(), password);
        assertEquals(400, response.status(), response.body()::toString);
        assertEquals("VALIDATION_ERROR", response.errorCode());
    }

    @Test
    @DisplayName("пробелы внутри пароля разрешены")
    void spacesInsidePasswordAllowed() {
        String login = Api.uniqueLogin();
        String password = "correct horse battery";
        assertEquals(201, Api.register(login, password).status());
        assertEquals(200, Api.login(login, password).status());
    }
}
