package com.jumatech.jumarag.answer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.answer")
public record AnswerProperties(
        String chatProvider,
        int retrievalLimit,
        double minimumSimilarity,
        int minimumTokenOverlap,
        int maxQuestionCharacters,
        int maxContextBytes,
        int maxContextDepth,
        int maxEvidenceChunks,
        int maxEvidenceCharacters) {

    public AnswerProperties {
        chatProvider = chatProvider == null || chatProvider.isBlank() ? "fake" : chatProvider;
        if (retrievalLimit < 1 || retrievalLimit > 50) {
            throw new IllegalArgumentException("Answer retrieval limit must be between 1 and 50.");
        }
        if (minimumSimilarity < -1 || minimumSimilarity > 1) {
            throw new IllegalArgumentException("Minimum similarity must be between -1 and 1.");
        }
        if (minimumTokenOverlap < 1 || maxQuestionCharacters < 1 || maxContextBytes < 2 || maxContextDepth < 1
                || maxEvidenceChunks < 1 || maxEvidenceChunks > retrievalLimit || maxEvidenceCharacters < 256) {
            throw new IllegalArgumentException("Answer validation limits must be positive.");
        }
    }
}
