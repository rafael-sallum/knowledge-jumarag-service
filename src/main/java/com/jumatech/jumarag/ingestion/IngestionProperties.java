package com.jumatech.jumarag.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ingestion")
public record IngestionProperties(
        long maxUploadBytes,
        int targetWords,
        int maxWords,
        int overlapWords,
        String defaultEmbeddingProfile) {

    public IngestionProperties {
        if (maxUploadBytes < 1 || targetWords < 10 || maxWords < targetWords) {
            throw new IllegalArgumentException("Ingestion size limits are invalid.");
        }
        if (overlapWords < 0 || overlapWords >= targetWords) {
            throw new IllegalArgumentException("Chunk overlap must be smaller than the target chunk size.");
        }
        if (defaultEmbeddingProfile == null || defaultEmbeddingProfile.isBlank()) {
            throw new IllegalArgumentException("A default embedding profile is required.");
        }
    }
}
