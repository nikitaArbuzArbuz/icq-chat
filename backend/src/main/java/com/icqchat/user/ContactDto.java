package com.icqchat.user;

/**
 * Entry of the contact list. {@code self} marks the "Избранное" chat with yourself.
 */
public record ContactDto(long id, String login, boolean self, boolean online, int unread) {
}
