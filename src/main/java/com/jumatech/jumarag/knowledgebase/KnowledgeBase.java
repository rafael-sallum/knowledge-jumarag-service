package com.jumatech.jumarag.knowledgebase;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeBase(
        UUID id,
        String slug,
        String name,
        String description,
        String defaultLocale,
        Status status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public enum Status {
        ACTIVE,
        DISABLED
    }
}
