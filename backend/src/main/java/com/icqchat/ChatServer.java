package com.icqchat;

import com.icqchat.auth.AuthController;
import com.icqchat.auth.AuthFilter;
import com.icqchat.auth.AuthService;
import com.icqchat.auth.JwtService;
import com.icqchat.auth.PasswordHasher;
import com.icqchat.common.ErrorHandler;
import com.icqchat.common.Json;
import com.icqchat.config.AppConfig;
import com.icqchat.db.DatabaseManager;
import com.icqchat.message.MessageController;
import com.icqchat.message.MessageRepository;
import com.icqchat.message.MessageService;
import com.icqchat.user.UserController;
import com.icqchat.user.UserRepository;
import com.icqchat.user.UserService;
import com.icqchat.ws.ChatWebSocketHandler;
import com.icqchat.ws.HeartbeatMonitor;
import com.icqchat.ws.SessionRegistry;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

import java.time.Duration;
import java.util.Map;

/**
 * Собранное и запущенное приложение: все зависимости создаются и связываются здесь вручную.
 * Вынесено из App, чтобы интеграционные тесты могли поднять сервер со своей конфигурацией
 * (тестовая база, случайный порт) и остановить его.
 */
public final class ChatServer implements AutoCloseable {
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
        AuthService authService = new AuthService(userRepository, new PasswordHasher(), jwtService);
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
            cfg.jetty.modifyWebSocketServletFactory(factory ->
                    factory.setIdleTimeout(HeartbeatMonitor.TRANSPORT_IDLE_TIMEOUT));
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
