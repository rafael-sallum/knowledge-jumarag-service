package com.jumatech.jumarag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FakeEmbeddingGatewayTest {
    private final FakeEmbeddingGateway gateway = new FakeEmbeddingGateway(
            new FakeEmbeddingGateway.EmbeddingProperties("fake", 1536));

    @Test
    void producesStableNormalizedVectorsAtProductionDimension() {
        float[] first = gateway.embed("Bola livre no Snooker");
        float[] second = gateway.embed("Bola livre no Snooker");

        assertThat(first).hasSize(1536).containsExactly(second);
        double norm = 0;
        for (float value : first) {
            norm += value * value;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(0.0001));
    }

    @Test
    void normalizesCaseAndDiacritics() {
        assertThat(gateway.embed("BOLA LIVRE")).containsExactly(gateway.embed("bola livre"));
    }

    @Test
    void rejectsWrongDimensionAndBlankInput() {
        assertThatThrownBy(() -> new FakeEmbeddingGateway(
                        new FakeEmbeddingGateway.EmbeddingProperties("fake", 32)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1536");
        assertThatThrownBy(() -> gateway.embed(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
