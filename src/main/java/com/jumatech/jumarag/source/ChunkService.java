package com.jumatech.jumarag.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.shared.web.DomainException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class ChunkService {
    private final ChunkRepository repository;
    private final SourceService sources;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ChunkService(ChunkRepository repository, SourceService sources, ObjectMapper objectMapper) {
        this(repository, sources, objectMapper, Clock.systemUTC());
    }

    ChunkService(ChunkRepository repository, SourceService sources, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.sources = sources;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public DocumentChunk create(
            UUID sourceVersionId,
            int chunkIndex,
            String text,
            String language,
            String headingPath,
            Integer pageStart,
            Integer pageEnd,
            JsonNode metadata) {
        sources.requireVersion(sourceVersionId);
        KnowledgeBaseService.validateLocale(language);
        if (chunkIndex < 0 || text == null || text.isBlank()) {
            throw new DomainException(
                    "INVALID_DOCUMENT_CHUNK", HttpStatus.BAD_REQUEST, "Chunk index and text are invalid.");
        }
        if ((pageStart == null) != (pageEnd == null)
                || (pageStart != null && (pageStart < 1 || pageEnd < pageStart))) {
            throw new DomainException(
                    "INVALID_PAGE_RANGE", HttpStatus.BAD_REQUEST, "Page range must contain positive ordered endpoints.");
        }
        DocumentChunk chunk = new DocumentChunk(
                UUID.randomUUID(),
                sourceVersionId,
                chunkIndex,
                text,
                Locale.forLanguageTag(language).toLanguageTag(),
                headingPath,
                pageStart,
                pageEnd,
                metadata == null ? objectMapper.createObjectNode() : metadata,
                OffsetDateTime.now(clock));
        try {
            return repository.insert(chunk);
        } catch (DuplicateKeyException exception) {
            throw new DomainException(
                    "CHUNK_INDEX_ALREADY_EXISTS",
                    HttpStatus.CONFLICT,
                    "This source version already has a chunk at that index.");
        }
    }

    public DocumentChunk require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new DomainException(
                "DOCUMENT_CHUNK_NOT_FOUND", HttpStatus.NOT_FOUND, "Document chunk was not found."));
    }
}
