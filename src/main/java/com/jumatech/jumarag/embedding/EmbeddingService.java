package com.jumatech.jumarag.embedding;

import com.jumatech.jumarag.source.ChunkService;
import com.jumatech.jumarag.ingestion.IngestionProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class EmbeddingService {
    private final ChunkService chunks;
    private final EmbeddingGateway gateway;
    private final EmbeddingRepository repository;
    private final EmbeddingProfileResolver profiles;
    private final IngestionProperties properties;
    private final Clock clock;

    @Autowired
    public EmbeddingService(
            ChunkService chunks,
            EmbeddingGateway gateway,
            EmbeddingRepository repository,
            EmbeddingProfileResolver profiles,
            IngestionProperties properties) {
        this(chunks, gateway, repository, profiles, properties, Clock.systemUTC());
    }

    EmbeddingService(
            ChunkService chunks,
            EmbeddingGateway gateway,
            EmbeddingRepository repository,
            EmbeddingProfileResolver profiles,
            IngestionProperties properties,
            Clock clock) {
        this.chunks = chunks;
        this.gateway = gateway;
        this.repository = repository;
        this.profiles = profiles;
        this.properties = properties;
        this.clock = clock;
    }

    public EmbeddedChunk embed(UUID chunkId) {
        var chunk = chunks.require(chunkId);
        var profile = profiles.requireCompatible(properties.defaultEmbeddingProfile());
        repository.upsert(
                chunk.id(),
                profile.id(),
                gateway.embed(chunk.text()),
                OffsetDateTime.now(clock));
        return new EmbeddedChunk(chunk.id(), profile.id(), profile.name(), gateway.dimensions());
    }

    public record EmbeddedChunk(UUID chunkId, UUID embeddingProfileId, String embeddingProfile, int dimensions) {}
}
