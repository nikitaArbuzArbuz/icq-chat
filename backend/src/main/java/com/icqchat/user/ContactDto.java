package com.icqchat.user;

/**
 * Элемент списка контактов. {@code self} отмечает чат «Избранное» с самим собой.
 */
public record ContactDto(long id, String login, boolean self, boolean online, int unread) {
}
