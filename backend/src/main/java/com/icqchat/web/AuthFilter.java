package com.icqchat.web;

import com.icqchat.exception.ApiException;
import com.icqchat.service.JwtService;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;

/**
 * Проверяет Bearer-токен у каждого запроса к /api, кроме регистрации и входа.
 * Id пользователя из токена кладётся в атрибуты запроса ({@link CurrentUser}) и дальше берётся только оттуда.
 */
public class AuthFilter {
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public AuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    public void handle(Context ctx) {
        if (ctx.method() == HandlerType.OPTIONS || ctx.path().startsWith("/api/auth/")) {
            return;
        }
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw ApiException.unauthorized("Требуется авторизация");
        }
        long userId = jwtService.verify(header.substring(BEARER_PREFIX.length()))
                .orElseThrow(() -> ApiException.unauthorized("Токен недействителен или истёк"));
        CurrentUser.set(ctx, userId);
    }
}
