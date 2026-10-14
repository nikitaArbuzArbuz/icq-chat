package com.icqchat;

import com.icqchat.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа: читает конфигурацию из окружения и запускает сервер.
 */
public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        ChatServer server = ChatServer.start(AppConfig.fromEnv());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            server.close();
        }));
        log.info("Chat server started on port {}", server.port());
    }
}
