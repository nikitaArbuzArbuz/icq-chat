package com.icqchat.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.common.Json;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * WebSocket-клиент для тестов: одна вкладка браузера. Складывает все события от сервера,
 * сам отвечает на heartbeat-ping и умеет ждать нужное событие или проверять, что лишнего не пришло.
 */
public final class ChatWs implements AutoCloseable {
    /** Сколько ждём ожидаемое событие. */
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(5);
    /** Сколько ждём, прежде чем утверждать, что события не будет. */
    private static final Duration QUIET_PERIOD = Duration.ofMillis(500);

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final List<Event> events = new ArrayList<>();
    private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
    private final WebSocket socket;
    /** Отправки в java.net.http.WebSocket нельзя запускать параллельно, поэтому выстраиваем их в цепочку. */
    private CompletableFuture<WebSocket> lastSend = CompletableFuture.completedFuture(null);

    private static final class Event {
        private final JsonNode json;
        private boolean consumed;

        private Event(JsonNode json) {
            this.json = json;
        }

        @Override
        public String toString() {
            return (consumed ? "(разобрано) " : "") + json;
        }
    }

    private ChatWs(String token) {
        this.socket = HTTP.newWebSocketBuilder().buildAsync(TestApp.wsUri(token), new Listener()).join();
    }

    public static ChatWs connect(TestUser user) {
        return new ChatWs(user.token());
    }

    public static ChatWs connect(String token) {
        return new ChatWs(token);
    }

    // ---------- отправка

    public void send(Map<String, ?> payload) {
        enqueueSend(socket, Json.write(payload)).join();
    }

    public void sendRaw(String text) {
        enqueueSend(socket, text).join();
    }

    /** Отправляет сообщение с новым clientId и ждёт подтверждения сохранения. */
    public JsonNode sendMessage(TestUser to, String text) {
        return sendMessage(to.id(), UUID.randomUUID().toString(), text);
    }

    public JsonNode sendMessage(long to, String clientId, String text) {
        send(sendPayload(to, clientId, text));
        return awaitAck(clientId);
    }

    public static Map<String, Object> sendPayload(long to, String clientId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "send");
        payload.put("clientId", clientId);
        payload.put("to", to);
        payload.put("text", text);
        return payload;
    }

    public void delivered(long... messageIds) {
        send(Map.of("type", "delivered", "messageIds", Arrays.stream(messageIds).boxed().toList()));
    }

    public void read(long peerId, long lastMessageId) {
        send(Map.of("type", "read", "peerId", peerId, "lastMessageId", lastMessageId));
    }

    /** Подписка на онлайн-статусы пользователей. Возвращает снимок, который сервер присылает в ответ. */
    public JsonNode watch(long... userIds) {
        send(Map.of("type", "watch", "userIds", Arrays.stream(userIds).boxed().toList()));
        return await("presenceSnapshot");
    }

    // ---------- ожидание событий

    public JsonNode awaitAck(String clientId) {
        return await("ack", e -> clientId.equals(e.path("clientId").asText()));
    }

    /** Ждёт событие type=message с конкретным сообщением. */
    public JsonNode awaitMessage(long messageId) {
        return await("message", e -> e.at("/message/id").asLong() == messageId);
    }

    public JsonNode await(String type) {
        return await(type, e -> true);
    }

    /** Ждёт первое ещё не разобранное событие нужного типа, подходящее под условие, и помечает его разобранным. */
    public JsonNode await(String type, Predicate<JsonNode> filter) {
        long deadline = System.nanoTime() + EVENT_TIMEOUT.toNanos();
        synchronized (events) {
            while (true) {
                for (Event event : events) {
                    if (!event.consumed && matches(event, type, filter)) {
                        event.consumed = true;
                        return event.json;
                    }
                }
                long leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (leftMillis <= 0) {
                    return fail("не дождались события " + type + ", пришло: " + events);
                }
                waitForEvents(leftMillis);
            }
        }
    }

    public void assertNoEvent(String type) {
        assertNoEvent(type, e -> true);
    }

    /** Ждёт немного и проверяет, что неразобранных событий такого типа не появилось. */
    public void assertNoEvent(String type, Predicate<JsonNode> filter) {
        sleep(QUIET_PERIOD);
        synchronized (events) {
            for (Event event : events) {
                if (!event.consumed && matches(event, type, filter)) {
                    fail("пришло лишнее событие " + event.json + ", все события: " + events);
                }
            }
        }
    }

    /** Все полученные события данного типа в порядке прихода, включая уже разобранные. */
    public List<JsonNode> received(String type) {
        synchronized (events) {
            return events.stream().filter(e -> type.equals(e.json.path("type").asText())).map(e -> e.json).toList();
        }
    }

    /** Код, с которым сервер закрыл соединение. */
    public int awaitCloseCode() {
        try {
            return closeCode.get(EVENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return fail("сервер не закрыл соединение");
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        if (closeCode.isDone()) {
            return;
        }
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(EVENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            closeCode.get(EVENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            socket.abort();
        }
    }

    // ---------- внутреннее

    private static boolean matches(Event event, String type, Predicate<JsonNode> filter) {
        return type.equals(event.json.path("type").asText()) && filter.test(event.json);
    }

    private synchronized CompletableFuture<WebSocket> enqueueSend(WebSocket target, String text) {
        lastSend = lastSend.exceptionally(e -> null).thenCompose(ignored -> target.sendText(text, true));
        return lastSend;
    }

    private void onServerEvent(WebSocket webSocket, String text) {
        JsonNode json;
        try {
            json = Json.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("сервер прислал не JSON: " + text, e);
        }
        if ("ping".equals(json.path("type").asText())) {
            enqueueSend(webSocket, "{\"type\":\"pong\"}");
            return;
        }
        synchronized (events) {
            events.add(new Event(json));
            events.notifyAll();
        }
    }

    private void waitForEvents(long millis) {
        try {
            events.wait(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                onServerEvent(webSocket, text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.completeExceptionally(error);
        }
    }
}
