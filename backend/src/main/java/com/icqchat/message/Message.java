package com.icqchat.message;

import java.time.Instant;

public record Message(
        long id,
        long senderId,
        long recipientId,
        String clientId,
        String text,
        MessageStatus status,
        Instant createdAt
) {
}
