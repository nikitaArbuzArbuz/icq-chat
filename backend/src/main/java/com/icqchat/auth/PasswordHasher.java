package com.icqchat.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;

public class PasswordHasher {
    private static final int COST = 12;

    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(COST, password.toCharArray());
    }

    public boolean verify(String password, String hash) {
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
    }
}
