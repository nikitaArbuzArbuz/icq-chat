package com.icqchat.exception;

import java.time.Duration;

/**
 * Бизнес-ошибка, которая превращается в JSON-ответ с заданным HTTP-статусом и кодом ошибки.
 */
public class ApiException extends RuntimeException {
    /** Через сколько клиенту предлагается повторить запрос, когда сервер перегружен. */
    private static final Duration OVERLOAD_RETRY_AFTER = Duration.ofSeconds(1);

    private final int status;
    private final String code;
    /** Когда можно повторить запрос: уходит клиенту в заголовке Retry-After. null, если заголовок не нужен. */
    private final Duration retryAfter;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Duration retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, "UNAUTHORIZED", message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    /** Клиент исчерпал лимит запросов: 429, повторить можно через retryAfter. */
    public static ApiException tooManyRequests(String code, String message, Duration retryAfter) {
        return new ApiException(429, code, message, retryAfter);
    }

    /** Сервер временно перегружен: 503, запрос можно повторить через секунду. */
    public static ApiException overloaded() {
        return new ApiException(503, "SERVICE_UNAVAILABLE", "Сервер перегружен, попробуйте ещё раз", OVERLOAD_RETRY_AFTER);
    }
}
