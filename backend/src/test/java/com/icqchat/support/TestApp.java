package com.icqchat.support;

import com.icqchat.ChatServer;
import com.icqchat.config.AppConfig;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Один PostgreSQL в Docker и один запущенный сервер на все тестовые классы: поднимаются
 * при первом обращении и живут до конца прогона. Контейнер после прогона удаляет сам
 * Testcontainers. Тесты не мешают друг другу, потому что каждый создаёт своих пользователей.
 */
public final class TestApp {
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final int RANDOM_PORT = 0;
    private static final int DB_POOL_SIZE = 5;
    private static final String JWT_SECRET = "integration-tests-secret-at-least-32-chars";
    private static final long JWT_TTL_HOURS = 1;

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);
    private static final ChatServer SERVER = startServer();

    private TestApp() {
    }

    private static ChatServer startServer() {
        POSTGRES.start();
        return ChatServer.start(config(DB_POOL_SIZE));
    }

    /** Конфигурация с тестовой базой. Тестам пула нужен свой размер пула, поэтому он параметр. */
    public static AppConfig config(int dbPoolSize) {
        return new AppConfig(RANDOM_PORT, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), dbPoolSize, JWT_SECRET, JWT_TTL_HOURS);
    }

    public static URI httpUri(String pathAndQuery) {
        return URI.create("http://localhost:" + SERVER.port() + pathAndQuery);
    }

    public static URI wsUri(String token) {
        return URI.create("ws://localhost:" + SERVER.port() + "/ws?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8));
    }
}
