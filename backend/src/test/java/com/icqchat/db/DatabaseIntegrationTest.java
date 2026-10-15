package com.icqchat.db;

import com.icqchat.exception.DataAccessException;
import com.icqchat.support.TestApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Пул соединений и таймауты базы")
class DatabaseIntegrationTest {
    private static final int POOL_SIZE = 2;

    @Test
    @DisplayName("на соединениях пула действует statement_timeout")
    void statementTimeoutIsSet() throws SQLException {
        try (DatabaseManager database = new DatabaseManager(TestApp.config(POOL_SIZE));
             Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW statement_timeout")) {
            rs.next();
            assertEquals(DatabaseManager.STATEMENT_TIMEOUT.toSeconds() + "s", rs.getString(1));
        }
    }

    @Test
    @DisplayName("запрос дольше statement_timeout прерывается, и это распознаётся как перегрузка")
    void longQueryIsCanceled() throws SQLException {
        long sleepSeconds = DatabaseManager.STATEMENT_TIMEOUT.toSeconds() + 1;
        try (DatabaseManager database = new DatabaseManager(TestApp.config(POOL_SIZE));
             Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class,
                    () -> statement.execute("SELECT pg_sleep(" + sleepSeconds + ")"));
            assertEquals("57014", error.getSQLState());
            assertTrue(DataAccessException.isOverload(new DataAccessException("query failed", error)));
        }
    }

    @Test
    @DisplayName("когда свободных соединений нет, запрос получает ошибку через connectionTimeout, а не через 30 секунд")
    void exhaustedPoolFailsFast() throws SQLException {
        try (DatabaseManager database = new DatabaseManager(TestApp.config(1));
             Connection busy = database.dataSource().getConnection()) {
            long start = System.nanoTime();
            SQLException error = assertThrows(SQLException.class, () -> database.dataSource().getConnection());
            Duration waited = Duration.ofNanos(System.nanoTime() - start);

            assertTrue(DataAccessException.isOverload(error));
            assertTrue(waited.compareTo(DatabaseManager.CONNECTION_TIMEOUT.plusSeconds(2)) < 0, waited::toString);
        }
    }

    @Test
    @DisplayName("обычные ошибки SQL перегрузкой не считаются")
    void otherErrorsAreNotOverload() {
        assertFalse(DataAccessException.isOverload(new SQLException("syntax error", "42601")));
        assertFalse(DataAccessException.isOverload(new SQLException("duplicate key", "23505")));
        assertFalse(DataAccessException.isOverload(null));
    }
}
