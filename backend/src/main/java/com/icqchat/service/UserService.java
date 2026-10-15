package com.icqchat.service;

import com.icqchat.common.Texts;
import com.icqchat.dto.ContactDto;
import com.icqchat.exception.ApiException;
import com.icqchat.repository.UserRepository;

import java.util.List;

public class UserService {
    private final UserRepository userRepository;
    private final PresenceProvider presence;

    public UserService(UserRepository userRepository, PresenceProvider presence) {
        this.userRepository = userRepository;
        this.presence = presence;
    }

    public List<ContactDto> contacts(long currentUserId, String search) {
        if (Texts.containsNul(search)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Строка поиска содержит недопустимый символ U+0000");
        }
        return userRepository.findContacts(currentUserId, search).stream()
                .map(row -> {
                    boolean self = row.id() == currentUserId;
                    return new ContactDto(
                            row.id(),
                            row.login(),
                            self,
                            !self && presence.isOnline(row.id()),
                            self ? 0 : row.unread(),
                            row.lastMessageAt()
                    );
                })
                .toList();
    }
}
