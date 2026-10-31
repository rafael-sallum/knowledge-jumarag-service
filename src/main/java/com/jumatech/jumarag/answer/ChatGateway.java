package com.jumatech.jumarag.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.jumatech.jumarag.evidence.EvidenceIntent;
import java.util.List;
import java.util.Set;

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
            EvidenceIntent evidenceIntent,
            Set<String> requiredEvidenceClasses,
            List<Evidence> evidence) {

        public GenerationRequest(
                String systemInstruction,
                String question,
                String language,
                String contextSchema,
                JsonNode context,
                AnswerStatus requiredStatus,
                List<Evidence> evidence) {
            this(systemInstruction, question, language, contextSchema, context, requiredStatus,
                    EvidenceIntent.GENERAL, Set.of(), evidence);
        }
    }

    record Evidence(String citationId, String text, String sourceClassification, String headingPath) {}

    record GenerationResult(String answer, List<String> citationIds, Usage usage) {
        public GenerationResult(String answer, List<String> citationIds) {
            this(answer, citationIds, Usage.none());
        }
    }

    record Usage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        public static Usage none() {
            return new Usage(null, null, null);
        }
    }
}
