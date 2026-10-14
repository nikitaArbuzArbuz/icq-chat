package com.icqchat.user;

import com.icqchat.ws.SessionRegistry;

import java.util.List;

public class UserService {
    private final UserRepository userRepository;
    private final SessionRegistry sessionRegistry;

    public UserService(UserRepository userRepository, SessionRegistry sessionRegistry) {
        this.userRepository = userRepository;
        this.sessionRegistry = sessionRegistry;
    }

    public List<ContactDto> contacts(long currentUserId, String search) {
        return userRepository.findContacts(currentUserId, search).stream()
                .map(row -> {
                    boolean self = row.id() == currentUserId;
                    return new ContactDto(
                            row.id(),
                            row.login(),
                            self,
                            !self && sessionRegistry.isOnline(row.id()),
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
