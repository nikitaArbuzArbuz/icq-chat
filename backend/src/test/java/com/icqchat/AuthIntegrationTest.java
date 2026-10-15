package com.icqchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.icqchat.support.Api;
import com.icqchat.support.Api.Response;
import com.icqchat.support.ChatWs;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static com.icqchat.support.Api.PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Регистрация и вход")
class AuthIntegrationTest {

    @Test
    @DisplayName("регистрация выдаёт токен, вход по тем же данным возвращает того же пользователя")
    void registerThenLogin() {
        String login = Api.uniqueLogin();

        Response registered = Api.register(login, PASSWORD);
        assertEquals(201, registered.status(), registered.body()::toString);
        assertEquals(login, registered.body().at("/user/login").asText());
        assertFalse(registered.body().path("token").asText().isBlank());

        Response loggedIn = Api.login(login, PASSWORD);
        assertEquals(200, loggedIn.status(), loggedIn.body()::toString);
        assertEquals(registered.body().at("/user/id").asLong(), loggedIn.body().at("/user/id").asLong());

        Response me = Api.get("/api/me", loggedIn.body().path("token").asText());
        assertEquals(200, me.status());
        assertEquals(login, me.body().path("login").asText());
    }

    @Test
    @DisplayName("занятый логин: 409 LOGIN_TAKEN")
    void loginTaken() {
        TestUser user = Api.newUser();
        assertError(Api.register(user.login(), "another-password"), 409, "LOGIN_TAKEN");
    }

    @Test
    @DisplayName("логин, отличающийся только регистром, считается занятым: 409")
    void loginTakenIgnoringCase() {
        TestUser user = Api.newUser();
        assertError(Api.register(user.login().toUpperCase(), PASSWORD), 409, "LOGIN_TAKEN");
        assertError(Api.register(capitalize(user.login()), PASSWORD), 409, "LOGIN_TAKEN");
    }

    @Test
    @DisplayName("вход без учёта регистра, логин отображается так, как его зарегистрировали")
    void loginIgnoresCase() {
        String login = "Mixed" + Api.uniqueLogin();
        Response registered = Api.register(login, PASSWORD);
        assertEquals(201, registered.status(), registered.body()::toString);

        Response loggedIn = Api.login(login.toLowerCase(), PASSWORD);
        assertEquals(200, loggedIn.status(), loggedIn.body()::toString);
        assertEquals(registered.body().at("/user/id").asLong(), loggedIn.body().at("/user/id").asLong());
        assertEquals(login, loggedIn.body().at("/user/login").asText());
    }

    private static String capitalize(String login) {
        return Character.toUpperCase(login.charAt(0)) + login.substring(1);
    }

    @ParameterizedTest(name = "логин \"{0}\": 400")
    @ValueSource(strings = {"ab", "abcdefghijklmnopqrstuvwxyz1234567", "with space", "кириллица", "dash-dash"})
    @DisplayName("невалидный логин: 400 VALIDATION_ERROR")
    void invalidLogin(String login) {
        assertError(Api.register(login, PASSWORD), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("пароль короче 8 символов: 400")
    void shortPassword() {
        assertError(Api.register(Api.uniqueLogin(), "1234567"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("без логина и пароля: 400 и при регистрации, и при входе")
    void missingFields() {
        assertError(Api.post("/api/auth/register", "{}"), 400, "VALIDATION_ERROR");
        assertError(Api.post("/api/auth/login", "{}"), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("пароль ровно 72 байта (36 букв кириллицы) принимается, с ним можно войти")
    void passwordOfExactly72Bytes() {
        String login = Api.uniqueLogin();
        String password = "я".repeat(36);
        assertEquals(201, Api.register(login, password).status());
        assertEquals(200, Api.login(login, password).status());
    }

    static Stream<Arguments> passwordsLongerThan72Bytes() {
        return Stream.of(
                arguments("73 латинских символа, 73 байта", "a".repeat(73)),
                arguments("37 букв кириллицы, 74 байта", "я".repeat(37)),
                arguments("19 эмодзи, 76 байт", "\uD83D\uDE00".repeat(19)));
    }

    @ParameterizedTest(name = "{0}: 400")
    @MethodSource("passwordsLongerThan72Bytes")
    @DisplayName("пароль длиннее 72 байт в UTF-8 при регистрации: 400, а не 500")
    void passwordLongerThan72Bytes(String description, String password) {
        assertError(Api.register(Api.uniqueLogin(), password), 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("вход с паролем длиннее 72 байт: 401, а не 500")
    void loginWithTooLongPassword() {
        TestUser user = Api.newUser();
        assertError(Api.login(user.login(), "я".repeat(37)), 401, "INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("неверный пароль и несуществующий логин дают одинаковый ответ 401")
    void wrongCredentials() {
        TestUser user = Api.newUser();
        Response wrongPassword = Api.login(user.login(), "wrong-password");
        Response unknownLogin = Api.login(Api.uniqueLogin(), PASSWORD);

        assertError(wrongPassword, 401, "INVALID_CREDENTIALS");
        assertError(unknownLogin, 401, "INVALID_CREDENTIALS");
        assertEquals(wrongPassword.body(), unknownLogin.body());
    }

    @Test
    @DisplayName("после 10 неудачных попыток вход в аккаунт закрыт: 429 TOO_MANY_ATTEMPTS с Retry-After, даже с верным паролем")
    void tooManyLoginAttempts() {
        TestUser user = Api.newUser();
        for (int i = 0; i < 10; i++) {
            assertError(Api.login(user.login(), "wrong-password"), 401, "INVALID_CREDENTIALS");
        }

        Response blocked = Api.login(user.login(), PASSWORD);
        assertError(blocked, 429, "TOO_MANY_ATTEMPTS");
        long retryAfterSeconds = Long.parseLong(blocked.header("Retry-After"));
        assertTrue(retryAfterSeconds > 0 && retryAfterSeconds <= 15 * 60, () -> "Retry-After: " + retryAfterSeconds);

        // Лимит у каждого аккаунта свой: другие пользователи входят как обычно.
        TestUser other = Api.newUser();
        assertEquals(200, Api.login(other.login(), PASSWORD).status());
    }

    @Test
    @DisplayName("API без токена или с поддельным токеном: 401")
    void apiRequiresToken() {
        assertError(Api.get("/api/me", null), 401, "UNAUTHORIZED");
        assertError(Api.get("/api/me", "not-a-jwt"), 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("WebSocket с невалидным токеном закрывается с кодом 4401")
    void websocketRejectsInvalidToken() {
        try (ChatWs ws = ChatWs.connect("not-a-jwt")) {
            assertEquals(4401, ws.awaitCloseCode());
        }
    }

    private static void assertError(Response response, int status, String code) {
        JsonNode body = response.body();
        assertEquals(status, response.status(), body::toString);
        assertEquals(code, response.errorCode(), body::toString);
    }
}
