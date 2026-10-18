package com.jumatech.jumarag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmbeddingRepositoryTest {

    @Test
    void validatesPhaseZeroVectorDimensionAndFiniteValues() {
        float[] valid = new float[1536];
        valid[0] = 1.0f;
        assertThat(EmbeddingRepository.vectorLiteral(valid)).startsWith("[1.0,").endsWith("]");

        assertThatThrownBy(() -> EmbeddingRepository.vectorLiteral(new float[3]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1536");

        valid[4] = Float.NaN;
        assertThatThrownBy(() -> EmbeddingRepository.vectorLiteral(valid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }
}
