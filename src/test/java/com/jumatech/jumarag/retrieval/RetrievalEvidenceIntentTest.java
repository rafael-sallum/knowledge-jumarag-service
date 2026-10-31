package com.jumatech.jumarag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jumatech.jumarag.embedding.EmbeddingGateway;
import com.jumatech.jumarag.embedding.EmbeddingProfileResolver;
import com.jumatech.jumarag.embedding.EmbeddingRepository;
import com.jumatech.jumarag.evidence.EvidenceClassificationPolicy;
import com.jumatech.jumarag.evidence.EvidenceIntent;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.security.ApiClientService;
import com.jumatech.jumarag.snapshot.KnowledgeSnapshot;
import com.jumatech.jumarag.snapshot.SnapshotService;
import com.jumatech.jumarag.source.SourceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetrievalEvidenceIntentTest {
    @Test
    void comparativeRetrievalEmbedsQuestionOnceAndQueriesBothClassificationGroups() {
        RetrievalRepository repository = mock(RetrievalRepository.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        EmbeddingProfileResolver profiles = mock(EmbeddingProfileResolver.class);
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        KnowledgeSnapshot snapshot = mock(KnowledgeSnapshot.class);
        when(snapshot.id()).thenReturn(snapshotId);
        when(snapshot.knowledgeBaseId()).thenReturn(knowledgeBaseId);
        when(embeddings.embed("comparison question")).thenReturn(new float[1536]);
        when(embeddings.provider()).thenReturn("FAKE");
        when(profiles.requireCompatible("phase0-fake-1536")).thenReturn(
                new EmbeddingRepository.EmbeddingProfile(
                        UUID.randomUUID(), "phase0-fake-1536", "FAKE", "deterministic-token-hash-v1",
                        1536, "COSINE"));
        when(repository.search(eq(knowledgeBaseId), eq(snapshotId), any(), any(), any(), any(), anySet(), anyInt()))
                .thenReturn(List.of());
        RetrievalService service = new RetrievalService(
                repository, embeddings, knowledgeBases, mock(SourceService.class), mock(ApiClientService.class),
                mock(SnapshotService.class), profiles, new RetrievalProperties("phase0-fake-1536"),
                new SimpleMeterRegistry(), new EvidenceClassificationPolicy());

        RetrievalService.RetrievalResult result = service.searchWithinSnapshot(
                knowledgeBaseId, snapshot, "comparison question", "en", null,
                EvidenceIntent.OFFICIAL_AND_IMPLEMENTATION, 6);

        assertThat(result.hits()).isEmpty();
        verify(embeddings, times(1)).embed("comparison question");
        verify(repository).search(eq(knowledgeBaseId), eq(snapshotId), any(), any(), eq("en"), eq(null),
                eq(Set.of("OFFICIAL_RULE")), eq(6));
        verify(repository).search(eq(knowledgeBaseId), eq(snapshotId), any(), any(), eq("en"), eq(null),
                eq(Set.of("IMPLEMENTATION_NOTE")), eq(6));
    }
}
