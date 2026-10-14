package com.icqchat;

import com.icqchat.support.Api;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Ошибки, которые формирует сам Javalin, в общем формате JSON")
class HttpErrorsIntegrationTest {

    @Test
    @DisplayName("неизвестный адрес: 404 NOT_FOUND в JSON, а не текстом")
    void unknownEndpoint() {
        TestUser alice = Api.newUser();

        // Api разбирает каждый ответ как JSON, поэтому ответ текстом уронил бы тест ещё на самом запросе.
        Api.Response api = Api.get("/api/no-such-endpoint", alice.token());
        assertEquals(404, api.status());
        assertEquals("NOT_FOUND", api.errorCode());
        assertTrue(api.body().path("message").asText().contains("/api/no-such-endpoint"), api.body()::toString);

        Api.Response outsideApi = Api.get("/no-such-page", null);
        assertEquals(404, outsideApi.status());
        assertEquals("NOT_FOUND", outsideApi.errorCode());
    }

    @Test
    @DisplayName("существующий адрес с неподходящим методом: тоже 404 в JSON")
    void wrongMethod() {
        Api.Response response = Api.get("/api/auth/login", null);
        assertEquals(404, response.status());
        assertEquals("NOT_FOUND", response.errorCode());
    }
}
