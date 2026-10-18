package com.jumatech.jumarag.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
}
