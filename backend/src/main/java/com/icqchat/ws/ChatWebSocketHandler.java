package com.icqchat.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.auth.JwtService;
import com.icqchat.common.ApiException;
import com.icqchat.common.Json;
import com.icqchat.message.Message;
import com.icqchat.message.MessageService;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Обработчик WebSocket. Сообщения от клиента: send, delivered, read, pong. Протокол описан в README.
 */
public class ChatWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String USER_ID_ATTRIBUTE = "userId";
    /** Токен невалидный или истёк. Клиент по этому коду разлогинивается. */
    private static final int CLOSE_UNAUTHORIZED = 4401;
    /** Клиент перестал отвечать на ping. Если он всё-таки жив, то просто переподключится. */
    private static final int CLOSE_HEARTBEAT_TIMEOUT = 4408;

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

        boolean cameOnline = registry.add(id, ctx);
        // Монитор закроет сессию, если клиент перестанет отвечать на ping или истечёт токен.
        heartbeat.track(ctx, id, token.get().expiresAt());
        log.info("User {} connected, session {}", id, ctx.sessionId());
        if (cameOnline) {
            registry.sendToAllExcept(id, Json.write(new WsEvents.PresenceEvent(id, true)));
        }

        // Досылаем всё, что пришло, пока пользователь был офлайн. Клиент подтвердит доставку.
        for (Message message : messageService.undelivered(id)) {
            registry.send(ctx, Json.write(new WsEvents.MessageEvent(message)));
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
                case "delivered" -> handleDelivered(userId, node);
                case "read" -> handleRead(ctx, userId, node);
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
            log.error("Failed to handle WebSocket message from user {}", userId, e);
            sendError(ctx, clientId, "INTERNAL_ERROR", "Внутренняя ошибка сервера");
        }
    }

    private void handleSend(WsMessageContext ctx, long senderId, JsonNode node) {
        if (!node.hasNonNull("to") || !node.get("to").canConvertToLong()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Не указан получатель");
        }
        long recipientId = node.get("to").asLong();
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

    private void handleDelivered(long recipientId, JsonNode node) {
        List<Long> ids = new ArrayList<>();
        node.path("messageIds").forEach(idNode -> {
            if (idNode.canConvertToLong()) {
                ids.add(idNode.asLong());
            }
        });
        messageService.markDelivered(recipientId, ids).forEach((senderId, deliveredIds) ->
                registry.sendToUser(senderId, Json.write(new WsEvents.DeliveredEvent(recipientId, deliveredIds))));
    }

    private void handleRead(WsMessageContext ctx, long readerId, JsonNode node) {
        if (!node.hasNonNull("peerId") || !node.hasNonNull("lastMessageId")) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Нужны поля peerId и lastMessageId");
        }
        long senderId = node.get("peerId").asLong();
        long lastMessageId = node.get("lastMessageId").asLong();
        if (senderId == readerId) {
            return;
        }
        int updated = messageService.markRead(readerId, senderId, lastMessageId);
        if (updated > 0) {
            String event = Json.write(new WsEvents.ReadEvent(readerId, senderId, lastMessageId));
            registry.sendToUser(senderId, event);
            // В остальных вкладках читателя сбрасывается счётчик непрочитанных.
            registry.sendToUserExcept(readerId, ctx, event);
        }
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
        boolean wentOffline = registry.remove(userId, ctx);
        log.info("User {} disconnected, session {}", userId, ctx.sessionId());
        if (wentOffline) {
            registry.sendToAllExcept(userId, Json.write(new WsEvents.PresenceEvent(userId, false)));
        }
    }

    private void sendError(WsContext ctx, String clientId, String code, String message) {
        registry.send(ctx, Json.write(new WsEvents.ErrorEvent(clientId, code, message)));
    }
}
