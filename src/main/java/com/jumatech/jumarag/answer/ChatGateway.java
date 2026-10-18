package com.jumatech.jumarag.answer;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface ChatGateway {
    GenerationResult generate(GenerationRequest request);

    String provider();

    String model();

    record GenerationRequest(
            String systemInstruction,
            String question,
            String language,
            String contextSchema,
            JsonNode context,
            AnswerStatus requiredStatus,
            List<Evidence> evidence) {}

    record Evidence(String citationId, String text, String sourceClassification, String headingPath) {}

    record GenerationResult(String answer, List<String> citationIds) {}
}
