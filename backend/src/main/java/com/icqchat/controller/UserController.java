package com.icqchat.controller;

import com.icqchat.service.UserService;
import com.icqchat.web.CurrentUser;
import io.javalin.http.Context;

public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    public void contacts(Context ctx) {
        long userId = CurrentUser.id(ctx);
        ctx.json(userService.contacts(userId, ctx.queryParam("search")));
    }
}
