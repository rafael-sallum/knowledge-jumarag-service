package com.jumatech.jumarag.source;

import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.shared.web.DomainException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class SourceService {
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private final SourceRepository repository;
    private final KnowledgeBaseService knowledgeBases;
    private final Clock clock;

    @Autowired
    public SourceService(SourceRepository repository, KnowledgeBaseService knowledgeBases) {
        this(repository, knowledgeBases, Clock.systemUTC());
    }

    SourceService(SourceRepository repository, KnowledgeBaseService knowledgeBases, Clock clock) {
        this.repository = repository;
        this.knowledgeBases = knowledgeBases;
        this.clock = clock;
    }

    public KnowledgeSource create(
            UUID knowledgeBaseId,
            KnowledgeSource.SourceType type,
            String logicalName,
            String canonicalUri) {
        knowledgeBases.require(knowledgeBaseId);
        validateCanonicalUri(type, canonicalUri);
        OffsetDateTime now = OffsetDateTime.now(clock);
        KnowledgeSource source = new KnowledgeSource(
                UUID.randomUUID(), knowledgeBaseId, type, logicalName.trim(), canonicalUri, true, now, now);
        try {
            return repository.insert(source);
        } catch (DuplicateKeyException exception) {
            throw new DomainException(
                    "KNOWLEDGE_SOURCE_ALREADY_EXISTS",
                    HttpStatus.CONFLICT,
                    "A source with that logical name already exists in this knowledge base.");
        }
    }

    public List<KnowledgeSource> list(UUID knowledgeBaseId) {
        knowledgeBases.require(knowledgeBaseId);
        return repository.findByKnowledgeBase(knowledgeBaseId);
    }

    public SourceVersion createVersion(
            UUID sourceId,
            String versionLabel,
            String checksum,
            String mediaType,
            long byteSize,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        requireSource(sourceId);
        if (checksum == null || !SHA_256.matcher(checksum).matches()) {
            throw new DomainException(
                    "INVALID_SOURCE_CHECKSUM", HttpStatus.BAD_REQUEST, "Checksum must be lowercase SHA-256 hex.");
        }
        if (byteSize < 0 || (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom))) {
            throw new DomainException(
                    "INVALID_SOURCE_VERSION", HttpStatus.BAD_REQUEST, "Source version size or effective dates are invalid.");
        }
        SourceVersion version = new SourceVersion(
                UUID.randomUUID(),
                sourceId,
                versionLabel,
                checksum,
                mediaType,
                byteSize,
                effectiveFrom,
                effectiveTo,
                SourceVersion.Status.REGISTERED,
                OffsetDateTime.now(clock));
        try {
            return repository.insertVersion(version);
        } catch (DuplicateKeyException exception) {
            throw new DomainException(
                    "SOURCE_VERSION_ALREADY_EXISTS", HttpStatus.CONFLICT, "This source checksum is already registered.");
        }
    }

    public KnowledgeSource requireSource(UUID sourceId) {
        return repository.findById(sourceId).orElseThrow(() -> new DomainException(
                "KNOWLEDGE_SOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "Knowledge source was not found."));
    }

    public SourceVersion requireVersion(UUID versionId) {
        return repository.findVersionById(versionId).orElseThrow(() -> new DomainException(
                "SOURCE_VERSION_NOT_FOUND", HttpStatus.NOT_FOUND, "Source version was not found."));
    }

    private static void validateCanonicalUri(KnowledgeSource.SourceType type, String canonicalUri) {
        if (canonicalUri == null || canonicalUri.isBlank()) {
            if (type == KnowledgeSource.SourceType.URL || type == KnowledgeSource.SourceType.SITEMAP) {
                throw new DomainException(
                        "CANONICAL_URI_REQUIRED", HttpStatus.BAD_REQUEST, "URL and sitemap sources require a canonical URI.");
            }
            return;
        }
        try {
            URI uri = URI.create(canonicalUri);
            if (!List.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new DomainException(
                    "INVALID_CANONICAL_URI", HttpStatus.BAD_REQUEST, "Canonical URI must be an absolute HTTP(S) URI.");
        }
    }
}
