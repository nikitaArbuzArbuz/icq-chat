package com.icqchat.user;

import java.time.Instant;

public record User(long id, String login, String passwordHash, Instant createdAt) {
}
