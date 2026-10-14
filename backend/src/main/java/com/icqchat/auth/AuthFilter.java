package com.icqchat.auth;

import com.icqchat.common.ApiException;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;

/**
 * Проверяет Bearer-токен у каждого запроса к /api, кроме регистрации и входа.
 * Id пользователя из токена кладётся в атрибуты запроса и дальше берётся только оттуда.
 */
public class AuthFilter {
    private static final String USER_ID_ATTRIBUTE = "userId";
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
        ctx.attribute(USER_ID_ATTRIBUTE, userId);
    }

    public static long currentUserId(Context ctx) {
        Long userId = ctx.attribute(USER_ID_ATTRIBUTE);
        if (userId == null) {
            throw ApiException.unauthorized("Требуется авторизация");
        }
        return userId;
    }
}
