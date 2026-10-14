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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * Точка входа и сборка приложения: все зависимости создаются и связываются здесь вручную.
 */
public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();

        DatabaseManager database = new DatabaseManager(config);
        database.migrate();

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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            heartbeatMonitor.close();
            app.stop();
            database.close();
        }));

        app.start(config.port());
        heartbeatMonitor.start(wsHandler::onDeadSession);
        log.info("Chat server started on port {}", config.port());
    }
}
