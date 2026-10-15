package com.icqchat.websocket;

import com.icqchat.common.Json;
import com.icqchat.service.PresenceProvider;
import io.javalin.websocket.WsContext;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Потокобезопасный реестр открытых WebSocket-сессий, сгруппированных по id пользователя.
 * Пользователь считается онлайн, пока открыта хотя бы одна его сессия (вкладка браузера).
 * <p>
 * Отправка асинхронная: сообщение ставится в очередь Jetty, и поток, который его отправил, не ждёт,
 * пока клиент его примет. Поэтому медленный клиент не тормозит тех, кто ему пишет. Чтобы его очередь
 * не росла бесконечно, при {@link #MAX_PENDING_MESSAGES} неотправленных сообщениях соединение
 * разрывается: клиент переподключится и получит недоставленное заново.
 * <p>
 * Онлайн-статус рассылается не всем, а только сессиям, которые на этого пользователя подписаны
 * через {@link #watch}: клиент подписывается на тех, кого показывает в списке контактов.
 */
public class SessionRegistry implements PresenceProvider {
    /** Сколько сообщений может ждать отправки в одну сессию, прежде чем она будет признана слишком медленной. */
    public static final int MAX_PENDING_MESSAGES = 1000;

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<Long, Set<WsContext>> sessions = new ConcurrentHashMap<>();
    /** Состояние каждой открытой сессии по sessionId: очередь отправки и подписки на онлайн-статусы. */
    private final Map<String, SessionState> states = new ConcurrentHashMap<>();
    /** Подписчики на онлайн-статус: id пользователя -> сессии, которые за ним следят. */
    private final Map<Long, Set<WsContext>> watchers = new ConcurrentHashMap<>();
    /** Защищает согласованность watchers и SessionState.watching при смене подписок. */
    private final Object watchLock = new Object();

    private static final class SessionState {
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicBoolean dropped = new AtomicBoolean();
        /** На кого подписана сессия. Меняется только под watchLock. */
        private Set<Long> watching = Set.of();
    }

    /**
     * Регистрирует сессию. Если это первая сессия пользователя, подписчики получают presence online.
     * Рассылка идёт внутри compute, то есть под блокировкой этого пользователя, поэтому события
     * «онлайн» и «офлайн» одного пользователя не могут прийти подписчикам в обратном порядке.
     * Под блокировкой это дёшево: отправка только ставит сообщение в очередь.
     *
     * @return true, если пользователь только что появился в сети
     */
    public boolean add(long userId, WsContext ctx) {
        states.put(ctx.sessionId(), new SessionState());
        boolean[] cameOnline = {false};
        sessions.compute(userId, (id, set) -> {
            Set<WsContext> next = set == null ? ConcurrentHashMap.newKeySet() : set;
            cameOnline[0] = next.isEmpty();
            next.add(ctx);
            if (cameOnline[0]) {
                notifyWatchers(userId, true);
            }
            return next;
        });
        return cameOnline[0];
    }

    /**
     * Убирает сессию вместе с её подписками. Повторный вызов для той же сессии ничего не делает.
     *
     * @return true, если это была последняя сессия пользователя и он вышел из сети
     */
    public boolean remove(long userId, WsContext ctx) {
        SessionState state = states.remove(ctx.sessionId());
        if (state != null) {
            replaceWatching(ctx, state, Set.of());
        }
        boolean[] wentOffline = {false};
        sessions.computeIfPresent(userId, (id, set) -> {
            if (set.remove(ctx) && set.isEmpty()) {
                wentOffline[0] = true;
                notifyWatchers(userId, false);
            }
            return set.isEmpty() ? null : set;
        });
        return wentOffline[0];
    }

    @Override
    public boolean isOnline(long userId) {
        Set<WsContext> set = sessions.get(userId);
        return set != null && !set.isEmpty();
    }

    /**
     * Заменяет подписки сессии на онлайн-статусы указанных пользователей.
     *
     * @return кто из них сейчас онлайн, чтобы клиент сверил состояние на момент подписки
     */
    public List<Long> watch(long userId, WsContext ctx, Collection<Long> userIds) {
        Set<Long> next = userIds.stream().filter(id -> id != userId).collect(Collectors.toUnmodifiableSet());
        SessionState state = states.get(ctx.sessionId());
        if (state == null) {
            return List.of();
        }
        replaceWatching(ctx, state, next);
        return next.stream().filter(this::isOnline).sorted().toList();
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

    /**
     * Ставит сообщение в очередь отправки сессии и сразу возвращается. Jetty сам сериализует запись
     * в сокет и сохраняет порядок постановки в очередь, поэтому дополнительная синхронизация не нужна.
     */
    public void send(WsContext ctx, String json) {
        if (!ctx.session.isOpen()) {
            return;
        }
        SessionState state = states.get(ctx.sessionId());
        if (state == null) {
            // Сессия не зарегистрирована или уже закрыта: отправляем без учёта очереди.
            sendAsync(ctx, json, WriteCallback.NOOP);
            return;
        }
        if (state.pending.incrementAndGet() > MAX_PENDING_MESSAGES) {
            state.pending.decrementAndGet();
            dropSlowSession(ctx, state);
            return;
        }
        sendAsync(ctx, json, new WriteCallback() {
            @Override
            public void writeSuccess() {
                state.pending.decrementAndGet();
            }

            @Override
            public void writeFailed(Throwable error) {
                state.pending.decrementAndGet();
                log.debug("Failed to send message to session {}: {}", ctx.sessionId(), error.getMessage());
            }
        });
    }

    /** Закрывает сессию с кодом и причиной. Закрытие тоже встаёт в очередь Jetty после уже отправленного. */
    public void close(WsContext ctx, int code, String reason) {
        try {
            if (ctx.session.isOpen()) {
                ctx.closeSession(code, reason);
            }
        } catch (Exception e) {
            log.warn("Failed to close session {}: {}", ctx.sessionId(), e.getMessage());
        }
    }

    private void notifyWatchers(long userId, boolean online) {
        Set<WsContext> set = watchers.get(userId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String json = Json.write(new WsEvents.PresenceEvent(userId, online));
        set.forEach(watcher -> send(watcher, json));
    }

    private void replaceWatching(WsContext ctx, SessionState state, Set<Long> requested) {
        synchronized (watchLock) {
            // Сессию могли закрыть в другом потоке (например, монитор heartbeat) между states.get в watch()
            // и этой блокировкой. remove() убирает состояние из states до того, как чистит подписки под этой же
            // блокировкой, поэтому если состояния уже нет, новые подписки добавлять нельзя: их некому будет удалить.
            Set<Long> next = states.get(ctx.sessionId()) == state ? requested : Set.of();
            for (Long id : state.watching) {
                if (!next.contains(id)) {
                    watchers.computeIfPresent(id, (key, set) -> {
                        set.remove(ctx);
                        return set.isEmpty() ? null : set;
                    });
                }
            }
            for (Long id : next) {
                if (!state.watching.contains(id)) {
                    watchers.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet()).add(ctx);
                }
            }
            state.watching = next;
        }
    }

    private static void sendAsync(WsContext ctx, String json, WriteCallback callback) {
        try {
            ctx.session.getRemote().sendString(json, callback);
        } catch (Exception e) {
            callback.writeFailed(e);
        }
    }

    /**
     * Клиент не успевает забирать сообщения. Обычное закрытие встало бы в конец той же забитой очереди,
     * поэтому соединение разрывается сразу. Разрыв идёт в другом потоке: send может быть вызван
     * под блокировкой реестра, а закрытие сессии снова заходит в реестр через onClose.
     */
    private static void dropSlowSession(WsContext ctx, SessionState state) {
        if (state.dropped.compareAndSet(false, true)) {
            log.warn("Session {} is too slow: {} messages pending, disconnecting", ctx.sessionId(), MAX_PENDING_MESSAGES);
            CompletableFuture.runAsync(ctx.session::disconnect);
        }
    }
}
