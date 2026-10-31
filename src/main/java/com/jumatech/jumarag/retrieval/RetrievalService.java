package com.jumatech.jumarag.retrieval;

import com.jumatech.jumarag.embedding.EmbeddingGateway;
import com.jumatech.jumarag.embedding.EmbeddingProfileResolver;
import com.jumatech.jumarag.embedding.EmbeddingRepository;
import com.jumatech.jumarag.evidence.EvidenceClassificationPolicy;
import com.jumatech.jumarag.evidence.EvidenceIntent;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.provider.ProviderException;
import com.jumatech.jumarag.security.ApiClientService;
import com.jumatech.jumarag.snapshot.KnowledgeSnapshot;
import com.jumatech.jumarag.snapshot.SnapshotService;
import com.jumatech.jumarag.shared.web.DomainException;
import com.jumatech.jumarag.source.SourceService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final EvidenceClassificationPolicy evidenceClassifications;

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
        this(repository, embeddings, knowledgeBases, sources, clients, snapshots, profiles, properties,
                meterRegistry, new EvidenceClassificationPolicy());
    }

    @Autowired
    public RetrievalService(
            RetrievalRepository repository,
            EmbeddingGateway embeddings,
            KnowledgeBaseService knowledgeBases,
            SourceService sources,
            ApiClientService clients,
            SnapshotService snapshots,
            EmbeddingProfileResolver profiles,
            RetrievalProperties properties,
            MeterRegistry meterRegistry,
            EvidenceClassificationPolicy evidenceClassifications) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.knowledgeBases = knowledgeBases;
        this.sources = sources;
        this.clients = clients;
        this.snapshots = snapshots;
        this.profiles = profiles;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.evidenceClassifications = evidenceClassifications;
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
        KnowledgeSnapshot snapshot = snapshots.resolveForClient(knowledgeBaseId, snapshotId);
        return searchWithinSnapshot(knowledgeBaseId, snapshot, query, language, sourceId, limit);
    }

    public RetrievalResult searchWithinSnapshot(
            UUID knowledgeBaseId, KnowledgeSnapshot snapshot, String query,
            String language, UUID sourceId, int limit) {
        return searchWithinSnapshot(knowledgeBaseId, snapshot, query, language, sourceId,
                EvidenceIntent.GENERAL, limit);
    }

    public RetrievalResult searchWithinSnapshot(
            UUID knowledgeBaseId, KnowledgeSnapshot snapshot, String query,
            String language, UUID sourceId, EvidenceIntent evidenceIntent, int limit) {
        knowledgeBases.require(knowledgeBaseId);
        if (!snapshot.knowledgeBaseId().equals(knowledgeBaseId)) {
            throw new DomainException("SNAPSHOT_OUTSIDE_KNOWLEDGE_BASE", HttpStatus.BAD_REQUEST,
                    "Snapshot does not belong to the selected knowledge base.");
        }
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
        List<Set<String>> groups = evidenceClassifications.retrievalGroups(evidenceIntent);
        List<List<RetrievalHit>> groupedHits = meterRegistry
                .timer("jumarag.retrieval.duration", "provider", embeddings.provider())
                .record(() -> groups.stream()
                        .map(group -> repository.search(
                                knowledgeBaseId,
                                snapshot.id(),
                                profile.id(),
                                queryVector,
                                finalLanguage,
                                sourceId,
                                group,
                                limit))
                        .toList());
        List<RetrievalHit> hits = mergeGroups(groupedHits == null ? List.of() : groupedHits, limit);
        return new RetrievalResult(
                knowledgeBaseId,
                snapshot.id(),
                "EXACT_COSINE",
                profile.name(),
                hits == null ? List.of() : hits);
    }

    private static List<RetrievalHit> mergeGroups(List<List<RetrievalHit>> groups, int limit) {
        if (groups.size() <= 1) {
            return groups.isEmpty() ? List.of() : groups.getFirst();
        }
        Map<UUID, RetrievalHit> merged = new LinkedHashMap<>();
        int greatest = groups.stream().mapToInt(List::size).max().orElse(0);
        for (int rank = 0; rank < greatest && merged.size() < limit; rank++) {
            for (List<RetrievalHit> group : groups) {
                if (rank < group.size()) {
                    RetrievalHit hit = group.get(rank);
                    merged.putIfAbsent(hit.chunkId(), hit);
                    if (merged.size() == limit) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(new ArrayList<>(merged.values()));
    }

    public record RetrievalResult(
            UUID knowledgeBaseId,
            UUID snapshotId,
            String retrievalProfile,
            String embeddingProfile,
            List<RetrievalHit> hits) {}
}
