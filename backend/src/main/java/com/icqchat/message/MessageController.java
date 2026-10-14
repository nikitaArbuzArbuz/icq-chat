package com.icqchat.message;

import com.icqchat.auth.AuthFilter;
import com.icqchat.common.ApiException;
import io.javalin.http.Context;

public class MessageController {
    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    public void history(Context ctx) {
        long userId = AuthFilter.currentUserId(ctx);
        long peerId = parseLong(ctx.pathParam("userId"), "userId");
        Long before = ctx.queryParam("before") == null ? null : parseLong(ctx.queryParam("before"), "before");
        Integer limit = ctx.queryParam("limit") == null ? null : (int) parseLong(ctx.queryParam("limit"), "limit");
        ctx.json(messageService.history(userId, peerId, before, limit));
    }

    private static long parseLong(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Параметр " + name + " должен быть числом");
        }
    }
}
