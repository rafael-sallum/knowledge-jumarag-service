package com.jumatech.jumarag.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.answer.ChatGateway;
import com.jumatech.jumarag.embedding.EmbeddingGateway;
import com.jumatech.jumarag.provider.ProviderCallExecutor;
import com.jumatech.jumarag.provider.ProviderProperties;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

@Configuration
public class OpenAiProviderConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.embedding.provider", havingValue = "openai")
    EmbeddingGateway openAiEmbeddingGateway(
            ProviderProperties properties,
            ProviderCallExecutor calls,
            ObservationRegistry observations) {
        ProviderProperties.OpenAi openAi = properties.openai();
        OpenAiApi api = api(openAi);
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(openAi.embeddingModel())
                .dimensions(openAi.embeddingDimensions())
                .build();
        OpenAiEmbeddingModel model = new OpenAiEmbeddingModel(
                api, MetadataMode.EMBED, options, noRetry(), observations);
        return new OpenAiEmbeddingGateway(
                model, calls, openAi.embeddingModel(), openAi.embeddingDimensions());
    }

    @Bean
    @ConditionalOnProperty(name = "app.answer.chat-provider", havingValue = "openai")
    ChatGateway openAiChatGateway(
            ProviderProperties properties,
            ProviderCallExecutor calls,
            ObjectMapper objectMapper,
            ObservationRegistry observations) {
        ProviderProperties.OpenAi openAi = properties.openai();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(openAi.chatModel())
                .temperature(0.0)
                .maxTokens(openAi.maxOutputTokens())
                .N(1)
                .store(false)
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api(openAi))
                .defaultOptions(options)
                .retryTemplate(noRetry())
                .observationRegistry(observations)
                .build();
        return new OpenAiChatGateway(model, calls, objectMapper, openAi.chatModel(), openAi.maxOutputTokens());
    }

    private static OpenAiApi api(ProviderProperties.OpenAi properties) {
        Duration timeout = properties.timeout();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return OpenAiApi.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.requireApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
    }

    private static RetryTemplate noRetry() {
        return RetryTemplate.builder().maxAttempts(1).build();
    }
}
