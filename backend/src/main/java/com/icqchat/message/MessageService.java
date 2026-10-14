package com.icqchat.message;

import com.icqchat.common.ApiException;
import com.icqchat.common.Texts;
import com.icqchat.user.UserRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MessageService {
    public static final int MAX_TEXT_LENGTH = 4000;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_CLIENT_ID_LENGTH = 64;

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    public MessageService(MessageRepository messageRepository, UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    /**
     * Сохраняет сообщение. Повторная отправка с тем же clientId возвращает уже сохранённое сообщение.
     */
    public SendResult send(long senderId, long recipientId, String clientId, String text) {
        if (clientId == null || clientId.isBlank() || clientId.length() > MAX_CLIENT_ID_LENGTH) {
            throw ApiException.badRequest("INVALID_CLIENT_ID", "Некорректный clientId");
        }
        if (Texts.isVisuallyBlank(text)) {
            throw ApiException.badRequest("EMPTY_MESSAGE", "Сообщение не может быть пустым");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw ApiException.badRequest("MESSAGE_TOO_LONG",
                    "Сообщение длиннее " + MAX_TEXT_LENGTH + " символов");
        }
        if (!userRepository.existsById(recipientId)) {
            throw ApiException.notFound("USER_NOT_FOUND", "Получатель не найден");
        }

        // Сообщениям самому себе («Избранное») не нужны подтверждения доставки и прочтения.
        MessageStatus initialStatus = senderId == recipientId ? MessageStatus.READ : MessageStatus.SENT;

        Optional<Message> inserted = messageRepository.insertIfAbsent(senderId, recipientId, clientId, text, initialStatus);
        if (inserted.isPresent()) {
            return new SendResult(inserted.get(), true);
        }
        Message existing = messageRepository.findBySenderAndClientId(senderId, clientId)
                .orElseThrow(() -> new IllegalStateException("Duplicate message disappeared: " + clientId));
        return new SendResult(existing, false);
    }

    public HistoryPage history(long userId, long peerId, Long before, Integer limit) {
        if (!userRepository.existsById(peerId)) {
            throw ApiException.notFound("USER_NOT_FOUND", "Пользователь не найден");
        }
        int pageSize = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        // Загружаем на одну строку больше, чтобы понять, есть ли ещё более старые сообщения.
        List<Message> rows = messageRepository.findConversation(userId, peerId, before, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Message> page = new ArrayList<>(hasMore ? rows.subList(0, pageSize) : rows);
        Collections.reverse(page);
        return new HistoryPage(page, hasMore);
    }

    public Map<Long, List<Long>> markDelivered(long recipientId, List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        return messageRepository.markDelivered(recipientId, messageIds);
    }

    public int markRead(long recipientId, long senderId, long upToMessageId) {
        return messageRepository.markRead(recipientId, senderId, upToMessageId);
    }

    public List<Message> undelivered(long recipientId) {
        return messageRepository.findUndelivered(recipientId);
    }

    public record SendResult(Message message, boolean created) {
    }
}
