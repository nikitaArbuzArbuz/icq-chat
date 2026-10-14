package com.icqchat.ws;

import com.icqchat.common.Json;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Heartbeat на уровне приложения: следит, что WebSocket-сессии живы и их токены не истекли.
 * <p>
 * Сервер раз в {@link #PING_INTERVAL} шлёт каждой сессии {"type":"ping"}, клиент отвечает
 * {"type":"pong"}. Любое входящее сообщение от клиента продлевает сессию. Если от сессии ничего
 * не приходило дольше {@link #HEARTBEAT_TIMEOUT} или истёк срок действия её токена, монитор
 * передаёт её в {@link DeadSessionHandler}, который закрывает сессию.
 * <p>
 * Пинги идут JSON-сообщениями, а не ping-кадрами протокола: на ping-кадры браузер отвечает сам
 * и не показывает их JavaScript, поэтому клиент по ним не смог бы понять, что сервер пропал.
 * Пингует именно сервер: в фоновых вкладках браузер замедляет таймеры до раза в минуту,
 * а входящие сообщения доставляет сразу, так что ответ на ping не опаздывает.
 */
public class HeartbeatMonitor implements AutoCloseable {
    /** Как часто сервер пингует каждую сессию. */
    public static final Duration PING_INTERVAL = Duration.ofSeconds(20);
    /**
     * Сколько сессия может молчать до закрытия. Больше двух интервалов пинга,
     * чтобы один потерянный ответ не рвал соединение.
     */
    public static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(45);
    /**
     * Таймаут простоя на уровне Jetty, последний рубеж на случай, если монитор не сработал.
     * Должен быть больше HEARTBEAT_TIMEOUT, иначе Jetty закроет соединение раньше монитора
     * и клиент не получит понятный код закрытия.
     */
    public static final Duration TRANSPORT_IDLE_TIMEOUT = HEARTBEAT_TIMEOUT.plusSeconds(15);
    /** Как часто проверяются таймауты и сроки действия токенов. */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);
    private static final String PING = Json.write(new WsEvents.PingEvent());

    public enum Reason {
        HEARTBEAT_TIMEOUT,
        TOKEN_EXPIRED
    }

    @FunctionalInterface
    public interface DeadSessionHandler {
        void onDeadSession(WsContext ctx, long userId, Reason reason);
    }

    private final SessionRegistry registry;
    private final Clock clock;
    private final Map<String, TrackedSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ws-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public HeartbeatMonitor(SessionRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    HeartbeatMonitor(SessionRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    public void start(DeadSessionHandler handler) {
        long ping = PING_INTERVAL.toMillis();
        long check = CHECK_INTERVAL.toMillis();
        scheduler.scheduleWithFixedDelay(() -> runSafely(this::pingAll), ping, ping, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> runSafely(() -> checkSessions(handler)), check, check, TimeUnit.MILLISECONDS);
    }

    public void track(WsContext ctx, long userId, Instant tokenExpiresAt) {
        sessions.put(ctx.sessionId(), new TrackedSession(ctx, userId, tokenExpiresAt, clock.instant()));
    }

    /** Отмечает, что от сессии пришло сообщение, и продлевает её. */
    public void touch(WsContext ctx) {
        TrackedSession session = sessions.get(ctx.sessionId());
        if (session != null) {
            session.lastSeen = clock.instant();
        }
    }

    public void untrack(WsContext ctx) {
        sessions.remove(ctx.sessionId());
    }

    void pingAll() {
        sessions.values().forEach(session -> registry.send(session.ctx, PING));
    }

    void checkSessions(DeadSessionHandler handler) {
        Instant now = clock.instant();
        sessions.forEach((id, session) -> {
            Reason reason = deadReason(session, now);
            // remove(id, session) гарантирует, что одну сессию закроют только один раз.
            if (reason != null && sessions.remove(id, session)) {
                runSafely(() -> handler.onDeadSession(session.ctx, session.userId, reason));
            }
        });
    }

    private static Reason deadReason(TrackedSession session, Instant now) {
        if (!now.isBefore(session.tokenExpiresAt)) {
            return Reason.TOKEN_EXPIRED;
        }
        if (Duration.between(session.lastSeen, now).compareTo(HEARTBEAT_TIMEOUT) > 0) {
            return Reason.HEARTBEAT_TIMEOUT;
        }
        return null;
    }

    /** Исключение в задаче ScheduledExecutorService молча отменяет все её следующие запуски, поэтому ловим всё. */
    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("Heartbeat task failed", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private static final class TrackedSession {
        private final WsContext ctx;
        private final long userId;
        private final Instant tokenExpiresAt;
        private volatile Instant lastSeen;

        private TrackedSession(WsContext ctx, long userId, Instant tokenExpiresAt, Instant lastSeen) {
            this.ctx = ctx;
            this.userId = userId;
            this.tokenExpiresAt = tokenExpiresAt;
            this.lastSeen = lastSeen;
        }
    }
}
