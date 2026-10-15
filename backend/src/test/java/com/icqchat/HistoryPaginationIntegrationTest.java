package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.support.Api;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.icqchat.support.Api.history;
import static com.icqchat.support.Api.messageIds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Пагинация истории: before, limit, hasMore, максимум 100")
class HistoryPaginationIntegrationTest {
    private static final int TOTAL = 120;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private static TestUser alice;
    private static TestUser bob;
    /** id всех сообщений диалога в порядке отправки, то есть по возрастанию. */
    private static final List<Long> ALL_IDS = new ArrayList<>();

    @BeforeAll
    static void createConversation() {
        alice = Api.newUser();
        bob = Api.newUser();
        try (ChatWs aliceTab = ChatWs.connect(alice); ChatWs bobTab = ChatWs.connect(bob)) {
            for (int i = 0; i < TOTAL; i++) {
                // Каждое третье пишет Боб, чтобы в истории были оба направления.
                ChatWs sender = i % 3 == 0 ? bobTab : aliceTab;
                TestUser to = i % 3 == 0 ? alice : bob;
                ALL_IDS.add(sender.sendMessage(to, "сообщение " + i).path("id").asLong());
            }
        }
    }

    @Test
    @DisplayName("без параметров: последние 50 сообщений от старых к новым и hasMore=true")
    void latestPageByDefault() {
        JsonNode page = history(alice, bob.id(), null, null);
        assertEquals(ALL_IDS.subList(TOTAL - DEFAULT_LIMIT, TOTAL), messageIds(page));
        assertTrue(page.path("hasMore").asBoolean());
    }

    @Test
    @DisplayName("before и limit листают историю назад до конца без пропусков и повторов")
    void scrollBackToTheBeginning() {
        List<Long> collected = new ArrayList<>();
        Long before = null;
        int pages = 0;
        JsonNode page;
        do {
            page = history(alice, bob.id(), before, 30);
            List<Long> ids = messageIds(page);
            collected.addAll(0, ids);
            before = ids.get(0);
            pages++;
        } while (page.path("hasMore").asBoolean());

        assertEquals(ALL_IDS, collected);
        assertEquals(4, pages);
    }

    @Test
    @DisplayName("последняя страница: hasMore=false, дальше пусто")
    void lastPage() {
        JsonNode last = history(alice, bob.id(), ALL_IDS.get(10), null);
        assertEquals(ALL_IDS.subList(0, 10), messageIds(last));
        assertFalse(last.path("hasMore").asBoolean());

        JsonNode empty = history(alice, bob.id(), ALL_IDS.get(0), null);
        assertTrue(messageIds(empty).isEmpty());
        assertFalse(empty.path("hasMore").asBoolean());
    }

    @Test
    @DisplayName("limit больше 100 обрезается до 100")
    void limitIsCappedAt100() {
        JsonNode page = history(alice, bob.id(), null, 500);
        assertEquals(ALL_IDS.subList(TOTAL - MAX_LIMIT, TOTAL), messageIds(page));
        assertTrue(page.path("hasMore").asBoolean());
    }

    @Test
    @DisplayName("limit больше int тоже обрезается до 100, а не переполняется")
    void hugeLimitDoesNotOverflow() {
        // 2^32 + 1: при приведении long к int стало бы 1, и пришла бы страница из одного сообщения.
        Api.Response response = Api.historyRequest(alice, bob.id(), null, 4_294_967_297L);
        assertEquals(200, response.status(), response.body()::toString);
        assertEquals(ALL_IDS.subList(TOTAL - MAX_LIMIT, TOTAL), messageIds(response.body()));
        assertTrue(response.body().path("hasMore").asBoolean());
    }

    @Test
    @DisplayName("limit 0 или отрицательный: размер страницы по умолчанию")
    void nonPositiveLimitFallsBackToDefault() {
        assertEquals(DEFAULT_LIMIT, messageIds(history(alice, bob.id(), null, 0)).size());
        assertEquals(DEFAULT_LIMIT, messageIds(history(alice, bob.id(), null, -5)).size());
    }

    @Test
    @DisplayName("оба участника видят одну и ту же историю, в ней сообщения в обе стороны")
    void sameHistoryForBothParticipants() {
        JsonNode fromAlice = history(alice, bob.id(), null, MAX_LIMIT);
        JsonNode fromBob = history(bob, alice.id(), null, MAX_LIMIT);
        assertEquals(messageIds(fromAlice), messageIds(fromBob));

        Set<Long> senders = new HashSet<>();
        fromAlice.path("messages").forEach(m -> senders.add(m.path("senderId").asLong()));
        assertEquals(Set.of(alice.id(), bob.id()), senders);
    }

    @Test
    @DisplayName("посторонний пользователь чужую переписку не видит")
    void outsiderSeesNothing() {
        TestUser carol = Api.newUser();
        assertTrue(messageIds(history(carol, alice.id(), null, null)).isEmpty());
        assertTrue(messageIds(history(carol, bob.id(), null, null)).isEmpty());
    }

    @Test
    @DisplayName("некорректные параметры: 400, несуществующий собеседник: 404")
    void invalidParameters() {
        assertEquals(400, Api.historyRequest(alice, bob.id(), "abc", null).status());
        assertEquals(400, Api.historyRequest(alice, bob.id(), null, "many").status());
        assertEquals(404, Api.historyRequest(alice, Long.MAX_VALUE, null, null).status());
    }
}
