package com.jumatech.jumarag.source;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeSource(
        UUID id,
        UUID knowledgeBaseId,
        SourceType sourceType,
        String logicalName,
        String canonicalUri,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public enum SourceType {
        TEXT,
        FILE,
        PDF,
        URL,
        SITEMAP
    }
}
