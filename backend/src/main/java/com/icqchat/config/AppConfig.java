package com.icqchat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Application configuration. Every value comes from the environment, there are no defaults in code:
 * if something is missing, startup fails with the full list of missing variables.
 * <p>
 * For local runs values are also read from a .env file (current directory or one level up,
 * i.e. the repository root). Process environment variables take precedence over .env.
 */
public record AppConfig(
        int port,
        String dbUrl,
        String dbUser,
        String dbPassword,
        int dbPoolSize,
        String jwtSecret,
        long jwtTtlHours
) {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static final int MIN_JWT_SECRET_LENGTH = 32;

    public static AppConfig fromEnv() {
        Env env = new Env(loadDotEnv());

        int port = env.requireInt("PORT");
        String dbHost = env.require("DB_HOST");
        int dbPort = env.requireInt("DB_PORT");
        String dbName = env.require("POSTGRES_DB");
        String dbUser = env.require("POSTGRES_USER");
        String dbPassword = env.require("POSTGRES_PASSWORD");
        int dbPoolSize = env.requireInt("DB_POOL_SIZE");
        String jwtSecret = env.require("JWT_SECRET");
        long jwtTtlHours = env.requireLong("JWT_TTL_HOURS");

        env.failIfErrors();

        if (jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_JWT_SECRET_LENGTH + " characters long");
        }

        String dbUrl = "jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName;
        return new AppConfig(port, dbUrl, dbUser, dbPassword, dbPoolSize, jwtSecret, jwtTtlHours);
    }

    private static Map<String, String> loadDotEnv() {
        for (Path path : List.of(Path.of(".env"), Path.of("..", ".env"))) {
            if (Files.isRegularFile(path)) {
                log.info("Loading variables from {}", path.toAbsolutePath().normalize());
                return parseDotEnv(path);
            }
        }
        return Map.of();
    }

    private static Map<String, String> parseDotEnv(Path path) {
        Map<String, String> values = new HashMap<>();
        try {
            for (String rawLine : Files.readAllLines(path)) {
                String line = rawLine.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2
                        && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
        return values;
    }

    /** Collects all configuration errors to report them at once instead of one by one. */
    private static final class Env {
        private final Map<String, String> dotEnv;
        private final List<String> errors = new ArrayList<>();

        Env(Map<String, String> dotEnv) {
            this.dotEnv = dotEnv;
        }

        String require(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                value = dotEnv.get(name);
            }
            if (value == null || value.isBlank()) {
                errors.add(name + " is not set");
                return null;
            }
            return value;
        }

        int requireInt(String name) {
            String value = require(name);
            if (value == null) {
                return 0;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                errors.add(name + " must be an integer, got: " + value);
                return 0;
            }
        }

        long requireLong(String name) {
            String value = require(name);
            if (value == null) {
                return 0;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                errors.add(name + " must be an integer, got: " + value);
                return 0;
            }
        }

        void failIfErrors() {
            if (!errors.isEmpty()) {
                throw new IllegalStateException(
                        "Invalid configuration: " + String.join("; ", errors));
            }
        }
    }
}
