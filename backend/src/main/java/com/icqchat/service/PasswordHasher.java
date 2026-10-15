package com.icqchat.service;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;

/**
 * Хеширование паролей через bcrypt.
 * <p>
 * bcrypt принимает пароль не длиннее 72 байт, и библиотека в строгом режиме бросает
 * IllegalArgumentException на более длинный. Длина считается в байтах UTF-8, а не в символах:
 * латиница занимает 1 байт, кириллица 2, эмодзи 4. Поэтому проверять её нужно заранее,
 * через {@link #exceedsMaxLength}.
 */
public class PasswordHasher {
    /** Максимальная длина пароля в байтах UTF-8, которую принимает bcrypt. */
    public static final int MAX_PASSWORD_BYTES = BCrypt.Version.MAX_PW_LENGTH_BYTE;
    private static final int COST = 12;

    public static boolean exceedsMaxLength(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }

    /**
     * Длину пароля должен заранее проверить вызывающий код, иначе библиотека бросит исключение.
     */
    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(COST, password.toCharArray());
    }

    /**
     * Слишком длинный пароль не мог пройти регистрацию, поэтому он просто не совпадает с хешем,
     * а не роняет запрос исключением из библиотеки.
     */
    public boolean verify(String password, String hash) {
        if (exceedsMaxLength(password)) {
            return false;
        }
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
    }
}
