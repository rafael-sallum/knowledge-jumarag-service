package com.jumatech.jumarag.answer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class CitationValidator {
    public List<String> validate(List<String> generated, Set<String> allowed, AnswerStatus status) {
        List<String> safe = generated == null
                ? List.of()
                : new LinkedHashSet<>(generated).stream().filter(allowed::contains).toList();
        if (generated != null && safe.size() != new LinkedHashSet<>(generated).size()) {
            throw new IllegalStateException("Chat gateway returned an unknown citation identifier.");
        }
        if (status == AnswerStatus.ANSWERED && safe.isEmpty()) {
            throw new IllegalStateException("An answered response must contain a valid citation.");
        }
        return safe;
    }
}
