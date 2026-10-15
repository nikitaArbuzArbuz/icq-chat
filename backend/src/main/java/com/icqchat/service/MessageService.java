package com.icqchat.service;

import com.icqchat.exception.ApiException;
import com.icqchat.common.Texts;
import com.icqchat.dto.HistoryPage;
import com.icqchat.exception.DataAccessException;
import com.icqchat.model.Message;
import com.icqchat.model.MessageStatus;
import com.icqchat.repository.MessageRepository;
import com.icqchat.repository.UserRepository;

import java.sql.SQLException;
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
    /** Сколько недоставленных сообщений уходит клиенту за раз. Следующая пачка уходит после подтверждения доставки этой. */
    public static final int UNDELIVERED_BATCH_SIZE = 100;
    /** Сколько сообщений можно подтвердить одним delivered: ограничивает размер одного UPDATE. */
    public static final int MAX_DELIVERED_IDS = 1000;
    /** SQLState нарушения внешнего ключа: сообщение адресовано пользователю, которого нет. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

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
        // Сообщениям самому себе («Избранное») не нужны подтверждения доставки и прочтения.
        MessageStatus initialStatus = senderId == recipientId ? MessageStatus.READ : MessageStatus.SENT;

        // Получателя отдельным запросом не проверяем: несуществующему пользователю сообщение не даст сохранить
        // внешний ключ messages.recipient_id, а проверка заранее стоила бы лишнего запроса на каждое сообщение.
        Optional<Message> inserted;
        try {
            inserted = messageRepository.insertIfAbsent(senderId, recipientId, clientId, text, initialStatus);
        } catch (DataAccessException e) {
            if (e.getCause() instanceof SQLException sql && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())) {
                throw ApiException.notFound("USER_NOT_FOUND", "Получатель не найден");
            }
            throw e;
        }
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
        if (messageIds.size() > MAX_DELIVERED_IDS) {
            throw ApiException.badRequest("VALIDATION_ERROR",
                    "За раз можно подтвердить не больше " + MAX_DELIVERED_IDS + " сообщений");
        }
        return messageRepository.markDelivered(recipientId, messageIds);
    }

    public int markRead(long recipientId, long senderId, long upToMessageId) {
        return messageRepository.markRead(recipientId, senderId, upToMessageId);
    }

    /** Следующая пачка недоставленных сообщений после afterId. Читаем на одну строку больше, чтобы знать, есть ли ещё. */
    public UndeliveredBatch undelivered(long recipientId, long afterId) {
        List<Message> rows = messageRepository.findUndelivered(recipientId, afterId, UNDELIVERED_BATCH_SIZE + 1);
        boolean hasMore = rows.size() > UNDELIVERED_BATCH_SIZE;
        return new UndeliveredBatch(hasMore ? rows.subList(0, UNDELIVERED_BATCH_SIZE) : rows, hasMore);
    }

    public record UndeliveredBatch(List<Message> messages, boolean hasMore) {
        public long lastId() {
            return messages.get(messages.size() - 1).id();
        }
    }

    public record SendResult(Message message, boolean created) {
    }
}
