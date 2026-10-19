package com.jumatech.jumarag.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.answer.AnswerStatus;
import com.jumatech.jumarag.answer.ChatGateway;
import com.jumatech.jumarag.provider.ProviderCallExecutor;
import com.jumatech.jumarag.provider.ProviderException;
import com.jumatech.jumarag.provider.ProviderProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class OpenAiChatGatewayTest {
    @Test
    void mapsGroundedStructuredRequestResponseAndUsage() {
        ChatModel model = mock(ChatModel.class);
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(41, 12, 53)).build();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"answer\":\"A grounded rule.\",\"citationIds\":[\"C1\"]}"))),
                metadata));
        var gateway = gateway(model);

        ChatGateway.GenerationRequest request = new ChatGateway.GenerationRequest(
                "Use evidence only.", "What is the rule?", "en", null, null,
                AnswerStatus.ANSWERED,
                List.of(new ChatGateway.Evidence("C1", "Controlled evidence.", "OFFICIAL_RULE", "Rule 1")));
        ChatGateway.GenerationResult result = gateway.generate(request);

        assertThat(result.answer()).isEqualTo("A grounded rule.");
        assertThat(result.citationIds()).containsExactly("C1");
        assertThat(result.usage()).isEqualTo(new ChatGateway.Usage(41, 12, 53));

        Prompt prompt = gateway.prompt(request);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(options.getMaxTokens()).isEqualTo(128);
        assertThat(options.getResponseFormat()).isNotNull();
        assertThat(prompt.getSystemMessage().getText()).contains("Use evidence only", "required JSON");
        assertThat(prompt.getUserMessage().getText())
                .contains("\"citationId\":\"C1\"")
                .contains("\"question\":\"What is the rule?\"");
    }

    @Test
    void rejectsMalformedProviderOutputWithSafeError() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("not-json")))));

        assertThatThrownBy(() -> gateway(model).generate(new ChatGateway.GenerationRequest(
                        "Use evidence only.", "Question", "en", null, null, AnswerStatus.ANSWERED,
                        List.of(new ChatGateway.Evidence("C1", "Evidence", "OFFICIAL_RULE", null)))))
                .isInstanceOfSatisfying(ProviderException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(ProviderException.Reason.MALFORMED_RESPONSE));
    }

    private static OpenAiChatGateway gateway(ChatModel model) {
        ProviderCallExecutor calls = new ProviderCallExecutor(
                new ProviderProperties(null, new ProviderProperties.Resilience(3, Duration.ofSeconds(30))),
                new SimpleMeterRegistry());
        return new OpenAiChatGateway(model, calls, new ObjectMapper(), "gpt-4.1-mini", 128);
    }
}
