package com.icqchat.auth;

import com.icqchat.user.UserDto;

public record AuthResponse(String token, UserDto user) {
}
