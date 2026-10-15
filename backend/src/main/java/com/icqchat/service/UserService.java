package com.icqchat.service;

import com.icqchat.dto.ContactDto;
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

    public boolean exists(long userId) {
        return userRepository.existsById(userId);
    }
}
