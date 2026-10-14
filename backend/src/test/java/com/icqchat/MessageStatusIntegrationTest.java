package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.message.MessageService;
import com.icqchat.support.Api;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static com.icqchat.support.Api.statusOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Статусы SENT → DELIVERED → READ")
class MessageStatusIntegrationTest {

    @Test
    @DisplayName("полный цикл: каждое уведомление приходит отправителю ровно один раз, статус не откатывается")
    void fullCycleWithoutRollbackAndDuplicates() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice); ChatWs bobTab = ChatWs.connect(bob)) {
            long id = aliceTab.sendMessage(bob, "привет").path("id").asLong();
            assertEquals("SENT", bobTab.awaitMessage(id).at("/message/status").asText());
            assertEquals("SENT", statusOf(alice, bob.id(), id));

            bobTab.delivered(id);
            JsonNode delivered = aliceTab.await("delivered");
            assertEquals(bob.id(), delivered.path("recipientId").asLong());
            assertEquals(List.of(id), longs(delivered.path("messageIds")));
            assertEquals("DELIVERED", statusOf(alice, bob.id(), id));

            // Повторное подтверждение, например из второй вкладки Боба.
            bobTab.delivered(id);
            aliceTab.assertNoEvent("delivered");

            bobTab.read(alice.id(), id);
            JsonNode read = aliceTab.await("read");
            assertEquals(bob.id(), read.path("readerId").asLong());
            assertEquals(alice.id(), read.path("senderId").asLong());
            assertEquals(id, read.path("upToMessageId").asLong());
            assertEquals("READ", statusOf(alice, bob.id(), id));

            // Запоздалое подтверждение доставки и повторное прочтение ничего не меняют.
            bobTab.delivered(id);
            bobTab.read(alice.id(), id);
            aliceTab.assertNoEvent("delivered");
            aliceTab.assertNoEvent("read");
            assertEquals("READ", statusOf(alice, bob.id(), id));
        }
    }

    @Test
    @DisplayName("прочтение без подтверждения доставки сразу переводит SENT в READ")
    void readWithoutDelivered() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice); ChatWs bobTab = ChatWs.connect(bob)) {
            long id = aliceTab.sendMessage(bob, "привет").path("id").asLong();
            bobTab.awaitMessage(id);

            bobTab.read(alice.id(), id);
            aliceTab.await("read");
            assertEquals("READ", statusOf(alice, bob.id(), id));

            bobTab.delivered(id);
            aliceTab.assertNoEvent("delivered");
            assertEquals("READ", statusOf(alice, bob.id(), id));
        }
    }

    @Test
    @DisplayName("прочтение отмечает все сообщения до указанного включительно и сбрасывает счётчик непрочитанных")
    void readUpToMessage() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice); ChatWs bobTab = ChatWs.connect(bob)) {
            List<Long> ids = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                ids.add(aliceTab.sendMessage(bob, "сообщение " + i).path("id").asLong());
            }
            assertEquals(3, Api.contact(bob, alice).path("unread").asInt());

            bobTab.read(alice.id(), ids.get(1));
            assertEquals(ids.get(1), aliceTab.await("read").path("upToMessageId").asLong());

            assertEquals("READ", statusOf(alice, bob.id(), ids.get(0)));
            assertEquals("READ", statusOf(alice, bob.id(), ids.get(1)));
            assertEquals("SENT", statusOf(alice, bob.id(), ids.get(2)));
            assertEquals(1, Api.contact(bob, alice).path("unread").asInt());
        }
    }

    @Test
    @DisplayName("чужие сообщения нельзя отметить доставленными или прочитанными")
    void cannotConfirmSomeoneElsesMessages() {
        TestUser alice = Api.newUser();
        TestUser bob = Api.newUser();
        TestUser carol = Api.newUser();

        try (ChatWs aliceTab = ChatWs.connect(alice); ChatWs carolTab = ChatWs.connect(carol)) {
            long id = aliceTab.sendMessage(bob, "только для Боба").path("id").asLong();

            carolTab.delivered(id);
            carolTab.read(alice.id(), id);

            aliceTab.assertNoEvent("delivered");
            aliceTab.assertNoEvent("read");
            assertEquals("SENT", statusOf(alice, bob.id(), id));
        }
    }

    @Test
    @DisplayName("подтвердить больше 1000 сообщений одним delivered нельзя: ошибка VALIDATION_ERROR")
    void tooManyIdsInDelivered() {
        TestUser bob = Api.newUser();
        try (ChatWs bobTab = ChatWs.connect(bob)) {
            bobTab.delivered(LongStream.rangeClosed(1, MessageService.MAX_DELIVERED_IDS + 1).toArray());
            assertEquals("VALIDATION_ERROR", bobTab.await("error").path("code").asText());
        }
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asLong()));
        return result;
    }
}
