package com.icqchat.ws;

import com.icqchat.message.Message;
import com.icqchat.message.MessageStatus;

import java.time.Instant;
import java.util.List;

/**
 * Messages sent by the server over WebSocket. Every message has a "type" field.
 */
public final class WsEvents {
    private WsEvents() {
    }

    /** A new message: incoming one, or your own message sent from another tab. */
    public record MessageEvent(String type, MessageDto message) {
        public MessageEvent(Message message) {
            this("message", MessageDto.from(message));
        }
    }

    /** Confirmation for the sender that the message is stored. */
    public record AckEvent(String type, String clientId, long id, Instant createdAt) {
        public AckEvent(Message message) {
            this("ack", message.clientId(), message.id(), message.createdAt());
        }
    }

    /** Recipient confirmed that these messages were delivered. */
    public record DeliveredEvent(String type, long recipientId, List<Long> messageIds) {
        public DeliveredEvent(long recipientId, List<Long> messageIds) {
            this("delivered", recipientId, messageIds);
        }
    }

    /** Reader has read all messages from sender up to the given id. */
    public record ReadEvent(String type, long readerId, long senderId, long upToMessageId) {
        public ReadEvent(long readerId, long senderId, long upToMessageId) {
            this("read", readerId, senderId, upToMessageId);
        }
    }

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

    public record MessageDto(long id, String clientId, long senderId, long recipientId, String text,
                             MessageStatus status, Instant createdAt) {
        public static MessageDto from(Message m) {
            return new MessageDto(m.id(), m.clientId(), m.senderId(), m.recipientId(), m.text(),
                    m.status(), m.createdAt());
        }
    }
}
