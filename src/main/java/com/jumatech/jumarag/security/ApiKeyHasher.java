package com.jumatech.jumarag.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class ApiKeyHasher {
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] pepper;

    public ApiKeyHasher(SecurityProperties properties) {
        this.pepper = properties.apiKeyPepper().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] hash(String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(pepper, ALGORITHM));
            return mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is required by the Java runtime.", exception);
        }
    }

    public boolean matches(String secret, byte[] expectedHash) {
        return expectedHash != null && MessageDigest.isEqual(hash(secret), expectedHash);
    }
}
