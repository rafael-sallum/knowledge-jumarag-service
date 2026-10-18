package com.jumatech.jumarag.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record KnowledgeSnapshot(
        UUID id,
        UUID knowledgeBaseId,
        String name,
        Status status,
        JsonNode metadata,
        OffsetDateTime createdAt,
        OffsetDateTime activatedAt,
        List<Item> items) {

    public enum Status {
        DRAFT,
        ACTIVE,
        RETIRED
    }

    public record Item(
            UUID knowledgeSourceId,
            UUID sourceVersionId,
            String logicalSourceName,
            String sourceClassification,
            String versionTitle,
            String language) {}
}
