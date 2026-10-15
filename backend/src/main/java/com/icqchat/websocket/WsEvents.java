package com.icqchat.websocket;

import com.icqchat.dto.MessageDto;
import com.icqchat.model.Message;

import java.time.Instant;
import java.util.List;

/**
 * Сообщения, которые сервер отправляет по WebSocket. У каждого есть поле "type".
 */
public final class WsEvents {
    private WsEvents() {
    }

    /** Новое сообщение: входящее или своё, отправленное из другой вкладки. */
    public record MessageEvent(String type, MessageDto message) {
        public MessageEvent(Message message) {
            this("message", MessageDto.from(message));
        }
    }

    /**
     * Пачка сообщений, пришедших, пока получатель был офлайн, по возрастанию id.
     * Если hasMore, следующая пачка придёт после того, как клиент подтвердит доставку этой.
     */
    public record UndeliveredEvent(String type, List<MessageDto> messages, boolean hasMore) {
        public UndeliveredEvent(List<Message> messages, boolean hasMore) {
            this("undelivered", messages.stream().map(MessageDto::from).toList(), hasMore);
        }
    }

    /** Подтверждение отправителю, что сообщение сохранено. */
    public record AckEvent(String type, String clientId, long id, Instant createdAt) {
        public AckEvent(Message message) {
            this("ack", message.clientId(), message.id(), message.createdAt());
        }
    }

    /** Получатель подтвердил доставку этих сообщений. */
    public record DeliveredEvent(String type, long recipientId, List<Long> messageIds) {
        public DeliveredEvent(long recipientId, List<Long> messageIds) {
            this("delivered", recipientId, messageIds);
        }
    }

    /** Читатель прочитал все сообщения от отправителя до указанного id. */
    public record ReadEvent(String type, long readerId, long senderId, long upToMessageId) {
        public ReadEvent(long readerId, long senderId, long upToMessageId) {
            this("read", readerId, senderId, upToMessageId);
        }
    }

    /** Heartbeat: сервер проверяет, что клиент на связи. Клиент должен ответить {"type":"pong"}. */
    public record PingEvent(String type) {
        public PingEvent() {
            this("ping");
        }
    }

    /** Ответ на подписку watch: на кого подписаны и кто из них сейчас онлайн. */
    public record PresenceSnapshotEvent(String type, List<Long> watched, List<Long> online) {
        public PresenceSnapshotEvent(List<Long> watched, List<Long> online) {
            this("presenceSnapshot", watched, online);
        }
    }

    /** Пользователь появился в сети или вышел. Приходит только тем, кто на него подписан. */
    public record PresenceEvent(String type, long userId, boolean online) {
        public PresenceEvent(long userId, boolean online) {
            this("presence", userId, online);
        }
    }

    public record ErrorEvent(String type, String clientId, String code, String message) {
        public ErrorEvent(String clientId, String code, String message) {
            this("error", clientId, code, message);
        }
    }
}
