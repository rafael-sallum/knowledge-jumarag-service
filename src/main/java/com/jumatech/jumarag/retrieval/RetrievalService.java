package com.jumatech.jumarag.retrieval;

import com.jumatech.jumarag.embedding.EmbeddingGateway;
import com.jumatech.jumarag.embedding.EmbeddingProfileResolver;
import com.jumatech.jumarag.embedding.EmbeddingRepository;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.security.ApiClientService;
import com.jumatech.jumarag.snapshot.KnowledgeSnapshot;
import com.jumatech.jumarag.snapshot.SnapshotService;
import com.jumatech.jumarag.shared.web.DomainException;
import com.jumatech.jumarag.source.SourceService;
import com.jumatech.jumarag.provider.ProviderException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class RetrievalService {
    private final RetrievalRepository repository;
    private final EmbeddingGateway embeddings;
    private final KnowledgeBaseService knowledgeBases;
    private final SourceService sources;
    private final ApiClientService clients;
    private final SnapshotService snapshots;
    private final EmbeddingProfileResolver profiles;
    private final RetrievalProperties properties;
    private final MeterRegistry meterRegistry;

    public RetrievalService(
            RetrievalRepository repository,
            EmbeddingGateway embeddings,
            KnowledgeBaseService knowledgeBases,
            SourceService sources,
            ApiClientService clients,
            SnapshotService snapshots,
            EmbeddingProfileResolver profiles,
            RetrievalProperties properties,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.knowledgeBases = knowledgeBases;
        this.sources = sources;
        this.clients = clients;
        this.snapshots = snapshots;
        this.profiles = profiles;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public RetrievalResult searchAuthorized(
            UUID clientId, String knowledgeBaseSlug, String query, String language, UUID sourceId, int limit) {
        return searchAuthorized(clientId, knowledgeBaseSlug, query, language, sourceId, limit, null);
    }

    public RetrievalResult searchAuthorized(
            UUID clientId, String knowledgeBaseSlug, String query, String language,
            UUID sourceId, int limit, UUID snapshotId) {
        var knowledgeBase = knowledgeBases.requireBySlug(knowledgeBaseSlug);
        clients.requireRetrieveGrant(clientId, knowledgeBase.id());
        return search(knowledgeBase.id(), query, language, sourceId, limit, snapshotId);
    }

    public RetrievalResult search(
            UUID knowledgeBaseId, String query, String language, UUID sourceId, int limit) {
        return search(knowledgeBaseId, query, language, sourceId, limit, null);
    }

    public RetrievalResult search(
            UUID knowledgeBaseId, String query, String language, UUID sourceId, int limit, UUID snapshotId) {
        knowledgeBases.require(knowledgeBaseId);
        if (query == null || query.isBlank()) {
            throw new DomainException("INVALID_RETRIEVAL_QUERY", HttpStatus.BAD_REQUEST, "Query must not be blank.");
        }
        if (limit < 1 || limit > 50) {
            throw new DomainException("INVALID_RETRIEVAL_LIMIT", HttpStatus.BAD_REQUEST, "Limit must be between 1 and 50.");
        }
        String normalizedLanguage = null;
        if (language != null && !language.isBlank()) {
            KnowledgeBaseService.validateLocale(language);
            normalizedLanguage = Locale.forLanguageTag(language).toLanguageTag();
        }
        if (sourceId != null && !sources.requireSource(sourceId).knowledgeBaseId().equals(knowledgeBaseId)) {
            throw new DomainException(
                    "SOURCE_FILTER_OUTSIDE_KNOWLEDGE_BASE",
                    HttpStatus.BAD_REQUEST,
                    "Source filter does not belong to the selected knowledge base.");
        }
        KnowledgeSnapshot snapshot = snapshots.resolveForClient(knowledgeBaseId, snapshotId);
        EmbeddingRepository.EmbeddingProfile profile = profiles.requireCompatible(properties.embeddingProfile());
        float[] queryVector;
        try {
            queryVector = embeddings.embed(query);
        } catch (ProviderException exception) {
            throw new DomainException(
                    "AI_PROVIDER_UNAVAILABLE",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Embedding provider is temporarily unavailable; retry later.");
        }
        String finalLanguage = normalizedLanguage;
        List<RetrievalHit> hits = meterRegistry.timer("jumarag.retrieval.duration", "provider", embeddings.provider())
                .record(() -> repository.search(
                        knowledgeBaseId,
                        snapshot.id(),
                        profile.id(),
                        queryVector,
                        finalLanguage,
                        sourceId,
                        limit));
        return new RetrievalResult(
                knowledgeBaseId,
                snapshot.id(),
                "EXACT_COSINE",
                profile.name(),
                hits == null ? List.of() : hits);
    }

    public record RetrievalResult(
            UUID knowledgeBaseId,
            UUID snapshotId,
            String retrievalProfile,
            String embeddingProfile,
            List<RetrievalHit> hits) {}
}
