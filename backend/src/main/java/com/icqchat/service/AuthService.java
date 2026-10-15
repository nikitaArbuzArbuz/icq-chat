package com.icqchat.service;

import com.icqchat.exception.ApiException;
import com.icqchat.common.Texts;
import com.icqchat.exception.DataAccessException;
import com.icqchat.dto.AuthRequest;
import com.icqchat.dto.AuthResponse;
import com.icqchat.dto.UserDto;
import com.icqchat.model.User;
import com.icqchat.repository.UserRepository;

import java.sql.SQLException;
import java.util.regex.Pattern;

public class AuthService {
    private static final Pattern LOGIN_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final String UNIQUE_VIOLATION = "23505";

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;
    private final LoginAttemptLimiter loginLimiter;

    public AuthService(UserRepository userRepository, PasswordHasher passwordHasher, JwtService jwtService,
                       LoginAttemptLimiter loginLimiter) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        this.loginLimiter = loginLimiter;
    }

    public AuthResponse register(AuthRequest request) {
        validate(request);
        // Поиск без учёта регистра: Alice и alice считаются одним логином.
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
        // Логин с U+0000 не пройдёт регистрацию, а PostgreSQL не принимает этот символ в запросе: сразу 401.
        if (Texts.containsNul(request.login())) {
            throw invalidCredentials();
        }
        // Для несуществующего логина bcrypt не считается, такой ответ дешёвый и под лимит попыток не попадает.
        User user = userRepository.findByLogin(request.login()).orElseThrow(AuthService::invalidCredentials);
        // Попытка засчитывается до проверки пароля: заблокированный вход не тратит процессор на bcrypt.
        loginLimiter.acquire(user.id());
        // Пароль длиннее лимита bcrypt verify отклоняет сам, поэтому такой вход получает обычный 401.
        if (!passwordHasher.verify(request.password(), user.passwordHash())) {
            throw invalidCredentials();
        }
        loginLimiter.reset(user.id());
        return new AuthResponse(jwtService.issue(user), UserDto.from(user));
    }

    private static ApiException invalidCredentials() {
        return new ApiException(401, "INVALID_CREDENTIALS", "Неверный логин или пароль");
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
        if (Texts.isVisuallyBlank(request.password())) {
            throw ApiException.badRequest("VALIDATION_ERROR",
                    "Пароль не может состоять только из пробелов и невидимых символов");
        }
        if (PasswordHasher.exceedsMaxLength(request.password())) {
            throw ApiException.badRequest("VALIDATION_ERROR",
                    "Пароль слишком длинный: максимум " + PasswordHasher.MAX_PASSWORD_BYTES + " байта, это "
                            + PasswordHasher.MAX_PASSWORD_BYTES + " латинских символа или "
                            + PasswordHasher.MAX_PASSWORD_BYTES / 2 + " букв кириллицы");
        }
    }
}
