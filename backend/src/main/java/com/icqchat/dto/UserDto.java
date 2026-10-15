package com.icqchat.dto;

import com.icqchat.model.User;

public record UserDto(long id, String login) {
    public static UserDto from(User user) {
        return new UserDto(user.id(), user.login());
    }
}
