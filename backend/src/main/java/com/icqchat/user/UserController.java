package com.icqchat.user;

import com.icqchat.auth.AuthFilter;
import io.javalin.http.Context;

public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    public void contacts(Context ctx) {
        long userId = AuthFilter.currentUserId(ctx);
        ctx.json(userService.contacts(userId, ctx.queryParam("search")));
    }
}
