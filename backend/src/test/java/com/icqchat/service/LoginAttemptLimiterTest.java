package com.icqchat.service;

import com.icqchat.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Лимит попыток входа в один аккаунт")
class LoginAttemptLimiterTest {
    private static final long USER = 1;
    private static final long OTHER_USER = 2;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T09:00:00Z"));
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Test
    @DisplayName("10 попыток проходят, 11-я получает 429 с Retry-After до конца окна")
    void blocksAfterMaxAttempts() {
        useAllAttempts(USER);
        clock.advance(Duration.ofMinutes(5));

        ApiException e = assertThrows(ApiException.class, () -> limiter.acquire(USER));
        assertEquals(429, e.status());
        assertEquals("TOO_MANY_ATTEMPTS", e.code());
        assertEquals(Duration.ofMinutes(10), e.retryAfter());
        assertEquals("Слишком много попыток входа. Попробуйте через 10 мин.", e.getMessage());
    }

    @Test
    @DisplayName("заблокированные попытки не продлевают блокировку: через 15 минут от первой попытки вход снова открыт")
    void unblocksWhenWindowEnds() {
        useAllAttempts(USER);
        clock.advance(Duration.ofMinutes(14));
        assertThrows(ApiException.class, () -> limiter.acquire(USER));

        clock.advance(Duration.ofMinutes(1));
        assertDoesNotThrow(() -> limiter.acquire(USER));
    }

    @Test
    @DisplayName("успешный вход обнуляет счётчик")
    void resetClearsAttempts() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS - 1; i++) {
            limiter.acquire(USER);
        }
        limiter.reset(USER);

        useAllAttempts(USER);
        assertThrows(ApiException.class, () -> limiter.acquire(USER));
    }

    @Test
    @DisplayName("лимит у каждого аккаунта свой")
    void limitsArePerUser() {
        useAllAttempts(USER);
        assertThrows(ApiException.class, () -> limiter.acquire(USER));
        assertDoesNotThrow(() -> limiter.acquire(OTHER_USER));
    }

    private void useAllAttempts(long userId) {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            assertDoesNotThrow(() -> limiter.acquire(userId));
        }
    }

    /** Часы, которые тест двигает сам: 15-минутное окно проверяется без ожидания. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
