package com.jumatech.jumarag.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(String adminToken, String apiKeyPepper) {
    public SecurityProperties {
        if (adminToken == null || adminToken.length() < 32) {
            throw new IllegalArgumentException("Admin token must contain at least 32 characters.");
        }
        if (apiKeyPepper == null || apiKeyPepper.length() < 32) {
            throw new IllegalArgumentException("API-key pepper must contain at least 32 characters.");
        }
    }
}
