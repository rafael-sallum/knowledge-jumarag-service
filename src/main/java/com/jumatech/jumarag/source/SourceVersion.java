package com.jumatech.jumarag.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SourceVersion(
        UUID id,
        UUID sourceId,
        String versionLabel,
        String checksum,
        String mediaType,
        long byteSize,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Status status,
        OffsetDateTime createdAt) {

    public enum Status {
        REGISTERED,
        READY,
        FAILED,
        DISABLED
    }
}
