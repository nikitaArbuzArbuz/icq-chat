package com.icqchat.service;

/**
 * Кто сейчас в сети. Сервисам неважно, откуда это известно: сейчас ответ даёт реестр WebSocket-сессий
 * (SessionRegistry), а при нескольких экземплярах сервера это может быть общее хранилище, например Redis.
 */
public interface PresenceProvider {
    boolean isOnline(long userId);
}
