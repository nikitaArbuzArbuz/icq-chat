package com.icqchat.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.icqchat.user.User;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Выдаёт и проверяет токены HS256. В subject токена лежит id пользователя.
 */
public class JwtService {
    private static final String ISSUER = "icq-chat";

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final Duration ttl;

    public JwtService(String secret, Duration ttl) {
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).withIssuer(ISSUER).build();
        this.ttl = ttl;
    }

    public String issue(User user) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(Long.toString(user.id()))
                .withClaim("login", user.login())
                .withIssuedAt(now)
                .withExpiresAt(now.plus(ttl))
                .sign(algorithm);
    }

    /** Данные из валидного токена: id пользователя и момент, когда токен истекает. */
    public record VerifiedToken(long userId, Instant expiresAt) {
    }

    /**
     * @return id пользователя из валидного и не истёкшего токена
     */
    public Optional<Long> verify(String token) {
        return verifyToken(token).map(VerifiedToken::userId);
    }

    /**
     * @return id пользователя и срок действия из валидного и не истёкшего токена
     */
    public Optional<VerifiedToken> verifyToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            DecodedJWT jwt = verifier.verify(token);
            Instant expiresAt = jwt.getExpiresAtAsInstant();
            if (expiresAt == null) {
                // Все наши токены выдаются со сроком действия, бессрочный токен не принимаем.
                return Optional.empty();
            }
            return Optional.of(new VerifiedToken(Long.parseLong(jwt.getSubject()), expiresAt));
        } catch (JWTVerificationException | NumberFormatException e) {
            return Optional.empty();
        }
    }
}
