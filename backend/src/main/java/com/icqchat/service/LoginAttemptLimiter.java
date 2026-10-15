package com.icqchat.service;

import com.icqchat.exception.ApiException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ограничивает подбор пароля к одному аккаунту: не больше {@link #MAX_ATTEMPTS} попыток входа за {@link #WINDOW}.
 * Окно начинается с первой попытки, успешный вход обнуляет счётчик. Лимит по IP-адресу ставит nginx,
 * этот же не даёт перебирать пароль одного пользователя сразу с многих адресов.
 * <p>
 * Попытка засчитывается до проверки пароля: параллельные запросы не обходят лимит, а заблокированный вход
 * не тратит процессор на bcrypt. Счётчики заводятся только для существующих пользователей, поэтому их не больше,
 * чем пользователей. Как и WebSocket-сессии, они живут в памяти одного экземпляра сервера.
 */
public class LoginAttemptLimiter {
    /** Попыток на аккаунт за окно. OWASP ASVS допускает не больше 100 неудачных попыток в час, здесь 40. */
    static final int MAX_ATTEMPTS = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Clock clock;
    private final Map<Long, Attempts> attempts = new ConcurrentHashMap<>();

    private record Attempts(Instant windowStart, int count) {
        Instant windowEnd() {
            return windowStart.plus(WINDOW);
        }
    }

    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Засчитывает попытку входа пользователя.
     *
     * @throws ApiException 429 TOO_MANY_ATTEMPTS, если попытки за окно исчерпаны; Retry-After — до конца окна
     */
    public void acquire(long userId) {
        Instant now = clock.instant();
        boolean[] allowed = {true};
        // compute атомарен для одного ключа: два параллельных запроса не займут одну и ту же последнюю попытку.
        Attempts state = attempts.compute(userId, (id, current) -> {
            if (current == null || !now.isBefore(current.windowEnd())) {
                return new Attempts(now, 1);
            }
            if (current.count() >= MAX_ATTEMPTS) {
                allowed[0] = false;
                return current;
            }
            return new Attempts(current.windowStart(), current.count() + 1);
        });
        if (!allowed[0]) {
            Duration retryAfter = Duration.between(now, state.windowEnd());
            long minutes = Math.max(1, (retryAfter.toSeconds() + 59) / 60);
            throw ApiException.tooManyRequests("TOO_MANY_ATTEMPTS",
                    "Слишком много попыток входа. Попробуйте через " + minutes + " мин.", retryAfter);
        }
    }

    /** Успешный вход: счётчик пользователя обнуляется. */
    public void reset(long userId) {
        attempts.remove(userId);
    }
}
