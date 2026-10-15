package com.icqchat.dto;

import com.icqchat.model.Message;

import java.util.List;

/**
 * Одна страница истории переписки, сообщения отсортированы от старых к новым.
 */
public record HistoryPage(List<Message> messages, boolean hasMore) {
}
