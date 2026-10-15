package com.icqchat;

import com.icqchat.common.Json;
import com.icqchat.config.AppConfig;
import com.icqchat.controller.AuthController;
import com.icqchat.controller.MessageController;
import com.icqchat.controller.UserController;
import com.icqchat.db.DatabaseManager;
import com.icqchat.repository.MessageRepository;
import com.icqchat.repository.UserRepository;
import com.icqchat.service.AuthService;
import com.icqchat.service.JwtService;
import com.icqchat.service.LoginAttemptLimiter;
import com.icqchat.service.MessageService;
import com.icqchat.service.PasswordHasher;
import com.icqchat.service.UserService;
import com.icqchat.web.AuthFilter;
import com.icqchat.web.ErrorHandler;
import com.icqchat.websocket.ChatWebSocketHandler;
import com.icqchat.websocket.HeartbeatMonitor;
import com.icqchat.websocket.SessionRegistry;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * Собранное и запущенное приложение: все зависимости создаются и связываются здесь вручную.
 * Вынесено из App, чтобы интеграционные тесты могли поднять сервер со своей конфигурацией
 * (тестовая база, случайный порт) и остановить его.
 */
public final class ChatServer implements AutoCloseable {
    /** Лог HTTP-запросов: отдельный логгер, чтобы его уровень настраивался в logback.xml независимо от остальных. */
    private static final Logger REQUEST_LOG = LoggerFactory.getLogger("http");

    private final Javalin app;
    private final DatabaseManager database;
    private final HeartbeatMonitor heartbeatMonitor;

    private ChatServer(Javalin app, DatabaseManager database, HeartbeatMonitor heartbeatMonitor) {
        this.app = app;
        this.database = database;
        this.heartbeatMonitor = heartbeatMonitor;
    }

    /**
     * Применяет миграции и запускает HTTP-сервер. Порт 0 означает «любой свободный»,
     * реальный порт потом можно узнать через {@link #port()}.
     */
    public static ChatServer start(AppConfig config) {
        DatabaseManager database = new DatabaseManager(config);
        try {
            database.migrate();
            return startServer(config, database);
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
    }

    private static ChatServer startServer(AppConfig config, DatabaseManager database) {
        UserRepository userRepository = new UserRepository(database.dataSource());
        MessageRepository messageRepository = new MessageRepository(database.dataSource());
        SessionRegistry sessionRegistry = new SessionRegistry();

        JwtService jwtService = new JwtService(config.jwtSecret(), Duration.ofHours(config.jwtTtlHours()));
        AuthService authService = new AuthService(userRepository, new PasswordHasher(), jwtService,
                new LoginAttemptLimiter());
        UserService userService = new UserService(userRepository, sessionRegistry);
        MessageService messageService = new MessageService(messageRepository, userRepository);

        AuthController authController = new AuthController(authService);
        UserController userController = new UserController(userService);
        MessageController messageController = new MessageController(messageService);
        AuthFilter authFilter = new AuthFilter(jwtService);
        HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor(sessionRegistry);
        ChatWebSocketHandler wsHandler = new ChatWebSocketHandler(jwtService, messageService, sessionRegistry,
                heartbeatMonitor);

        Javalin app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson(Json.MAPPER, false));
            cfg.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setIdleTimeout(HeartbeatMonitor.TRANSPORT_IDLE_TIMEOUT);
                // Сжатие permessage-deflate Jetty включает по умолчанию, а браузеры всегда его запрашивают.
                // На каждое соединение оно держит буферы zlib вне Java-кучи, 100–250 КБ: на 10 000 соединений
                // это 1–2,5 ГБ. Наши сообщения — короткий JSON, сжимать в них почти нечего. Метод возвращает
                // живой набор расширений сервера, поэтому удаление из него отключает сжатие для всех соединений.
                factory.getAvailableExtensionNames().remove("permessage-deflate");
            });
            // Строка на каждый HTTP-запрос: метод, путь, статус и время ответа. Тела, заголовки и строка
            // запроса в лог не попадают: в них бывают пароли и токены.
            cfg.requestLogger.http((ctx, executionTimeMs) -> REQUEST_LOG.info("{} {} {} {} ms",
                    ctx.method(), ctx.path(), ctx.statusCode(), Math.round(executionTimeMs)));
        });

        ErrorHandler.register(app);

        app.get("/health", ctx -> ctx.json(Map.of("status", "UP")));

        app.before("/api/*", authFilter::handle);
        app.post("/api/auth/register", authController::register);
        app.post("/api/auth/login", authController::login);
        app.get("/api/me", authController::me);
        app.get("/api/users", userController::contacts);
        app.get("/api/messages/{userId}", messageController::history);

        app.ws("/ws", wsHandler::configure);

        app.start(config.port());
        heartbeatMonitor.start(wsHandler::onDeadSession);
        return new ChatServer(app, database, heartbeatMonitor);
    }

    public int port() {
        return app.port();
    }

    @Override
    public void close() {
        heartbeatMonitor.close();
        app.stop();
        database.close();
    }
}
