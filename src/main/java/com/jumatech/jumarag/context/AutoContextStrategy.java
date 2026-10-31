package com.jumatech.jumarag.context;

import com.jumatech.jumarag.evidence.EvidenceClassificationPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class AutoContextStrategy implements ContextStrategy {
    private final ContextRepository repository;
    private final LongContextStrategy longContext;
    private final RagContextStrategy rag;
    private final HybridContextStrategy hybrid;
    private final ContextProperties properties;
    private final EvidenceClassificationPolicy evidenceClassifications;

    AutoContextStrategy(ContextRepository repository, LongContextStrategy longContext,
            RagContextStrategy rag, HybridContextStrategy hybrid, ContextProperties properties) {
        this(repository, longContext, rag, hybrid, properties, new EvidenceClassificationPolicy());
    }

    @Autowired
    public AutoContextStrategy(ContextRepository repository, LongContextStrategy longContext,
            RagContextStrategy rag, HybridContextStrategy hybrid, ContextProperties properties,
            EvidenceClassificationPolicy evidenceClassifications) {
        this.repository = repository;
        this.longContext = longContext;
        this.rag = rag;
        this.hybrid = hybrid;
        this.properties = properties;
        this.evidenceClassifications = evidenceClassifications;
    }

    @Override
    public ContextStrategyType type() {
        return ContextStrategyType.AUTO;
    }

    @Override
    public PreparedContext prepare(ContextPreparationRequest request) {
        ContextRepository.SnapshotStats stats = repository.stats(
                request.knowledgeBase().id(), request.snapshot().id(), request.language(),
                evidenceClassifications.eligibleClassifications(request.evidenceIntent()));
        if (longContext.fits(stats)) {
            return longContext.prepare(request, ContextStrategyType.AUTO,
                    ContextDecisionReason.SNAPSHOT_FITS_LONG_CONTEXT);
        }
        PreparedContext retrieved = rag.prepare(
                request, ContextStrategyType.AUTO, ContextDecisionReason.STRONG_RETRIEVAL_SIGNAL);
        double strongest = retrieved.evidence().stream().mapToDouble(hit -> hit.similarity()).max().orElse(-1.0);
        if (strongest >= properties.autoStrongSimilarity()) {
            return retrieved;
        }
        return hybrid.expand(request, retrieved, ContextStrategyType.AUTO,
                ContextDecisionReason.RETRIEVAL_NEEDS_CONTEXT_EXPANSION);
    }
}
