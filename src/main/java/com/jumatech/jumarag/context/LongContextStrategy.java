package com.jumatech.jumarag.context;

import com.jumatech.jumarag.evidence.EvidenceClassificationPolicy;
import com.jumatech.jumarag.shared.web.DomainException;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class LongContextStrategy implements ContextStrategy {
    private final ContextRepository repository;
    private final ContextProperties properties;
    private final EvidenceClassificationPolicy evidenceClassifications;

    LongContextStrategy(ContextRepository repository, ContextProperties properties) {
        this(repository, properties, new EvidenceClassificationPolicy());
    }

    @Autowired
    public LongContextStrategy(ContextRepository repository, ContextProperties properties,
            EvidenceClassificationPolicy evidenceClassifications) {
        this.repository = repository;
        this.properties = properties;
        this.evidenceClassifications = evidenceClassifications;
    }

    @Override
    public ContextStrategyType type() {
        return ContextStrategyType.LONG_CONTEXT;
    }

    @Override
    public PreparedContext prepare(ContextPreparationRequest request) {
        return prepare(request, request.knowledgeBase().contextStrategy(),
                ContextDecisionReason.CONFIGURED_LONG_CONTEXT);
    }

    PreparedContext prepare(ContextPreparationRequest request, ContextStrategyType configured,
            ContextDecisionReason reason) {
        var eligible = evidenceClassifications.eligibleClassifications(request.evidenceIntent());
        ContextRepository.SnapshotStats stats = repository.stats(
                request.knowledgeBase().id(), request.snapshot().id(), request.language(), eligible);
        if (!fits(stats)) {
            throw new DomainException("LONG_CONTEXT_BUDGET_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY,
                    "The active snapshot does not fit the configured long-context budget; use RAG or HYBRID.");
        }
        var chunks = repository.findSnapshotChunks(
                request.knowledgeBase().id(), request.snapshot().id(), request.language(), eligible);
        ContextBudget.Selection selection = ContextBudget.select(chunks, properties.longContextMaxChunks(),
                properties.longContextMaxCharacters(), properties.longContextMaxEstimatedTokens());
        Set<java.util.UUID> anchors = selection.chunks().stream()
                .map(hit -> hit.chunkId()).collect(Collectors.toUnmodifiableSet());
        return new PreparedContext(configured, ContextStrategyType.LONG_CONTEXT, reason, request.snapshot().id(),
                "FULL_SNAPSHOT", null, selection.chunks(), anchors, selection.characters(),
                selection.estimatedTokens(), selection.complete());
    }

    boolean fits(ContextRepository.SnapshotStats stats) {
        return stats.chunkCount() <= properties.longContextMaxChunks()
                && stats.characterCount() <= properties.longContextMaxCharacters()
                && stats.estimatedTokens() <= properties.longContextMaxEstimatedTokens();
    }
}
