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
 * Issues and verifies HS256 tokens. The subject of the token is the user id.
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

    /**
     * @return user id from a valid, non-expired token
     */
    public Optional<Long> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            DecodedJWT jwt = verifier.verify(token);
            return Optional.of(Long.parseLong(jwt.getSubject()));
        } catch (JWTVerificationException | NumberFormatException e) {
            return Optional.empty();
        }
    }
}
