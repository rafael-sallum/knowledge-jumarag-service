package com.jumatech.jumarag.embedding;

import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.provider.ProviderException;
import com.jumatech.jumarag.shared.web.DomainException;
import com.jumatech.jumarag.snapshot.SnapshotService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class EmbeddingBackfillService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillService.class);
    private static final int MAX_BATCH_SIZE = 100;
    private final KnowledgeBaseService knowledgeBases;
    private final SnapshotService snapshots;
    private final EmbeddingProfileResolver profiles;
    private final EmbeddingGateway gateway;
    private final EmbeddingRepository embeddings;
    private final EmbeddingBackfillRepository repository;
    private final MeterRegistry metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public EmbeddingBackfillService(
            KnowledgeBaseService knowledgeBases,
            SnapshotService snapshots,
            EmbeddingProfileResolver profiles,
            EmbeddingGateway gateway,
            EmbeddingRepository embeddings,
            EmbeddingBackfillRepository repository,
            MeterRegistry metrics,
            TransactionTemplate transactions) {
        this(
                knowledgeBases,
                snapshots,
                profiles,
                gateway,
                embeddings,
                repository,
                metrics,
                transactions,
                Clock.systemUTC());
    }

    EmbeddingBackfillService(
            KnowledgeBaseService knowledgeBases,
            SnapshotService snapshots,
            EmbeddingProfileResolver profiles,
            EmbeddingGateway gateway,
            EmbeddingRepository embeddings,
            EmbeddingBackfillRepository repository,
            MeterRegistry metrics,
            TransactionTemplate transactions,
            Clock clock) {
        this.knowledgeBases = knowledgeBases;
        this.snapshots = snapshots;
        this.profiles = profiles;
        this.gateway = gateway;
        this.embeddings = embeddings;
        this.repository = repository;
        this.metrics = metrics;
        this.transactions = transactions;
        this.clock = clock;
    }

    public BackfillResult backfill(UUID knowledgeBaseId, String profileName, UUID snapshotId, Integer requestedBatchSize) {
        knowledgeBases.require(knowledgeBaseId);
        if (snapshotId != null) {
            snapshots.require(knowledgeBaseId, snapshotId);
        }
        int batchSize = requestedBatchSize == null ? 25 : requestedBatchSize;
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new DomainException(
                    "INVALID_EMBEDDING_BATCH_SIZE", HttpStatus.BAD_REQUEST, "Batch size must be between 1 and 100.");
        }
        EmbeddingRepository.EmbeddingProfile profile = profiles.requireCompatible(profileName);
        Attempt attempt = transactions.execute(status -> executeBatch(
                knowledgeBaseId, snapshotId, profile, batchSize));
        if (attempt == null) {
            throw new IllegalStateException("Embedding backfill transaction returned no result.");
        }
        if (attempt.failure() != null) {
            metrics.counter("jumarag.embedding.backfill", "profile", profile.name(), "outcome", "failure").increment();
            log.warn("Embedding backfill interrupted knowledgeBaseId={} snapshotId={} profile={} embedded={} reason={}",
                    knowledgeBaseId, snapshotId, profile.name(), attempt.embedded(), attempt.failure().reason());
            throw new DomainException(
                    "AI_PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "Embedding provider is temporarily unavailable; completed vectors remain safe and retryable.");
        }
        metrics.counter("jumarag.embedding.backfill", "profile", profile.name(), "outcome", "success").increment();
        metrics.summary("jumarag.embedding.backfill.chunks", "profile", profile.name()).record(attempt.embedded());
        log.info("Embedding backfill completed knowledgeBaseId={} snapshotId={} profile={} embedded={} remaining={}",
                knowledgeBaseId, snapshotId, profile.name(), attempt.embedded(), attempt.remaining());
        return new BackfillResult(
                knowledgeBaseId,
                snapshotId,
                profile.name(),
                attempt.eligible(),
                attempt.alreadyEmbedded(),
                attempt.embedded(),
                attempt.remaining(),
                batchSize);
    }

    private Attempt executeBatch(
            UUID knowledgeBaseId,
            UUID snapshotId,
            EmbeddingRepository.EmbeddingProfile profile,
            int batchSize) {
        repository.lock(knowledgeBaseId, profile.id());
        EmbeddingBackfillRepository.Counts before = repository.counts(knowledgeBaseId, snapshotId, profile.id());
        var missing = repository.findMissing(knowledgeBaseId, snapshotId, profile.id(), batchSize);
        int embedded = 0;
        ProviderException failure = null;
        for (EmbeddingBackfillRepository.ChunkForEmbedding chunk : missing) {
            if (embeddings.exists(chunk.id(), profile.id())) {
                continue;
            }
            try {
                embeddings.upsert(
                        chunk.id(), profile.id(), gateway.embed(chunk.text()), OffsetDateTime.now(clock));
                embedded++;
            } catch (ProviderException exception) {
                failure = exception;
                break;
            }
        }
        EmbeddingBackfillRepository.Counts after = repository.counts(knowledgeBaseId, snapshotId, profile.id());
        return new Attempt(
                before.eligible(),
                before.embedded(),
                embedded,
                after.eligible() - after.embedded(),
                failure);
    }

    public record BackfillResult(
            UUID knowledgeBaseId,
            UUID snapshotId,
            String embeddingProfile,
            int eligibleChunks,
            int alreadyEmbedded,
            int embedded,
            int remaining,
            int batchSize) {}

    private record Attempt(
            int eligible,
            int alreadyEmbedded,
            int embedded,
            int remaining,
            ProviderException failure) {}
}
