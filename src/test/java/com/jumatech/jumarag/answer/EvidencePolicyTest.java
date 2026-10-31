package com.jumatech.jumarag.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.context.ContextDecisionReason;
import com.jumatech.jumarag.context.ContextStrategyType;
import com.jumatech.jumarag.context.PreparedContext;
import com.jumatech.jumarag.evidence.EvidenceClassificationPolicy;
import com.jumatech.jumarag.evidence.EvidenceIntent;
import com.jumatech.jumarag.retrieval.RetrievalHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidencePolicyTest {
    private final EvidencePolicy policy = new EvidencePolicy(
            new AnswerProperties("fake", 8, 0.05, 1, 4000, 8192, 8, 6, 12000),
            new EvidenceClassificationPolicy());

    @Test
    void comparativeIntentRequiresBothRelevantClassificationGroups() {
        RetrievalHit official = hit("OFFICIAL_RULE", "official refusal option", 0.9);
        RetrievalHit implementation = hit("IMPLEMENTATION_NOTE", "implementation refusal option", 0.8);

        EvidencePolicy.Assessment complete = policy.assess(
                "refusal option", context(List.of(official, implementation)),
                EvidenceIntent.OFFICIAL_AND_IMPLEMENTATION);
        EvidencePolicy.Assessment incomplete = policy.assess(
                "refusal option", context(List.of(implementation)),
                EvidenceIntent.OFFICIAL_AND_IMPLEMENTATION);

        assertThat(complete.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(complete.requiredEvidenceClasses())
                .containsExactlyInAnyOrder("OFFICIAL_RULE", "IMPLEMENTATION_NOTE");
        assertThat(complete.evidenceDiversitySatisfied()).isTrue();
        assertThat(incomplete.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(incomplete.presentEvidenceClasses()).containsExactly("IMPLEMENTATION_NOTE");
        assertThat(incomplete.evidenceDiversitySatisfied()).isFalse();
    }

    @Test
    void explicitSingleClassIntentsRemainDeterministic() {
        EvidencePolicy.Assessment official = policy.assess(
                "official rule", context(List.of(hit("OFFICIAL_RULE", "official rule", 0.9))),
                EvidenceIntent.OFFICIAL_ONLY);
        EvidencePolicy.Assessment implementation = policy.assess(
                "implementation rule",
                context(List.of(hit("IMPLEMENTATION_NOTE", "implementation rule", 0.9))),
                EvidenceIntent.IMPLEMENTATION_ONLY);

        assertThat(official.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(official.presentEvidenceClasses()).containsExactly("OFFICIAL_RULE");
        assertThat(implementation.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(implementation.presentEvidenceClasses()).containsExactly("IMPLEMENTATION_NOTE");
    }

    private static PreparedContext context(List<RetrievalHit> evidence) {
        return new PreparedContext(
                ContextStrategyType.RAG, ContextStrategyType.RAG, ContextDecisionReason.CONFIGURED_RAG,
                UUID.randomUUID(), "EXACT_COSINE", "phase0-fake-1536", evidence,
                evidence.stream().map(RetrievalHit::chunkId).collect(java.util.stream.Collectors.toSet()),
                evidence.stream().mapToInt(hit -> hit.text().length()).sum(), 20, false);
    }

    private static RetrievalHit hit(String classification, String text, double similarity) {
        var metadata = new ObjectMapper().createObjectNode();
        return new RetrievalHit(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), classification,
                classification, classification, null, 0, text, "en", null,
                null, null, metadata, metadata, metadata, similarity);
    }
}
