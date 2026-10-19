package com.jumatech.jumarag.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jumatech.jumarag.provider.ProviderCallExecutor;
import com.jumatech.jumarag.provider.ProviderException;
import com.jumatech.jumarag.provider.ProviderProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class OpenAiEmbeddingGatewayTest {
    @Test
    void mapsSpringAiEmbeddingAndEnforcesConfiguredDimension() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        float[] vector = new float[1536];
        vector[0] = 1.0f;
        when(model.embed("free ball rule")).thenReturn(vector);
        var gateway = new OpenAiEmbeddingGateway(model, calls(), "text-embedding-3-small", 1536);

        assertThat(gateway.embed("free ball rule")).isSameAs(vector);
        assertThat(gateway.provider()).isEqualTo("OPENAI");
        assertThat(gateway.model()).isEqualTo("text-embedding-3-small");
        assertThat(gateway.dimensions()).isEqualTo(1536);

        when(model.embed("bad vector")).thenReturn(new float[3]);
        assertThatThrownBy(() -> gateway.embed("bad vector"))
                .isInstanceOfSatisfying(ProviderException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(ProviderException.Reason.INCOMPATIBLE_VECTOR));
    }

    private static ProviderCallExecutor calls() {
        return new ProviderCallExecutor(
                new ProviderProperties(null, new ProviderProperties.Resilience(3, Duration.ofSeconds(30))),
                new SimpleMeterRegistry());
    }
}
