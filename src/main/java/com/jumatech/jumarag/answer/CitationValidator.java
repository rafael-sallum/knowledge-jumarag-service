package com.jumatech.jumarag.answer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class CitationValidator {
    public List<String> validate(List<String> generated, Set<String> allowed, AnswerStatus status) {
        return validate(generated, allowed.stream().collect(java.util.stream.Collectors.toMap(
                id -> id, ignored -> "UNSPECIFIED")), status, Set.of());
    }

    public List<String> validate(
            List<String> generated,
            Map<String, String> allowedClassifications,
            AnswerStatus status,
            Set<String> requiredClassifications) {
        Set<String> allowed = allowedClassifications.keySet();
        List<String> safe = generated == null
                ? List.of()
                : new LinkedHashSet<>(generated).stream().filter(allowed::contains).toList();
        if (generated != null && safe.size() != new LinkedHashSet<>(generated).size()) {
            throw new IllegalStateException("Chat gateway returned an unknown citation identifier.");
        }
        if (status == AnswerStatus.ANSWERED && safe.isEmpty()) {
            throw new IllegalStateException("An answered response must contain a valid citation.");
        }
        if (status == AnswerStatus.ANSWERED && !safe.stream()
                .map(allowedClassifications::get)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(requiredClassifications)) {
            throw new IllegalStateException(
                    "An answered response must cite every required evidence classification.");
        }
        return safe;
    }
}
