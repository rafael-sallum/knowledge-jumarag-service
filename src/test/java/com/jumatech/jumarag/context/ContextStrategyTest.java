package com.jumatech.jumarag.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.answer.AnswerProperties;
import com.jumatech.jumarag.knowledgebase.KnowledgeBase;
import com.jumatech.jumarag.retrieval.RetrievalHit;
import com.jumatech.jumarag.retrieval.RetrievalService;
import com.jumatech.jumarag.snapshot.KnowledgeSnapshot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextStrategyTest {
    private final ContextProperties properties = new ContextProperties(
            6, 12_000, 3_000, 4, 8_000, 2_000, 1, 10, 10_000, 2_500, 0.8);
    private final AnswerProperties answerProperties = new AnswerProperties(
            "fake", 8, 0.05, 1, 4000, 8192, 8, 6, 12000);

    @Test
    void ragEmbedsAndRetrievesExactlyOnce() {
        RetrievalService retrieval = mock(RetrievalService.class);
        ContextPreparationRequest request = request(ContextStrategyType.RAG);
        when(retrieval.searchWithinSnapshot(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(result(List.of(hit(0, 0.9))));

        PreparedContext prepared = new RagContextStrategy(retrieval, properties, answerProperties).prepare(request);

        assertThat(prepared.usedStrategy()).isEqualTo(ContextStrategyType.RAG);
        verify(retrieval).searchWithinSnapshot(any(), any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void longContextNeverTouchesRetrievalAndRequiresWholeSnapshotToFit() {
        ContextRepository repository = mock(ContextRepository.class);
        ContextPreparationRequest request = request(ContextStrategyType.LONG_CONTEXT);
        when(repository.stats(any(), any(), any())).thenReturn(new ContextRepository.SnapshotStats(1, 120));
        when(repository.findSnapshotChunks(any(), any(), any())).thenReturn(List.of(hit(0, 0)));

        PreparedContext prepared = new LongContextStrategy(repository, properties).prepare(request);

        assertThat(prepared.usedStrategy()).isEqualTo(ContextStrategyType.LONG_CONTEXT);
        assertThat(prepared.embeddingProfile()).isNull();
        assertThat(prepared.completeSnapshot()).isTrue();
    }

    @Test
    void autoReusesTheSingleRetrievalForStrongAndExpandedPaths() {
        ContextRepository repository = mock(ContextRepository.class);
        RetrievalService retrieval = mock(RetrievalService.class);
        ContextPreparationRequest request = request(ContextStrategyType.AUTO);
        when(repository.stats(any(), any(), any())).thenReturn(new ContextRepository.SnapshotStats(20, 20_000));
        when(retrieval.searchWithinSnapshot(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(result(List.of(hit(4, 0.95))));
        RagContextStrategy rag = new RagContextStrategy(retrieval, properties, answerProperties);
        LongContextStrategy longContext = new LongContextStrategy(repository, properties);
        HybridContextStrategy hybrid = new HybridContextStrategy(rag, repository, properties);

        PreparedContext prepared = new AutoContextStrategy(
                repository, longContext, rag, hybrid, properties).prepare(request);

        assertThat(prepared.usedStrategy()).isEqualTo(ContextStrategyType.RAG);
        assertThat(prepared.decisionReason()).isEqualTo(ContextDecisionReason.STRONG_RETRIEVAL_SIGNAL);
        verify(retrieval).searchWithinSnapshot(any(), any(), any(), any(), any(), any(Integer.class));
        verify(repository, never()).findNeighbors(any(), any(), any(), any(), any(Integer.class));
    }

    private static ContextPreparationRequest request(ContextStrategyType strategy) {
        UUID knowledgeBaseId = UUID.randomUUID();
        KnowledgeBase base = new KnowledgeBase(
                knowledgeBaseId, "rules", "Rules", "Rules", "en", KnowledgeBase.Status.ACTIVE,
                strategy, OffsetDateTime.now(), OffsetDateTime.now());
        KnowledgeSnapshot snapshot = mock(KnowledgeSnapshot.class);
        when(snapshot.id()).thenReturn(UUID.randomUUID());
        when(snapshot.knowledgeBaseId()).thenReturn(knowledgeBaseId);
        return new ContextPreparationRequest(base, snapshot, "controlled marker", "en");
    }

    private static RetrievalService.RetrievalResult result(List<RetrievalHit> hits) {
        return new RetrievalService.RetrievalResult(
                UUID.randomUUID(), UUID.randomUUID(), "EXACT_COSINE", "phase0-fake-1536", hits);
    }

    private static RetrievalHit hit(int ordinal, double similarity) {
        var json = new ObjectMapper().createObjectNode();
        return new RetrievalHit(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Rules", "OFFICIAL_RULE", "Rules",
                null, ordinal, "controlled marker evidence", "en", "Section", null, null,
                json, json, json, similarity);
    }
}
