package com.icqchat.ws;

import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Потокобезопасный реестр открытых WebSocket-сессий, сгруппированных по id пользователя.
 * Пользователь считается онлайн, пока открыта хотя бы одна его сессия (вкладка браузера).
 */
public class SessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<Long, Set<WsContext>> sessions = new ConcurrentHashMap<>();

    /**
     * @return true, если это первая сессия пользователя, то есть он только что появился в сети
     */
    public boolean add(long userId, WsContext ctx) {
        boolean[] cameOnline = {false};
        sessions.compute(userId, (id, set) -> {
            if (set == null) {
                set = ConcurrentHashMap.newKeySet();
            }
            if (set.isEmpty()) {
                cameOnline[0] = true;
            }
            set.add(ctx);
            return set;
        });
        return cameOnline[0];
    }

    /**
     * @return true, если это была последняя сессия пользователя, то есть он только что вышел из сети
     */
    public boolean remove(long userId, WsContext ctx) {
        boolean[] wentOffline = {false};
        sessions.computeIfPresent(userId, (id, set) -> {
            if (set.remove(ctx) && set.isEmpty()) {
                wentOffline[0] = true;
                return null;
            }
            return set.isEmpty() ? null : set;
        });
        return wentOffline[0];
    }

    public boolean isOnline(long userId) {
        Set<WsContext> set = sessions.get(userId);
        return set != null && !set.isEmpty();
    }

    public void sendToUser(long userId, String json) {
        sendToUserExcept(userId, null, json);
    }

    public void sendToUserExcept(long userId, WsContext except, String json) {
        Set<WsContext> set = sessions.get(userId);
        if (set == null) {
            return;
        }
        for (WsContext ctx : set) {
            // WsContext.equals в Javalin приводит аргумент к WsContext без проверки на null,
            // поэтому ctx.equals(null) бросает NPE. Сравниваем, только если есть сессия, которую надо пропустить.
            if (except == null || !ctx.equals(except)) {
                send(ctx, json);
            }
        }
    }

    public void sendToAllExcept(long userId, String json) {
        sessions.forEach((id, set) -> {
            if (id != userId) {
                set.forEach(ctx -> send(ctx, json));
            }
        });
    }

    /**
     * Закрывает сессию с кодом и причиной. Синхронизировано по сессии, как и {@link #send},
     * чтобы закрытие не пересеклось с записью из другого потока.
     */
    public void close(WsContext ctx, int code, String reason) {
        synchronized (ctx.session) {
            try {
                if (ctx.session.isOpen()) {
                    ctx.closeSession(code, reason);
                }
            } catch (Exception e) {
                log.warn("Failed to close session {}: {}", ctx.sessionId(), e.getMessage());
            }
        }
    }

    /**
     * Jetty не разрешает писать в одну сессию из нескольких потоков одновременно, поэтому запись синхронизирована по сессии.
     */
    public void send(WsContext ctx, String json) {
        synchronized (ctx.session) {
            try {
                if (ctx.session.isOpen()) {
                    ctx.send(json);
                }
            } catch (Exception e) {
                log.warn("Failed to send message to session {}: {}", ctx.sessionId(), e.getMessage());
            }
        }
    }
}
