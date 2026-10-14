package com.icqchat.db;

import com.icqchat.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;

/**
 * Владеет пулом соединений и при старте применяет миграции Liquibase.
 * <p>
 * Пул настроен так, чтобы под нагрузкой быстро отказывать, а не зависать: запрос ждёт свободное
 * соединение не дольше {@link #CONNECTION_TIMEOUT}, а сам SQL-запрос выполняется не дольше
 * {@link #STATEMENT_TIMEOUT}. Такие отказы API превращает в ответ 503.
 */
public class DatabaseManager implements AutoCloseable {
    /**
     * Сколько запрос ждёт свободное соединение из пула. У Hikari по умолчанию 30 секунд:
     * при исчерпании пула сервер полминуты не отвечал бы и только потом отдавал ошибку.
     */
    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(3);
    /**
     * Сколько может выполняться один SQL-запрос. Наши запросы укладываются в миллисекунды,
     * всё, что дольше, означает перегрузку базы или ошибку, и такой запрос лучше прервать,
     * чем держать занятым соединение из пула.
     */
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(5);
    /** Если соединение не вернули в пул за это время, Hikari пишет в лог предупреждение со стеком: так ищутся утечки. */
    private static final Duration LEAK_DETECTION_THRESHOLD = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    private final AppConfig config;
    private final HikariDataSource dataSource;

    public DatabaseManager(AppConfig config) {
        this.config = config;
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.dbUrl());
        hikari.setUsername(config.dbUser());
        hikari.setPassword(config.dbPassword());
        hikari.setMaximumPoolSize(config.dbPoolSize());
        hikari.setPoolName("chat-pool");
        hikari.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
        hikari.setLeakDetectionThreshold(LEAK_DETECTION_THRESHOLD.toMillis());
        // Таймаут передаётся параметром подключения, а не командой SET: он действует на каждое
        // соединение пула с момента подключения, и его не сбросит RESET.
        hikari.addDataSourceProperty("options", "-c statement_timeout=" + STATEMENT_TIMEOUT.toMillis());
        this.dataSource = new HikariDataSource(hikari);
    }

    /**
     * Миграции идут через отдельное соединение мимо пула, поэтому на них не действует statement_timeout:
     * создание индекса или заполнение таблицы на большой базе может занять гораздо дольше пяти секунд.
     */
    public void migrate() {
        log.info("Running database migrations");
        try (Connection connection = DriverManager.getConnection(config.dbUrl(), config.dbUser(), config.dbPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
            liquibase.update(new Contexts(), new LabelExpression());
            log.info("Database migrations finished");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to run database migrations", e);
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
