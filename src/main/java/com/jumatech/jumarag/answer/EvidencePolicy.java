package com.jumatech.jumarag.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.jumatech.jumarag.retrieval.RetrievalHit;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class EvidencePolicy {
    private final AnswerProperties properties;

    public EvidencePolicy(AnswerProperties properties) {
        this.properties = properties;
    }

    public Assessment assess(String question, List<RetrievalHit> candidates) {
        Set<String> queryTokens = tokens(question);
        List<RetrievalHit> usable = candidates.stream()
                .filter(hit -> hit.similarity() >= properties.minimumSimilarity())
                .filter(hit -> overlap(queryTokens, tokens(hit.text())) >= properties.minimumTokenOverlap())
                .toList();
        if (usable.isEmpty()) {
            return new Assessment(AnswerStatus.INSUFFICIENT_EVIDENCE, List.of());
        }
        List<RetrievalHit> conflicts = conflicts(usable);
        if (!conflicts.isEmpty()) {
            return new Assessment(AnswerStatus.CONFLICTING_SOURCES, conflicts);
        }
        return new Assessment(AnswerStatus.ANSWERED, usable);
    }

    private static List<RetrievalHit> conflicts(List<RetrievalHit> evidence) {
        Map<String, Set<String>> values = new HashMap<>();
        for (RetrievalHit hit : evidence) {
            String key = metadata(hit, "assertionKey");
            String value = metadata(hit, "assertionValue");
            if (key != null && value != null) {
                values.computeIfAbsent(key, ignored -> new HashSet<>()).add(value);
            }
        }
        Set<String> conflictingKeys = new HashSet<>();
        values.forEach((key, distinctValues) -> {
            if (distinctValues.size() > 1) {
                conflictingKeys.add(key);
            }
        });
        if (conflictingKeys.isEmpty()) {
            return List.of();
        }
        List<RetrievalHit> result = new ArrayList<>();
        for (RetrievalHit hit : evidence) {
            String key = metadata(hit, "assertionKey");
            if (key != null && conflictingKeys.contains(key)) {
                result.add(hit);
            }
        }
        return List.copyOf(result);
    }

    private static String metadata(RetrievalHit hit, String name) {
        for (JsonNode metadata : List.of(hit.sourceVersionMetadata(), hit.sourceMetadata(), hit.metadata())) {
            JsonNode value = metadata == null ? null : metadata.get(name);
            if (value != null && value.isTextual() && !value.textValue().isBlank()) {
                return value.textValue();
            }
        }
        return null;
    }

    private static int overlap(Set<String> left, Set<String> right) {
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return intersection.size();
    }

    private static Set<String> tokens(String value) {
        String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        Set<String> tokens = new LinkedHashSet<>();
        Arrays.stream(normalized.split("[^a-z0-9]+"))
                .filter(token -> token.length() >= 3)
                .forEach(tokens::add);
        return tokens;
    }

    public record Assessment(AnswerStatus status, List<RetrievalHit> evidence) {}
}
