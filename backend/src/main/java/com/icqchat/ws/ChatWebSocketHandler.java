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
import java.util.concurrent.TimeUnit;

/**
 * WebSocket endpoint. Client messages: send, delivered, read. See README for the protocol.
 */
public class ChatWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String USER_ID_ATTRIBUTE = "userId";
    private static final int CLOSE_UNAUTHORIZED = 4401;
    private static final long PING_INTERVAL_SECONDS = 20;

    private final JwtService jwtService;
    private final MessageService messageService;
    private final SessionRegistry registry;

    public ChatWebSocketHandler(JwtService jwtService, MessageService messageService, SessionRegistry registry) {
        this.jwtService = jwtService;
        this.messageService = messageService;
        this.registry = registry;
    }

    public void configure(WsConfig ws) {
        ws.onConnect(this::onConnect);
        ws.onMessage(this::onMessage);
        ws.onClose(this::onClose);
        ws.onError(this::onError);
    }

    private void onConnect(WsConnectContext ctx) {
        Optional<Long> userId = jwtService.verify(ctx.queryParam("token"));
        if (userId.isEmpty()) {
            ctx.closeSession(CLOSE_UNAUTHORIZED, "Unauthorized");
            return;
        }
        long id = userId.get();
        ctx.attribute(USER_ID_ATTRIBUTE, id);
        ctx.enableAutomaticPings(PING_INTERVAL_SECONDS, TimeUnit.SECONDS);

        boolean cameOnline = registry.add(id, ctx);
        log.info("User {} connected, session {}", id, ctx.sessionId());
        if (cameOnline) {
            registry.sendToAllExcept(id, Json.write(new WsEvents.PresenceEvent(id, true)));
        }

        // Re-send everything that was sent while the user was offline. The client confirms delivery.
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
        String clientId = null;
        try {
            JsonNode node = Json.MAPPER.readTree(ctx.message());
            String type = node.path("type").asText();
            clientId = node.hasNonNull("clientId") ? node.get("clientId").asText() : null;
            switch (type) {
                case "send" -> handleSend(ctx, userId, node);
                case "delivered" -> handleDelivered(userId, node);
                case "read" -> handleRead(ctx, userId, node);
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
        // The message appears in the sender's other tabs.
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
            // Other tabs of the reader reset the unread counter.
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

    private void unregister(WsContext ctx) {
        Long userId = ctx.attribute(USER_ID_ATTRIBUTE);
        if (userId == null) {
            return;
        }
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
