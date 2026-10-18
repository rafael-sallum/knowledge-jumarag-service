package com.jumatech.jumarag.embedding;

import com.jumatech.jumarag.source.ChunkService;
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
    private final Clock clock;

    @Autowired
    public EmbeddingService(ChunkService chunks, EmbeddingGateway gateway, EmbeddingRepository repository) {
        this(chunks, gateway, repository, Clock.systemUTC());
    }

    EmbeddingService(ChunkService chunks, EmbeddingGateway gateway, EmbeddingRepository repository, Clock clock) {
        this.chunks = chunks;
        this.gateway = gateway;
        this.repository = repository;
        this.clock = clock;
    }

    public EmbeddedChunk embed(UUID chunkId) {
        var chunk = chunks.require(chunkId);
        repository.upsert(
                chunk.id(),
                EmbeddingRepository.PHASE_ZERO_PROFILE_ID,
                gateway.embed(chunk.text()),
                OffsetDateTime.now(clock));
        return new EmbeddedChunk(chunk.id(), EmbeddingRepository.PHASE_ZERO_PROFILE_ID, gateway.dimensions());
    }

    public record EmbeddedChunk(UUID chunkId, UUID embeddingProfileId, int dimensions) {}
}
