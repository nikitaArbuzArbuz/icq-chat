package com.icqchat.user;

/**
 * Непроверяемая обёртка над ошибками SQL, чтобы проверяемые исключения не выходили из репозиториев в сервисы.
 */
public class DataAccessException extends RuntimeException {
    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
