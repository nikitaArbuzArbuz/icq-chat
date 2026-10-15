package com.icqchat.web;

import com.icqchat.exception.ApiException;
import io.javalin.http.Context;

/**
 * Id пользователя текущего HTTP-запроса. Его кладёт {@link AuthFilter} после проверки токена,
 * а контроллеры берут только отсюда: id, присланному клиентом, сервер не доверяет.
 */
public final class CurrentUser {
    private static final String USER_ID_ATTRIBUTE = "userId";

    private CurrentUser() {
    }

    static void set(Context ctx, long userId) {
        ctx.attribute(USER_ID_ATTRIBUTE, userId);
    }

    public static long id(Context ctx) {
        Long userId = ctx.attribute(USER_ID_ATTRIBUTE);
        if (userId == null) {
            throw ApiException.unauthorized("Требуется авторизация");
        }
        return userId;
    }
}
