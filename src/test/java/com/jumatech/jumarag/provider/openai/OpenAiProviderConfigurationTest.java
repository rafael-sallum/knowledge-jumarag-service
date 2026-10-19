package com.jumatech.jumarag.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.provider.ProviderCallExecutor;
import com.jumatech.jumarag.provider.ProviderProperties;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenAiProviderConfigurationTest {
    @Test
    void selectedOpenAiProviderFailsFastWithoutApiKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(OpenAiProviderConfiguration.class)
                .withPropertyValues("app.embedding.provider=openai", "app.answer.chat-provider=fake")
                .withBean(ProviderProperties.class,
                        () -> new ProviderProperties(null, null))
                .withBean(ProviderCallExecutor.class, () -> mock(ProviderCallExecutor.class))
                .withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("OPENAI_API_KEY is required");
                });
    }
}
