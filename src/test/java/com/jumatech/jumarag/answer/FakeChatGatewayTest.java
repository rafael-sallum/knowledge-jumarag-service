package com.jumatech.jumarag.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
}
