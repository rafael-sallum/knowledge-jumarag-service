package com.jumatech.jumarag.context;

import com.jumatech.jumarag.retrieval.RetrievalHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record PreparedContext(
        ContextStrategyType configuredStrategy,
        ContextStrategyType usedStrategy,
        ContextDecisionReason decisionReason,
        UUID snapshotId,
        String retrievalProfile,
        String embeddingProfile,
        List<RetrievalHit> evidence,
        Set<UUID> relevanceAnchorChunkIds,
        int characterCount,
        int estimatedTokens,
        boolean completeSnapshot) {

    public PreparedContext {
        evidence = List.copyOf(evidence);
        relevanceAnchorChunkIds = Set.copyOf(relevanceAnchorChunkIds);
    }
}
