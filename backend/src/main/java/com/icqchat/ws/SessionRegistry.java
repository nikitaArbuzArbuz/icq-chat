package com.icqchat.ws;

import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of open WebSocket sessions grouped by user id.
 * A user is online while at least one of their sessions (browser tabs) is open.
 */
public class SessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<Long, Set<WsContext>> sessions = new ConcurrentHashMap<>();

    /**
     * @return true if this is the first session of the user, i.e. the user just came online
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
     * @return true if this was the last session of the user, i.e. the user just went offline
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
            // WsContext.equals in Javalin casts the argument without a null check,
            // so ctx.equals(null) throws NPE. Compare only when there is a session to skip.
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
     * Jetty does not allow concurrent writes to one session, so writes are serialized per session.
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
