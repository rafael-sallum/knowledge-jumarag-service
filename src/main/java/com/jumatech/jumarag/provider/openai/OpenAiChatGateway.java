package com.jumatech.jumarag.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.answer.ChatGateway;
import com.jumatech.jumarag.provider.ProviderCallExecutor;
import com.jumatech.jumarag.provider.ProviderException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

public final class OpenAiChatGateway implements ChatGateway {
    static final String OUTPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "answer":{"type":"string"},
                "citationIds":{"type":"array","items":{"type":"string"}}
              },
              "required":["answer","citationIds"],
              "additionalProperties":false
            }
            """;
    private final ChatModel chatModel;
    private final ProviderCallExecutor calls;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final int maxOutputTokens;

    public OpenAiChatGateway(
            ChatModel chatModel,
            ProviderCallExecutor calls,
            ObjectMapper objectMapper,
            String modelName,
            int maxOutputTokens) {
        this.chatModel = chatModel;
        this.calls = calls;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        if (request.evidence() == null || request.evidence().isEmpty()) {
            throw new IllegalArgumentException("Grounded OpenAI generation requires evidence.");
        }
        Prompt prompt = prompt(request);
        return calls.execute(provider(), model(), ProviderCallExecutor.Operation.CHAT, () -> map(chatModel.call(prompt)));
    }

    Prompt prompt(GenerationRequest request) {
        ResponseFormat format = ResponseFormat.builder()
                .type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(ResponseFormat.JsonSchema.builder()
                        .name("grounded_answer")
                        .schema(OUTPUT_SCHEMA)
                        .strict(true)
                        .build())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(0.0)
                .maxTokens(maxOutputTokens)
                .N(1)
                .store(false)
                .responseFormat(format)
                .build();
        String system = request.systemInstruction() + "\nReturn only the required JSON object."
                + " Every citationIds entry must be one of the evidence IDs supplied by the user payload.";
        return new Prompt(List.of(new SystemMessage(system), new UserMessage(userPayload(request))), options);
    }

    private String userPayload(GenerationRequest request) {
        List<Map<String, Object>> evidence = request.evidence().stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("citationId", item.citationId());
            value.put("sourceClassification", item.sourceClassification());
            value.put("heading", item.headingPath());
            value.put("text", item.text());
            return value;
        }).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", request.question());
        payload.put("language", request.language());
        payload.put("contextSchema", request.contextSchema());
        payload.put("context", request.context());
        payload.put("evidence", evidence);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ProviderException(ProviderException.Reason.FAILURE, exception);
        }
    }

    private GenerationResult map(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ProviderException(ProviderException.Reason.MALFORMED_RESPONSE);
        }
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new ProviderException(ProviderException.Reason.MALFORMED_RESPONSE);
        }
        StructuredAnswer structured;
        try {
            structured = objectMapper.readValue(content, StructuredAnswer.class);
        } catch (JsonProcessingException exception) {
            throw new ProviderException(ProviderException.Reason.MALFORMED_RESPONSE, exception);
        }
        if (structured.answer() == null || structured.answer().isBlank() || structured.citationIds() == null) {
            throw new ProviderException(ProviderException.Reason.MALFORMED_RESPONSE);
        }
        var metadata = response.getMetadata();
        var usage = metadata == null ? null : metadata.getUsage();
        Usage mappedUsage = usage == null
                ? Usage.none()
                : new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        return new GenerationResult(structured.answer().strip(), List.copyOf(structured.citationIds()), mappedUsage);
    }

    @Override
    public String provider() {
        return "OPENAI";
    }

    @Override
    public String model() {
        return modelName;
    }

    private record StructuredAnswer(String answer, List<String> citationIds) {}
}
