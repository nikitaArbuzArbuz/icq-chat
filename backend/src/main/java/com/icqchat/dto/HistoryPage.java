package com.icqchat.dto;

import java.util.List;

/**
 * Одна страница истории переписки, сообщения отсортированы от старых к новым.
 */
public record HistoryPage(List<MessageDto> messages, boolean hasMore) {
}
