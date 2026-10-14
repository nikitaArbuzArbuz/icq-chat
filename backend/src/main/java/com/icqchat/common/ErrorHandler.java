package com.icqchat.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.icqchat.user.DataAccessException;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Глобальная обработка исключений: любая ошибка возвращается клиенту в виде JSON, стектрейсы остаются только в логах.
 */
public final class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
    /** Через сколько секунд клиенту предлагается повторить запрос при перегрузке. */
    private static final String RETRY_AFTER_SECONDS = "1";

    private ErrorHandler() {
    }

    public static void register(Javalin app) {
        app.exception(ApiException.class, (e, ctx) ->
                ctx.status(e.status()).json(new ErrorResponse(e.code(), e.getMessage())));

        app.exception(BadRequestResponse.class, (e, ctx) ->
                ctx.status(400).json(new ErrorResponse("BAD_REQUEST", e.getMessage())));

        app.exception(JsonProcessingException.class, (e, ctx) ->
                ctx.status(400).json(new ErrorResponse("BAD_JSON", "Некорректный JSON в теле запроса")));

        // Перегрузка базы временная: отвечаем 503 с Retry-After, чтобы клиент понял, что запрос можно повторить.
        app.exception(DataAccessException.class, (e, ctx) -> {
            if (DataAccessException.isOverload(e)) {
                log.warn("Database overloaded on {} {}: {}", ctx.method(), ctx.path(), e.getCause().getMessage());
                ctx.status(503).header("Retry-After", RETRY_AFTER_SECONDS)
                        .json(new ErrorResponse("SERVICE_UNAVAILABLE", "Сервер перегружен, попробуйте ещё раз"));
            } else {
                log.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
                ctx.status(500).json(new ErrorResponse("INTERNAL_ERROR", "Внутренняя ошибка сервера"));
            }
        });

        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(new ErrorResponse("INTERNAL_ERROR", "Внутренняя ошибка сервера"));
        });
    }
}
