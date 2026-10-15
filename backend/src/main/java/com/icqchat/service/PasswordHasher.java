package com.icqchat.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.icqchat.exception.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Хеширование паролей через bcrypt.
 * <p>
 * bcrypt принимает пароль не длиннее 72 байт, и библиотека в строгом режиме бросает
 * IllegalArgumentException на более длинный. Длина считается в байтах UTF-8, а не в символах:
 * латиница занимает 1 байт, кириллица 2, эмодзи 4. Поэтому проверять её нужно заранее,
 * через {@link #exceedsMaxLength}.
 * <p>
 * bcrypt медленный специально: один хеш с cost 12 — около 0,3 с работы ядра. Поэтому одновременно
 * считается не больше {@link #DEFAULT_MAX_CONCURRENT} хешей, по половине ядер: наплыв входов не займёт
 * весь процессор и не затормозит чат. Кто не дождался очереди за {@link #DEFAULT_QUEUE_TIMEOUT}, получает 503.
 */
public class PasswordHasher {
    /** Максимальная длина пароля в байтах UTF-8, которую принимает bcrypt. */
    public static final int MAX_PASSWORD_BYTES = BCrypt.Version.MAX_PW_LENGTH_BYTE;
    private static final int COST = 12;
    private static final int DEFAULT_MAX_CONCURRENT = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private static final Duration DEFAULT_QUEUE_TIMEOUT = Duration.ofSeconds(2);

    private final Semaphore permits;
    private final Duration queueTimeout;

    public PasswordHasher() {
        this(new Semaphore(DEFAULT_MAX_CONCURRENT, true), DEFAULT_QUEUE_TIMEOUT);
    }

    /** Для тестов: свои места в очереди и время ожидания. */
    PasswordHasher(Semaphore permits, Duration queueTimeout) {
        this.permits = permits;
        this.queueTimeout = queueTimeout;
    }

    public static boolean exceedsMaxLength(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }

    /**
     * Длину пароля должен заранее проверить вызывающий код, иначе библиотека бросит исключение.
     */
    public String hash(String password) {
        return withPermit(() -> BCrypt.withDefaults().hashToString(COST, password.toCharArray()));
    }

    /**
     * Слишком длинный пароль не мог пройти регистрацию, поэтому он просто не совпадает с хешем,
     * а не роняет запрос исключением из библиотеки.
     */
    public boolean verify(String password, String hash) {
        if (exceedsMaxLength(password)) {
            return false;
        }
        return withPermit(() -> BCrypt.verifyer().verify(password.toCharArray(), hash).verified);
    }

    /** Выполняет bcrypt, когда освободится место; не дождались за queueTimeout — 503, запрос можно повторить. */
    private <T> T withPermit(Supplier<T> bcrypt) {
        try {
            if (!permits.tryAcquire(queueTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw ApiException.overloaded();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.overloaded();
        }
        try {
            return bcrypt.get();
        } finally {
            permits.release();
        }
    }
}
