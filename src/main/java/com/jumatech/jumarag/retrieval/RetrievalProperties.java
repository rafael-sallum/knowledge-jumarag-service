package com.jumatech.jumarag.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.retrieval")
public record RetrievalProperties(String embeddingProfile) {
    public RetrievalProperties {
        if (embeddingProfile == null || embeddingProfile.isBlank()) {
            throw new IllegalArgumentException("A retrieval embedding profile is required.");
        }
    }
}
