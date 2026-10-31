package com.jumatech.jumarag.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.jumatech.jumarag.evidence.EvidenceIntent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FakeChatGatewayTest {
    private final FakeChatGateway gateway = new FakeChatGateway();

    @Test
    void isDeterministicAndReferencesOnlyProvidedEvidence() {
        var evidence = List.of(new ChatGateway.Evidence("C1", "Controlled evidence.", "OFFICIAL_RULE", "Rule 1"));
        var request = new ChatGateway.GenerationRequest(
                "Grounding contract", "Question", "en", null, null, AnswerStatus.ANSWERED, evidence);

        assertThat(gateway.generate(request)).isEqualTo(gateway.generate(request));
        assertThat(gateway.generate(request).citationIds()).containsExactly("C1");
        assertThat(gateway.provider()).isEqualTo("FAKE");
    }

    @Test
    void emitsDeterministicInsufficientAndConflictShapes() {
        var insufficient = gateway.generate(new ChatGateway.GenerationRequest(
                "Grounding", "Unknown", "en", null, null,
                AnswerStatus.INSUFFICIENT_EVIDENCE, List.of()));
        assertThat(insufficient.citationIds()).isEmpty();

        var evidence = List.of(
                new ChatGateway.Evidence("C1", "Yes", "OFFICIAL_RULE", null),
                new ChatGateway.Evidence("C2", "No", "IMPLEMENTATION_NOTE", null));
        var conflict = gateway.generate(new ChatGateway.GenerationRequest(
                "Grounding", "Conflict", "en", null, null,
                AnswerStatus.CONFLICTING_SOURCES, evidence));
        assertThat(conflict.citationIds()).containsExactly("C1", "C2");
    }

    @Test
    void comparativeAnswerCitesEveryRequiredClassification() {
        var evidence = List.of(
                new ChatGateway.Evidence("C1", "Official", "OFFICIAL_RULE", null),
                new ChatGateway.Evidence("C2", "Product", "IMPLEMENTATION_NOTE", null));
        var result = gateway.generate(new ChatGateway.GenerationRequest(
                "Grounding", "Compare", "en", null, null, AnswerStatus.ANSWERED,
                EvidenceIntent.OFFICIAL_AND_IMPLEMENTATION,
                Set.of("OFFICIAL_RULE", "IMPLEMENTATION_NOTE"), evidence));

        assertThat(result.citationIds()).containsExactlyInAnyOrder("C1", "C2");
    }
}
