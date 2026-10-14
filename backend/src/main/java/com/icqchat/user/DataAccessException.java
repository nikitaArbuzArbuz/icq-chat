package com.icqchat.user;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * Непроверяемая обёртка над ошибками SQL, чтобы проверяемые исключения не выходили из репозиториев в сервисы.
 */
public class DataAccessException extends RuntimeException {
    /** SQLState, с которым PostgreSQL прерывает запрос, в том числе по statement_timeout. */
    private static final String QUERY_CANCELED = "57014";

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * true, если запрос не выполнился из-за перегрузки: из пула не дождались свободного соединения
     * (Hikari бросает SQLTransientConnectionException) или PostgreSQL прервал запрос по statement_timeout.
     * Такие ошибки временные, на них клиенту отвечают 503, а не 500.
     */
    public static boolean isOverload(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
