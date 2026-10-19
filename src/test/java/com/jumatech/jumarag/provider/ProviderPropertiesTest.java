package com.jumatech.jumarag.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProviderPropertiesTest {
    @Test
    void fakeConfigurationNeedsNoKeyAndOpenAiKeyDiagnosticsAreRedacted() {
        var unset = new ProviderProperties.OpenAi(null, null, null, 0, null, null, 0);
        assertThatThrownBy(unset::requireApiKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");

        String secret = "test-secret-that-must-never-be-rendered";
        var configured = new ProviderProperties.OpenAi(
                secret, null, null, 1536, null, Duration.ofSeconds(5), 128);
        assertThat(configured.requireApiKey()).isEqualTo(secret);
        assertThat(configured.toString()).contains("<redacted>").doesNotContain(secret);
    }

    @Test
    void rejectsAnIncompatibleOpenAiEmbeddingDimension() {
        assertThatThrownBy(() -> new ProviderProperties.OpenAi(
                        "unused", null, null, 3072, null, Duration.ofSeconds(5), 128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1536");
    }
}
