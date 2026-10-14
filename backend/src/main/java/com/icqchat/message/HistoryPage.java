package com.icqchat.message;

import java.util.List;

/**
 * One page of chat history, messages ordered from oldest to newest.
 */
public record HistoryPage(List<Message> messages, boolean hasMore) {
}
