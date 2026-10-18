package com.jumatech.jumarag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record RetrievalHit(
        UUID chunkId,
        UUID sourceId,
        UUID sourceVersionId,
        String logicalSourceName,
        String sourceClassification,
        String sourceTitle,
        String originalFilename,
        int chunkIndex,
        String text,
        String language,
        String headingPath,
        Integer pageStart,
        Integer pageEnd,
        JsonNode metadata,
        JsonNode sourceMetadata,
        JsonNode sourceVersionMetadata,
        double similarity) {}
