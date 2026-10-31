package com.jumatech.jumarag.context;

import com.jumatech.jumarag.retrieval.RetrievalHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class HybridContextStrategy implements ContextStrategy {
    private static final Comparator<RetrievalHit> DOCUMENT_ORDER = Comparator
            .comparing(RetrievalHit::logicalSourceName)
            .thenComparing(RetrievalHit::sourceVersionId)
            .thenComparingInt(RetrievalHit::chunkIndex)
            .thenComparing(RetrievalHit::chunkId);

    private final RagContextStrategy rag;
    private final ContextRepository repository;
    private final ContextProperties properties;

    public HybridContextStrategy(RagContextStrategy rag, ContextRepository repository,
            ContextProperties properties) {
        this.rag = rag;
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public ContextStrategyType type() {
        return ContextStrategyType.HYBRID;
    }

    @Override
    public PreparedContext prepare(ContextPreparationRequest request) {
        PreparedContext anchors = rag.prepare(
                request, request.knowledgeBase().contextStrategy(), ContextDecisionReason.CONFIGURED_HYBRID);
        return expand(request, anchors, request.knowledgeBase().contextStrategy(),
                ContextDecisionReason.CONFIGURED_HYBRID);
    }

    PreparedContext expand(ContextPreparationRequest request, PreparedContext ragContext,
            ContextStrategyType configured, ContextDecisionReason reason) {
        List<RetrievalHit> anchors = ragContext.evidence();
        List<RetrievalHit> neighbors = repository.findNeighbors(
                request.knowledgeBase().id(), request.snapshot().id(), request.language(), anchors,
                properties.hybridNeighborChunks());
        Map<UUID, RetrievalHit> prioritized = new LinkedHashMap<>();
        anchors.forEach(hit -> prioritized.put(hit.chunkId(), hit));
        neighbors.forEach(hit -> prioritized.putIfAbsent(hit.chunkId(), hit));
        ContextBudget.Selection selection = ContextBudget.select(new ArrayList<>(prioritized.values()),
                properties.hybridMaxChunks(), properties.hybridMaxCharacters(),
                properties.hybridMaxEstimatedTokens());
        List<RetrievalHit> ordered = selection.chunks().stream().sorted(DOCUMENT_ORDER).toList();
        Set<UUID> retainedAnchors = anchors.stream().map(RetrievalHit::chunkId)
                .filter(id -> ordered.stream().anyMatch(hit -> hit.chunkId().equals(id)))
                .collect(Collectors.toUnmodifiableSet());
        int characters = ordered.stream().mapToInt(hit -> hit.text().length()).sum();
        return new PreparedContext(configured, ContextStrategyType.HYBRID, reason, request.snapshot().id(),
                ragContext.retrievalProfile(), ragContext.embeddingProfile(), ordered, retainedAnchors,
                characters, ContextBudget.estimatedTokens(characters), false);
    }
}
