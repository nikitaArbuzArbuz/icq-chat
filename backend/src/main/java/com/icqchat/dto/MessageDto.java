package com.icqchat.dto;

import com.icqchat.model.Message;
import com.icqchat.model.MessageStatus;

import java.time.Instant;

/**
 * Сообщение в ответах клиенту: одинаковое в истории REST и в событиях WebSocket.
 * Отделено от модели {@link Message}, чтобы изменения в ней не меняли API.
 */
public record MessageDto(long id, String clientId, long senderId, long recipientId, String text,
                         MessageStatus status, Instant createdAt) {
    public static MessageDto from(Message m) {
        return new MessageDto(m.id(), m.clientId(), m.senderId(), m.recipientId(), m.text(),
                m.status(), m.createdAt());
    }
}
