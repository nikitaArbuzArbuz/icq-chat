package com.icqchat.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.icqchat.common.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * REST-клиент для тестов: настоящие HTTP-запросы к запущенному серверу.
 */
public final class Api {
    public static final String PASSWORD = "password123";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicInteger LOGIN_COUNTER = new AtomicInteger();

    private Api() {
    }

    public record Response(int status, JsonNode body, HttpHeaders headers) {
        public String errorCode() {
            return body.path("code").asText();
        }

        /** Значение заголовка или null, если его нет. */
        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    /** Логин, уникальный в пределах прогона: латиница и цифры, не длиннее 32 символов. */
    public static String uniqueLogin() {
        return "u" + LOGIN_COUNTER.incrementAndGet() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static TestUser newUser() {
        return newUser(uniqueLogin());
    }

    public static TestUser newUser(String login) {
        Response response = register(login, PASSWORD);
        assertEquals(201, response.status(), () -> "регистрация не прошла: " + response.body());
        return new TestUser(response.body().at("/user/id").asLong(), login, response.body().path("token").asText());
    }

    public static Response register(String login, String password) {
        return post("/api/auth/register", Json.write(Map.of("login", login, "password", password)));
    }

    public static Response login(String login, String password) {
        return post("/api/auth/login", Json.write(Map.of("login", login, "password", password)));
    }

    public static Response post(String path, String jsonBody) {
        HttpRequest request = HttpRequest.newBuilder(TestApp.httpUri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return execute(request);
    }

    public static Response get(String pathAndQuery, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(TestApp.httpUri(pathAndQuery)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return execute(request.build());
    }

    /** История переписки; before и limit можно не передавать (null). */
    public static Response historyRequest(TestUser user, long peerId, Object before, Object limit) {
        StringBuilder path = new StringBuilder("/api/messages/" + peerId);
        String separator = "?";
        if (before != null) {
            path.append(separator).append("before=").append(before);
            separator = "&";
        }
        if (limit != null) {
            path.append(separator).append("limit=").append(limit);
        }
        return get(path.toString(), user.token());
    }

    public static JsonNode history(TestUser user, long peerId, Long before, Integer limit) {
        Response response = historyRequest(user, peerId, before, limit);
        assertEquals(200, response.status(), () -> "история не загрузилась: " + response.body());
        return response.body();
    }

    public static List<Long> messageIds(JsonNode historyPage) {
        List<Long> ids = new ArrayList<>();
        historyPage.path("messages").forEach(message -> ids.add(message.path("id").asLong()));
        return ids;
    }

    /** Статус сообщения, как его видит пользователь в истории переписки с peerId. */
    public static String statusOf(TestUser user, long peerId, long messageId) {
        for (JsonNode message : history(user, peerId, messageId + 1, null).path("messages")) {
            if (message.path("id").asLong() == messageId) {
                return message.path("status").asText();
            }
        }
        return fail("сообщения " + messageId + " нет в истории");
    }

    public static JsonNode contacts(TestUser user, String search) {
        String path = search == null
                ? "/api/users"
                : "/api/users?search=" + URLEncoder.encode(search, StandardCharsets.UTF_8);
        Response response = get(path, user.token());
        assertEquals(200, response.status(), () -> "контакты не загрузились: " + response.body());
        return response.body();
    }

    public static JsonNode contact(TestUser user, TestUser other) {
        for (JsonNode contact : contacts(user, other.login())) {
            if (contact.path("id").asLong() == other.id()) {
                return contact;
            }
        }
        return fail("пользователя " + other.login() + " нет в контактах");
    }

    private static Response execute(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            JsonNode json = body == null || body.isBlank() ? NullNode.getInstance() : Json.MAPPER.readTree(body);
            return new Response(response.statusCode(), json, response.headers());
        } catch (IOException e) {
            throw new IllegalStateException("запрос " + request.uri() + " не выполнился", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
