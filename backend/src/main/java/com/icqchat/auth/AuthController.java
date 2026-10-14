package com.icqchat.auth;

import io.javalin.http.Context;

public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public void register(Context ctx) {
        AuthRequest request = ctx.bodyAsClass(AuthRequest.class);
        ctx.status(201).json(authService.register(request));
    }

    public void login(Context ctx) {
        AuthRequest request = ctx.bodyAsClass(AuthRequest.class);
        ctx.json(authService.login(request));
    }

    public void me(Context ctx) {
        ctx.json(authService.me(AuthFilter.currentUserId(ctx)));
    }
}
