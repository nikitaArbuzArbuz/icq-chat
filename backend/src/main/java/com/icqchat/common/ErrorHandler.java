package com.icqchat.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global exception handling: every error is returned as JSON, stack traces stay in the logs.
 */
public final class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    private ErrorHandler() {
    }

    public static void register(Javalin app) {
        app.exception(ApiException.class, (e, ctx) ->
                ctx.status(e.status()).json(new ErrorResponse(e.code(), e.getMessage())));

        app.exception(BadRequestResponse.class, (e, ctx) ->
                ctx.status(400).json(new ErrorResponse("BAD_REQUEST", e.getMessage())));

        app.exception(JsonProcessingException.class, (e, ctx) ->
                ctx.status(400).json(new ErrorResponse("BAD_JSON", "Некорректный JSON в теле запроса")));

        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(new ErrorResponse("INTERNAL_ERROR", "Внутренняя ошибка сервера"));
        });
    }
}
