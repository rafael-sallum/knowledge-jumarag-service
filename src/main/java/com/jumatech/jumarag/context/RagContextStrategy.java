package com.jumatech.jumarag.context;

import com.jumatech.jumarag.answer.AnswerProperties;
import com.jumatech.jumarag.retrieval.RetrievalService;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class RagContextStrategy implements ContextStrategy {
    private final RetrievalService retrieval;
    private final ContextProperties properties;
    private final AnswerProperties answerProperties;

    public RagContextStrategy(RetrievalService retrieval, ContextProperties properties,
            AnswerProperties answerProperties) {
        this.retrieval = retrieval;
        this.properties = properties;
        this.answerProperties = answerProperties;
    }

    @Override
    public ContextStrategyType type() {
        return ContextStrategyType.RAG;
    }

    @Override
    public PreparedContext prepare(ContextPreparationRequest request) {
        return prepare(request, request.knowledgeBase().contextStrategy(), ContextDecisionReason.CONFIGURED_RAG);
    }

    PreparedContext prepare(ContextPreparationRequest request, ContextStrategyType configured,
            ContextDecisionReason reason) {
        RetrievalService.RetrievalResult result = retrieval.searchWithinSnapshot(
                request.knowledgeBase().id(), request.snapshot(), request.question(), request.language(), null,
                request.evidenceIntent(), answerProperties.retrievalLimit());
        ContextBudget.Selection selection = ContextBudget.select(
                result.hits(), properties.ragMaxChunks(), properties.ragMaxCharacters(),
                properties.ragMaxEstimatedTokens());
        Set<java.util.UUID> anchors = selection.chunks().stream()
                .map(hit -> hit.chunkId()).collect(Collectors.toUnmodifiableSet());
        return new PreparedContext(configured, ContextStrategyType.RAG, reason, request.snapshot().id(),
                result.retrievalProfile(), result.embeddingProfile(), selection.chunks(), anchors,
                selection.characters(), selection.estimatedTokens(), false);
    }
}
