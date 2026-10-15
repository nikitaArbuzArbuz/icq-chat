package com.icqchat;

import com.icqchat.support.Api;
import com.icqchat.support.TestApp;
import com.icqchat.support.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Сжатие WebSocket")
class WebSocketCompressionIntegrationTest {
    private static final int READ_TIMEOUT_MILLIS = 5_000;

    /**
     * Рукопожатие отправляется вручную: клиент из java.net.http не умеет просить сжатие, а браузеры просят всегда.
     * Если сервер согласится, в ответе 101 будет заголовок Sec-WebSocket-Extensions с permessage-deflate.
     */
    @Test
    @DisplayName("сервер не включает permessage-deflate, даже когда клиент его просит, как браузер")
    void compressionIsNotNegotiated() throws IOException {
        TestUser user = Api.newUser();
        URI uri = TestApp.wsUri(user.token());

        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            String handshake = "GET " + uri.getRawPath() + "?" + uri.getRawQuery() + " HTTP/1.1\r\n"
                    + "Host: " + uri.getHost() + ":" + uri.getPort() + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(new byte[16]) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits\r\n"
                    + "\r\n";
            socket.getOutputStream().write(handshake.getBytes(StandardCharsets.US_ASCII));

            String headers = readHeaders(socket.getInputStream());
            assertTrue(headers.startsWith("HTTP/1.1 101"), headers);
            assertFalse(headers.toLowerCase(Locale.ROOT).contains("sec-websocket-extensions"), headers);
        }
    }

    /** Заголовки ответа, до пустой строки включительно. */
    private static String readHeaders(InputStream in) throws IOException {
        StringBuilder headers = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            headers.append((char) b);
            if (headers.length() >= 4 && headers.lastIndexOf("\r\n\r\n") == headers.length() - 4) {
                break;
            }
        }
        return headers.toString();
    }
}
