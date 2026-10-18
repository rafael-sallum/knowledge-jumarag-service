package com.jumatech.jumarag.source;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentChunk(
        UUID id,
        UUID sourceVersionId,
        int chunkIndex,
        String text,
        String language,
        String headingPath,
        Integer pageStart,
        Integer pageEnd,
        JsonNode metadata,
        OffsetDateTime createdAt) {}
