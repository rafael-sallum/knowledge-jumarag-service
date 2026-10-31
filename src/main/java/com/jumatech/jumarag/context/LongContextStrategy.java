package com.jumatech.jumarag.context;

import com.jumatech.jumarag.shared.web.DomainException;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class LongContextStrategy implements ContextStrategy {
    private final ContextRepository repository;
    private final ContextProperties properties;

    public LongContextStrategy(ContextRepository repository, ContextProperties properties) {
        this.repository = repository;
        this.properties = properties;
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
        ContextRepository.SnapshotStats stats = repository.stats(
                request.knowledgeBase().id(), request.snapshot().id(), request.language());
        if (!fits(stats)) {
            throw new DomainException("LONG_CONTEXT_BUDGET_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY,
                    "The active snapshot does not fit the configured long-context budget; use RAG or HYBRID.");
        }
        var chunks = repository.findSnapshotChunks(
                request.knowledgeBase().id(), request.snapshot().id(), request.language());
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
