package com.jumatech.jumarag.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ApiClient(
        UUID id,
        String name,
        String keyPrefix,
        boolean enabled,
        JsonNode metadata,
        OffsetDateTime createdAt,
        OffsetDateTime disabledAt) {}
