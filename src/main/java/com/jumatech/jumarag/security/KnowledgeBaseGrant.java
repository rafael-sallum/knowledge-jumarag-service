package com.jumatech.jumarag.security;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeBaseGrant(
        UUID apiClientId,
        UUID knowledgeBaseId,
        Permission permission,
        OffsetDateTime createdAt) {

    public enum Permission {
        RETRIEVE,
        ASK
    }
}
