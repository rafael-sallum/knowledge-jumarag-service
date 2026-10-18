package com.jumatech.jumarag.answer;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.answer.chat-provider", havingValue = "fake", matchIfMissing = true)
public final class FakeChatGateway implements ChatGateway {
    @Override
    public GenerationResult generate(GenerationRequest request) {
        return switch (request.requiredStatus()) {
            case INSUFFICIENT_EVIDENCE -> new GenerationResult(
                    "The active controlled sources do not provide enough evidence to answer this question.",
                    List.of());
            case CONFLICTING_SOURCES -> new GenerationResult(
                    "The controlled sources contain conflicting assertions; review the cited evidence.",
                    request.evidence().stream().map(Evidence::citationId).toList());
            case ANSWERED -> {
                Evidence evidence = request.evidence().getFirst();
                yield new GenerationResult("Grounded answer: " + evidence.text(), List.of(evidence.citationId()));
            }
        };
    }

    @Override
    public String provider() {
        return "FAKE";
    }

    @Override
    public String model() {
        return "deterministic-grounded-v1";
    }
}
