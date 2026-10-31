package com.jumatech.jumarag.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.context")
public record ContextProperties(
        int ragMaxChunks,
        int ragMaxCharacters,
        int ragMaxEstimatedTokens,
        int longContextMaxChunks,
        int longContextMaxCharacters,
        int longContextMaxEstimatedTokens,
        int hybridNeighborChunks,
        int hybridMaxChunks,
        int hybridMaxCharacters,
        int hybridMaxEstimatedTokens,
        double autoStrongSimilarity) {

    public ContextProperties {
        requirePositive(ragMaxChunks, "RAG max chunks");
        requirePositive(ragMaxCharacters, "RAG max characters");
        requirePositive(ragMaxEstimatedTokens, "RAG max estimated tokens");
        requirePositive(longContextMaxChunks, "long-context max chunks");
        requirePositive(longContextMaxCharacters, "long-context max characters");
        requirePositive(longContextMaxEstimatedTokens, "long-context max estimated tokens");
        if (hybridNeighborChunks < 0 || hybridNeighborChunks > 10) {
            throw new IllegalArgumentException("Hybrid neighbor chunks must be between 0 and 10.");
        }
        requirePositive(hybridMaxChunks, "hybrid max chunks");
        requirePositive(hybridMaxCharacters, "hybrid max characters");
        requirePositive(hybridMaxEstimatedTokens, "hybrid max estimated tokens");
        if (autoStrongSimilarity < -1 || autoStrongSimilarity > 1) {
            throw new IllegalArgumentException("AUTO strong similarity must be between -1 and 1.");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
    }
}
