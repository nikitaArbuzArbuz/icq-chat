package com.icqchat.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.exception.ApiException;
import com.icqchat.common.Json;
import com.icqchat.exception.DataAccessException;
import com.icqchat.model.Message;
import com.icqchat.service.JwtService;
import com.icqchat.service.MessageService;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Обработчик WebSocket. Сообщения от клиента: send, delivered, read, watch, pong. Протокол описан в README.
 */
public class ChatWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String USER_ID_ATTRIBUTE = "userId";
    /**
     * Курсор досылки недоставленного: id последнего отправленного сообщения, пока клиент получил не всё.
     * Хранится в атрибутах соединения, а не в общей карте: исчезает вместе с соединением, поэтому не может утечь.
     */
    private static final String UNDELIVERED_CURSOR_ATTRIBUTE = "undeliveredCursor";
    /** Токен невалидный или истёк. Клиент по этому коду разлогинивается. */
    private static final int CLOSE_UNAUTHORIZED = 4401;
    /** Клиент перестал отвечать на ping. Если он всё-таки жив, то просто переподключится. */
    private static final int CLOSE_HEARTBEAT_TIMEOUT = 4408;
    /** Стандартный код WebSocket «попробуйте позже»: сервер перегружен, клиент переподключится сам. */
    private static final int CLOSE_TRY_AGAIN_LATER = 1013;
    /** На скольких пользователей можно подписаться: в списке контактов до 100 человек и открытый диалог, запас вдвое. */
    private static final int MAX_WATCHED_USERS = 200;

    private final JwtService jwtService;
    private final MessageService messageService;
    private final SessionRegistry registry;
    private final HeartbeatMonitor heartbeat;

    public ChatWebSocketHandler(JwtService jwtService, MessageService messageService, SessionRegistry registry,
                                HeartbeatMonitor heartbeat) {
        this.jwtService = jwtService;
        this.messageService = messageService;
        this.registry = registry;
        this.heartbeat = heartbeat;
    }

    public void configure(WsConfig ws) {
        ws.onConnect(this::onConnect);
        ws.onMessage(this::onMessage);
        ws.onClose(this::onClose);
        ws.onError(this::onError);
    }

    private void onConnect(WsConnectContext ctx) {
        Optional<JwtService.VerifiedToken> token = jwtService.verifyToken(ctx.queryParam("token"));
        if (token.isEmpty()) {
            ctx.closeSession(CLOSE_UNAUTHORIZED, "Unauthorized");
            return;
        }
        long id = token.get().userId();
        ctx.attribute(USER_ID_ATTRIBUTE, id);

        // Если это первая вкладка пользователя, подписчики на его статус получат presence online.
        registry.add(id, ctx);
        // Монитор закроет сессию, если клиент перестанет отвечать на ping или истечёт токен.
        heartbeat.track(ctx, id, token.get().expiresAt());
        log.info("User {} connected, session {}", id, ctx.sessionId());

        // Досылаем то, что пришло, пока пользователь был офлайн. Пачками: следующая уходит, только когда клиент
        // подтвердит доставку предыдущей, поэтому клиент, который неделю был офлайн, не получает тысячи сообщений разом.
        try {
            sendUndeliveredBatch(ctx, id, 0);
        } catch (Exception e) {
            // Без досылки клиент не узнает о сообщениях, пришедших офлайн, поэтому соединение закрываем:
            // клиент переподключится с паузой и попробует снова.
            log.warn("Failed to load undelivered messages for user {}: {}", id, e.getMessage());
            registry.close(ctx, CLOSE_TRY_AGAIN_LATER, "Try again later");
        }
    }

    private void onMessage(WsMessageContext ctx) {
        Long userId = ctx.attribute(USER_ID_ATTRIBUTE);
        if (userId == null) {
            ctx.closeSession(CLOSE_UNAUTHORIZED, "Unauthorized");
            return;
        }
        heartbeat.touch(ctx);
        String clientId = null;
        try {
            JsonNode node = Json.MAPPER.readTree(ctx.message());
            String type = node.path("type").asText();
            clientId = node.hasNonNull("clientId") ? node.get("clientId").asText() : null;
            switch (type) {
                case "send" -> handleSend(ctx, userId, node);
                case "delivered" -> handleDelivered(ctx, userId, node);
                case "read" -> handleRead(ctx, userId, node);
                case "watch" -> handleWatch(ctx, userId, node);
                case "pong" -> {
                    // Ответ на ping: сессию уже продлил heartbeat.touch выше.
                }
                default -> sendError(ctx, clientId, "UNKNOWN_TYPE", "Неизвестный тип сообщения: " + type);
            }
        } catch (ApiException e) {
            sendError(ctx, clientId, e.code(), e.getMessage());
        } catch (JsonProcessingException e) {
            sendError(ctx, null, "BAD_JSON", "Некорректный JSON");
        } catch (Exception e) {
            if (DataAccessException.isOverload(e)) {
                log.warn("Database overloaded while handling message from user {}: {}", userId, e.getMessage());
                sendError(ctx, clientId, "SERVICE_UNAVAILABLE", "Сервер перегружен, попробуйте ещё раз");
            } else {
                log.error("Failed to handle WebSocket message from user {}", userId, e);
                sendError(ctx, clientId, "INTERNAL_ERROR", "Внутренняя ошибка сервера");
            }
        }
    }

    private void handleSend(WsMessageContext ctx, long senderId, JsonNode node) {
        long recipientId = requireLong(node, "to", "Поле to должно быть числом: id получателя");
        String clientId = node.path("clientId").asText(null);
        String text = node.path("text").asText(null);

        MessageService.SendResult result = messageService.send(senderId, recipientId, clientId, text);
        Message message = result.message();
        registry.send(ctx, Json.write(new WsEvents.AckEvent(message)));
        if (!result.created()) {
            return;
        }

        String event = Json.write(new WsEvents.MessageEvent(message));
        // Сообщение появляется в остальных вкладках отправителя.
        registry.sendToUserExcept(senderId, ctx, event);
        if (recipientId != senderId) {
            registry.sendToUser(recipientId, event);
        }
    }

    /**
     * Подписка на онлайн-статусы. Клиент присылает всех, кого показывает в списке контактов,
     * новый набор заменяет предыдущий. В ответ приходит снимок: кто из них сейчас онлайн.
     */
    private void handleWatch(WsMessageContext ctx, long userId, JsonNode node) {
        JsonNode userIds = node.path("userIds");
        if (!userIds.isArray()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Нужен массив userIds");
        }
        if (userIds.size() > MAX_WATCHED_USERS) {
            throw ApiException.badRequest("VALIDATION_ERROR",
                    "Можно подписаться не больше чем на " + MAX_WATCHED_USERS + " пользователей");
        }
        Set<Long> ids = new LinkedHashSet<>();
        userIds.forEach(idNode -> {
            if (isLong(idNode) && idNode.asLong() != userId) {
                ids.add(idNode.asLong());
            }
        });
        List<Long> online = registry.watch(userId, ctx, ids);
        List<Long> watched = ids.stream().sorted().toList();
        registry.send(ctx, Json.write(new WsEvents.PresenceSnapshotEvent(watched, online)));
    }

    private void handleDelivered(WsMessageContext ctx, long recipientId, JsonNode node) {
        List<Long> ids = new ArrayList<>();
        node.path("messageIds").forEach(idNode -> {
            if (isLong(idNode)) {
                ids.add(idNode.asLong());
            }
        });
        // Следующая пачка недоставленного уходит, только когда клиент подтвердил текущую, то есть её последнее
        // сообщение. Подтверждения обычных входящих, которые приходят тем временем, пачку не двигают.
        Long cursor = ctx.attribute(UNDELIVERED_CURSOR_ATTRIBUTE);
        boolean batchConfirmed = cursor != null && ids.contains(cursor);
        try {
            messageService.markDelivered(recipientId, ids).forEach((senderId, deliveredIds) ->
                    registry.sendToUser(senderId, Json.write(new WsEvents.DeliveredEvent(recipientId, deliveredIds))));
            if (batchConfirmed) {
                sendUndeliveredBatch(ctx, recipientId, cursor);
            }
        } catch (DataAccessException e) {
            if (!batchConfirmed) {
                throw e;
            }
            // Подтверждение пачки не обработалось, а второго клиент не пришлёт: досылка встала бы до переподключения.
            // Закрываем соединение, как и при ошибке досылки на подключении: клиент переподключится с паузой
            // и получит всё, что осталось недоставленным.
            log.warn("Failed to continue undelivered messages for user {}: {}", recipientId, e.getMessage());
            registry.close(ctx, CLOSE_TRY_AGAIN_LATER, "Try again later");
        }
    }

    /**
     * Отправляет пачку недоставленного после afterId и запоминает, докуда дошли, если есть ещё.
     * События одного соединения Jetty обрабатывает по очереди, поэтому курсор не нужно защищать от гонок.
     */
    private void sendUndeliveredBatch(WsContext ctx, long userId, long afterId) {
        MessageService.UndeliveredBatch batch = messageService.undelivered(userId, afterId);
        // Курсор ставим до отправки: подтверждение пачки может прийти раньше, чем мы выйдем из метода.
        ctx.attribute(UNDELIVERED_CURSOR_ATTRIBUTE, batch.hasMore() ? batch.lastId() : null);
        if (!batch.messages().isEmpty()) {
            registry.send(ctx, Json.write(new WsEvents.UndeliveredEvent(batch.messages(), batch.hasMore())));
        }
    }

    private void handleRead(WsMessageContext ctx, long readerId, JsonNode node) {
        long senderId = requireLong(node, "peerId", "Поле peerId должно быть числом: id собеседника");
        long lastMessageId = requireLong(node, "lastMessageId",
                "Поле lastMessageId должно быть числом: id последнего прочитанного сообщения");
        if (senderId == readerId) {
            return;
        }
        // В событие уходит id последнего сообщения, которое действительно стало прочитанным, а не число от клиента.
        OptionalLong readUpTo = messageService.markRead(readerId, senderId, lastMessageId);
        if (readUpTo.isPresent()) {
            String event = Json.write(new WsEvents.ReadEvent(readerId, senderId, readUpTo.getAsLong()));
            registry.sendToUser(senderId, event);
            // В остальных вкладках читателя сбрасывается счётчик непрочитанных.
            registry.sendToUserExcept(readerId, ctx, event);
        }
    }

    /**
     * Целое число из поля сообщения. Без проверки asLong() на строке "abc" молча вернул бы 0, а на 1.5 — 1,
     * поэтому всё, что не целое число, получает VALIDATION_ERROR.
     */
    private static long requireLong(JsonNode node, String field, String message) {
        JsonNode value = node.get(field);
        if (!isLong(value)) {
            throw ApiException.badRequest("VALIDATION_ERROR", message);
        }
        return value.asLong();
    }

    private static boolean isLong(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong();
    }

    private void onClose(WsCloseContext ctx) {
        unregister(ctx);
    }

    private void onError(WsErrorContext ctx) {
        log.warn("WebSocket error in session {}", ctx.sessionId(), ctx.error());
        unregister(ctx);
    }

    /**
     * Вызывается монитором heartbeat из его потока: клиент перестал отвечать на ping
     * или истёк токен. Сначала убираем сессию из реестра, чтобы остальные сразу увидели
     * пользователя офлайн, даже если закрытие мёртвого соединения затянется.
     */
    public void onDeadSession(WsContext ctx, long userId, HeartbeatMonitor.Reason reason) {
        log.info("Closing session {} of user {}: {}", ctx.sessionId(), userId, reason);
        unregister(ctx, userId);
        switch (reason) {
            case TOKEN_EXPIRED -> registry.close(ctx, CLOSE_UNAUTHORIZED, "Token expired");
            case HEARTBEAT_TIMEOUT -> registry.close(ctx, CLOSE_HEARTBEAT_TIMEOUT, "Heartbeat timeout");
        }
    }

    private void unregister(WsContext ctx) {
        Long userId = ctx.attribute(USER_ID_ATTRIBUTE);
        if (userId == null) {
            return;
        }
        unregister(ctx, userId);
    }

    /** Повторный вызов для той же сессии безопасен: второй раз она уже не найдётся в реестре. */
    private void unregister(WsContext ctx, long userId) {
        heartbeat.untrack(ctx);
        // Если это была последняя вкладка пользователя, подписчики на его статус получат presence offline.
        registry.remove(userId, ctx);
        log.info("User {} disconnected, session {}", userId, ctx.sessionId());
    }

    private void sendError(WsContext ctx, String clientId, String code, String message) {
        registry.send(ctx, Json.write(new WsEvents.ErrorEvent(clientId, code, message)));
    }
}
