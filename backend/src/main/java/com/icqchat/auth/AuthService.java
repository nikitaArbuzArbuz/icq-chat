package com.icqchat.auth;

import com.icqchat.common.ApiException;
import com.icqchat.user.DataAccessException;
import com.icqchat.user.User;
import com.icqchat.user.UserDto;
import com.icqchat.user.UserRepository;

import java.sql.SQLException;
import java.util.regex.Pattern;

public class AuthService {
    private static final Pattern LOGIN_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final String UNIQUE_VIOLATION = "23505";

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordHasher passwordHasher, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
    }

    public AuthResponse register(AuthRequest request) {
        validate(request);
        if (userRepository.findByLogin(request.login()).isPresent()) {
            throw ApiException.conflict("LOGIN_TAKEN", "Логин уже занят");
        }
        User user;
        try {
            user = userRepository.create(request.login(), passwordHasher.hash(request.password()));
        } catch (DataAccessException e) {
            if (e.getCause() instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                throw ApiException.conflict("LOGIN_TAKEN", "Логин уже занят");
            }
            throw e;
        }
        return new AuthResponse(jwtService.issue(user), UserDto.from(user));
    }

    public AuthResponse login(AuthRequest request) {
        if (request == null || request.login() == null || request.password() == null) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Укажите логин и пароль");
        }
        User user = userRepository.findByLogin(request.login())
                .filter(u -> passwordHasher.verify(request.password(), u.passwordHash()))
                .orElseThrow(() -> new ApiException(401, "INVALID_CREDENTIALS", "Неверный логин или пароль"));
        return new AuthResponse(jwtService.issue(user), UserDto.from(user));
    }

    public UserDto me(long userId) {
        return userRepository.findById(userId)
                .map(UserDto::from)
                .orElseThrow(() -> ApiException.unauthorized("Пользователь не найден"));
    }

    private void validate(AuthRequest request) {
        if (request == null || request.login() == null || !LOGIN_PATTERN.matcher(request.login()).matches()) {
            throw ApiException.badRequest("VALIDATION_ERROR",
                    "Логин должен быть от 3 до 32 символов: латиница, цифры и подчёркивание");
        }
        if (request.password() == null || request.password().length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Пароль должен быть не короче 8 символов");
        }
    }
}
