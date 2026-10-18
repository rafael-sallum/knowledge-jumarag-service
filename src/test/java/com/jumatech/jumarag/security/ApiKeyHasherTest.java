package com.jumatech.jumarag.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ApiKeyHasherTest {
    private final ApiKeyHasher hasher = new ApiKeyHasher(new SecurityProperties(
            "test-admin-token-with-at-least-thirty-two-characters",
            "test-api-key-pepper-with-at-least-thirty-two-characters"));

    @Test
    void storesStableKeyedDigestRatherThanRawSecret() {
        String secret = "a-high-entropy-api-secret-that-is-never-persisted";

        byte[] digest = hasher.hash(secret);

        assertThat(digest).hasSize(32).isNotEqualTo(secret.getBytes(StandardCharsets.UTF_8));
        assertThat(hasher.matches(secret, digest)).isTrue();
        assertThat(hasher.matches(secret + "-wrong", digest)).isFalse();
    }

    @Test
    void parsesBase64UrlUnderscoresWithoutTreatingThemAsSeparators() {
        String publicId = "abc_defghijklmno";
        String secret = "secret_with_underscores" + "x".repeat(20);
        assertThat(publicId).hasSize(16);
        assertThat(secret).hasSize(43);

        var parsed = ApiClientService.parse("jrag_" + publicId + "_" + secret);

        assertThat(parsed).isNotNull();
        assertThat(parsed.publicId()).isEqualTo(publicId);
        assertThat(parsed.secret()).isEqualTo(secret);
    }
}
