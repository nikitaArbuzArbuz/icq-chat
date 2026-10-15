package com.icqchat.service;

import com.icqchat.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Очередь на bcrypt")
class PasswordHasherTest {

    @Test
    @DisplayName("пока все места заняты, запрос ждёт не дольше таймаута и получает 503 с Retry-After")
    void rejectsWhenAllPermitsAreBusy() throws InterruptedException {
        Semaphore permits = new Semaphore(1);
        PasswordHasher hasher = new PasswordHasher(permits, Duration.ofMillis(100));
        String hash = hasher.hash("password123");

        permits.acquire();
        ApiException e = assertThrows(ApiException.class, () -> hasher.verify("password123", hash));
        assertEquals(503, e.status());
        assertEquals("SERVICE_UNAVAILABLE", e.code());
        assertEquals(Duration.ofSeconds(1), e.retryAfter());

        permits.release();
        assertTrue(hasher.verify("password123", hash));
        assertFalse(hasher.verify("wrong-password", hash));
        assertEquals(1, permits.availablePermits(), "после bcrypt место освобождается");
    }
}
