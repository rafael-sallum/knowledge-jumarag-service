package com.jumatech.jumarag.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.knowledgebase.KnowledgeBase;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.provider.ProviderException;
import com.jumatech.jumarag.retrieval.RetrievalHit;
import com.jumatech.jumarag.retrieval.RetrievalService;
import com.jumatech.jumarag.security.ApiClientService;
import com.jumatech.jumarag.shared.web.DomainException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerProviderPolicyTest {
    @Test
    void insufficientAndConflictingEvidenceNeverInvokeChatProvider() {
        Fixture fixture = fixture();
        when(fixture.evidencePolicy.assess(any(), any()))
                .thenReturn(new EvidencePolicy.Assessment(AnswerStatus.INSUFFICIENT_EVIDENCE, List.of()))
                .thenReturn(new EvidencePolicy.Assessment(
                        AnswerStatus.CONFLICTING_SOURCES, List.of(hit("A"), hit("B"))));

        AnswerService.AnswerResponse insufficient = fixture.service.ask(
                fixture.clientId, "rules", new AnswerService.AnswerRequest("unknown topic", "en", null, null, null));
        AnswerService.AnswerResponse conflict = fixture.service.ask(
                fixture.clientId, "rules", new AnswerService.AnswerRequest("conflicting rule", "en", null, null, null));

        assertThat(insufficient.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(insufficient.providerUsage()).isEqualTo(ChatGateway.Usage.none());
        assertThat(conflict.status()).isEqualTo(AnswerStatus.CONFLICTING_SOURCES);
        assertThat(conflict.citations()).hasSize(2);
        verify(fixture.chat, never()).generate(any());
    }

    @Test
    void providerFailureBecomesSafeServiceUnavailableError() {
        Fixture fixture = fixture();
        RetrievalHit hit = hit("A");
        when(fixture.evidencePolicy.assess(any(), any()))
                .thenReturn(new EvidencePolicy.Assessment(AnswerStatus.ANSWERED, List.of(hit)));
        when(fixture.chat.generate(any()))
                .thenThrow(new ProviderException(ProviderException.Reason.RATE_LIMITED));

        assertThatThrownBy(() -> fixture.service.ask(
                        fixture.clientId, "rules",
                        new AnswerService.AnswerRequest("answerable rule", "en", null, null, null)))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.getMessage()).doesNotContain("RATE_LIMITED");
                });
    }

    private static Fixture fixture() {
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        KnowledgeBase base = new KnowledgeBase(
                knowledgeBaseId, "rules", "Rules", "Test", "en", KnowledgeBase.Status.ACTIVE,
                OffsetDateTime.now(), OffsetDateTime.now());
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        ApiClientService clients = mock(ApiClientService.class);
        RetrievalService retrieval = mock(RetrievalService.class);
        EvidencePolicy evidence = mock(EvidencePolicy.class);
        ChatGateway chat = mock(ChatGateway.class);
        when(knowledgeBases.requireBySlug("rules")).thenReturn(base);
        when(retrieval.search(any(), any(), any(), any(), any(Integer.class), any()))
                .thenReturn(new RetrievalService.RetrievalResult(
                        knowledgeBaseId, snapshotId, "EXACT_COSINE", "phase0-fake-1536", List.of(hit("R"))));
        when(chat.provider()).thenReturn("OPENAI");
        when(chat.model()).thenReturn("gpt-4.1-mini");
        AnswerProperties properties = new AnswerProperties(
                "openai", 8, 0.05, 1, 4000, 8192, 8, 6, 12000);
        AnswerService service = new AnswerService(
                knowledgeBases, clients, retrieval, evidence, chat, new CitationValidator(), properties,
                new ObjectMapper(), new SimpleMeterRegistry());
        return new Fixture(service, evidence, chat, clientId);
    }

    private static RetrievalHit hit(String marker) {
        ObjectMapper mapper = new ObjectMapper();
        return new RetrievalHit(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Source " + marker,
                "OFFICIAL_RULE", "Rule", null, 0, "Controlled evidence " + marker, "en", null,
                null, null, mapper.createObjectNode(), mapper.createObjectNode(), mapper.createObjectNode(), 0.8);
    }

    private record Fixture(
            AnswerService service,
            EvidencePolicy evidencePolicy,
            ChatGateway chat,
            UUID clientId) {}
}
