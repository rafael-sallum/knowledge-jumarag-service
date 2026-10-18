package com.jumatech.jumarag.retrieval;

import com.jumatech.jumarag.embedding.EmbeddingGateway;
import com.jumatech.jumarag.embedding.EmbeddingRepository;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.source.SourceService;
import com.jumatech.jumarag.shared.web.DomainException;
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
    private final MeterRegistry meterRegistry;

    public RetrievalService(
            RetrievalRepository repository,
            EmbeddingGateway embeddings,
            KnowledgeBaseService knowledgeBases,
            SourceService sources,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.knowledgeBases = knowledgeBases;
        this.sources = sources;
        this.meterRegistry = meterRegistry;
    }

    public List<RetrievalHit> search(
            UUID knowledgeBaseId, String query, String language, UUID sourceId, int limit) {
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
        float[] queryVector = embeddings.embed(query);
        String finalLanguage = normalizedLanguage;
        return meterRegistry.timer("jumarag.retrieval.duration", "provider", embeddings.provider())
                .record(() -> repository.search(
                        knowledgeBaseId,
                        EmbeddingRepository.PHASE_ZERO_PROFILE_ID,
                        queryVector,
                        finalLanguage,
                        sourceId,
                        limit));
    }
}
