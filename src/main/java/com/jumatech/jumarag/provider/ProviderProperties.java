package com.jumatech.jumarag.provider;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.provider")
public record ProviderProperties(OpenAi openai, Resilience resilience) {
    public ProviderProperties {
        openai = openai == null ? new OpenAi(null, null, null, 0, null, null, 0) : openai;
        resilience = resilience == null ? new Resilience(0, null) : resilience;
    }

    public record OpenAi(
            String apiKey,
            String baseUrl,
            String embeddingModel,
            int embeddingDimensions,
            String chatModel,
            Duration timeout,
            int maxOutputTokens) {
        public OpenAi {
            baseUrl = blankToDefault(baseUrl, "https://api.openai.com");
            embeddingModel = blankToDefault(embeddingModel, "text-embedding-3-small");
            embeddingDimensions = embeddingDimensions == 0 ? 1536 : embeddingDimensions;
            chatModel = blankToDefault(chatModel, "gpt-4.1-mini");
            timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
            maxOutputTokens = maxOutputTokens == 0 ? 256 : maxOutputTokens;
            if (embeddingDimensions != 1536) {
                throw new IllegalArgumentException("The Phase 3 OpenAI embedding profile requires 1536 dimensions.");
            }
            if (timeout.isZero() || timeout.isNegative() || maxOutputTokens < 32 || maxOutputTokens > 2048) {
                throw new IllegalArgumentException("OpenAI timeout or output-token guard is invalid.");
            }
        }

        public String requireApiKey() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("OPENAI_API_KEY is required when an OpenAI provider is selected.");
            }
            return apiKey;
        }

        @Override
        public String toString() {
            return "OpenAi[apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<redacted>")
                    + ", baseUrl=" + baseUrl
                    + ", embeddingModel=" + embeddingModel
                    + ", embeddingDimensions=" + embeddingDimensions
                    + ", chatModel=" + chatModel
                    + ", timeout=" + timeout
                    + ", maxOutputTokens=" + maxOutputTokens + ']';
        }
    }

    public record Resilience(int failureThreshold, Duration openDuration) {
        public Resilience {
            failureThreshold = failureThreshold == 0 ? 3 : failureThreshold;
            openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
            if (failureThreshold < 1 || openDuration.isZero() || openDuration.isNegative()) {
                throw new IllegalArgumentException("Provider circuit-breaker configuration is invalid.");
            }
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
