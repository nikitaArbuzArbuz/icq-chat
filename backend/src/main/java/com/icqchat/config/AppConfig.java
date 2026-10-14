package com.icqchat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application configuration read from environment variables with defaults for local development.
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
    private static final String DEFAULT_JWT_SECRET = "dev-secret-change-me-please-at-least-32-chars";

    public static AppConfig fromEnv() {
        String jwtSecret = env("JWT_SECRET", DEFAULT_JWT_SECRET);
        if (DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            log.warn("JWT_SECRET is not set, using insecure default secret. Do not use it in production!");
        }
        return new AppConfig(
                Integer.parseInt(env("PORT", "8080")),
                env("DB_URL", "jdbc:postgresql://localhost:5432/chat"),
                env("DB_USER", "chat"),
                env("DB_PASSWORD", "chat"),
                Integer.parseInt(env("DB_POOL_SIZE", "10")),
                jwtSecret,
                Long.parseLong(env("JWT_TTL_HOURS", "24"))
        );
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
