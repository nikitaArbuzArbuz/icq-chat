package com.icqchat.user;

import java.time.Instant;

/**
 * Элемент списка контактов. {@code self} отмечает чат «Избранное» с самим собой,
 * {@code lastMessageAt} равен null, если переписки ещё не было.
 */
public record ContactDto(long id, String login, boolean self, boolean online, int unread, Instant lastMessageAt) {
}
