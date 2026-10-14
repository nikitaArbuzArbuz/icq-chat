package com.icqchat.user;

/**
 * Unchecked wrapper for SQL errors so repositories do not leak checked exceptions to services.
 */
public class DataAccessException extends RuntimeException {
    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
