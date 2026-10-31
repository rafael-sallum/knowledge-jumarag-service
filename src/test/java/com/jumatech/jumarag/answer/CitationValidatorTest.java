package com.jumatech.jumarag.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CitationValidatorTest {
    private final CitationValidator validator = new CitationValidator();

    @Test
    void acceptsKnownDeduplicatedCitations() {
        assertThat(validator.validate(List.of("C1", "C1", "C2"), Set.of("C1", "C2"), AnswerStatus.ANSWERED))
                .containsExactly("C1", "C2");
    }

    @Test
    void rejectsHallucinatedOrMissingAnsweredCitations() {
        assertThatThrownBy(() -> validator.validate(List.of("C99"), Set.of("C1"), AnswerStatus.ANSWERED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown citation");
        assertThatThrownBy(() -> validator.validate(List.of(), Set.of("C1"), AnswerStatus.ANSWERED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid citation");
    }

    @Test
    void comparativeAnswerMustCiteEveryRequiredClassification() {
        Map<String, String> allowed = Map.of(
                "C1", "OFFICIAL_RULE",
                "C2", "IMPLEMENTATION_NOTE");

        assertThat(validator.validate(
                List.of("C1", "C2"), allowed, AnswerStatus.ANSWERED,
                Set.of("OFFICIAL_RULE", "IMPLEMENTATION_NOTE")))
                .containsExactly("C1", "C2");
        assertThatThrownBy(() -> validator.validate(
                List.of("C2"), allowed, AnswerStatus.ANSWERED,
                Set.of("OFFICIAL_RULE", "IMPLEMENTATION_NOTE")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required evidence classification");
    }
}
