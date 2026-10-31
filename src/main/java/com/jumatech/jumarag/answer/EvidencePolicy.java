package com.jumatech.jumarag.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.jumatech.jumarag.context.PreparedContext;
import com.jumatech.jumarag.evidence.EvidenceClassificationPolicy;
import com.jumatech.jumarag.evidence.EvidenceIntent;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class EvidencePolicy {
    private final AnswerProperties properties;
    private final EvidenceClassificationPolicy classifications;

    EvidencePolicy(AnswerProperties properties) {
        this(properties, new EvidenceClassificationPolicy());
    }

    @Autowired
    public EvidencePolicy(AnswerProperties properties, EvidenceClassificationPolicy classifications) {
        this.properties = properties;
        this.classifications = classifications;
    }

    public Assessment assess(String question, PreparedContext context) {
        return assess(question, context, EvidenceIntent.GENERAL);
    }

    public Assessment assess(String question, PreparedContext context, EvidenceIntent intent) {
        return switch (context.usedStrategy()) {
            case RAG -> assessRag(question, context.evidence(), intent);
            case LONG_CONTEXT -> assessLongContext(question, context.evidence(), intent);
            case HYBRID -> assessHybrid(question, context, intent);
            case AUTO -> throw new IllegalStateException("AUTO must resolve to a concrete context strategy.");
        };
    }

    private Assessment assessRag(String question, List<RetrievalHit> candidates, EvidenceIntent intent) {
        Set<String> queryTokens = tokens(question);
        List<RetrievalHit> usable = candidates.stream()
                .filter(hit -> hit.similarity() >= properties.minimumSimilarity())
                .filter(hit -> overlap(queryTokens, tokens(hit.text())) >= properties.minimumTokenOverlap())
                .toList();
        if (usable.isEmpty()) {
            return assessed(AnswerStatus.INSUFFICIENT_EVIDENCE, List.of(), intent);
        }
        if (!classifications.diversitySatisfied(intent, usable)) {
            return assessed(AnswerStatus.INSUFFICIENT_EVIDENCE, usable, intent);
        }
        List<RetrievalHit> conflicts = conflicts(usable);
        if (!conflicts.isEmpty()) {
            return assessed(AnswerStatus.CONFLICTING_SOURCES, conflicts, intent);
        }
        return assessed(AnswerStatus.ANSWERED, usable, intent);
    }

    private Assessment assessLongContext(String question, List<RetrievalHit> evidence, EvidenceIntent intent) {
        if (evidence.isEmpty()) {
            return assessed(AnswerStatus.INSUFFICIENT_EVIDENCE, List.of(), intent);
        }
        Set<String> queryTokens = tokens(question);
        List<RetrievalHit> relevant = evidence.stream()
                .filter(hit -> overlap(queryTokens, tokens(hit.text())) >= properties.minimumTokenOverlap())
                .toList();
        if (relevant.isEmpty()) {
            return assessed(AnswerStatus.INSUFFICIENT_EVIDENCE, List.of(), intent);
        }
        if (!classifications.diversitySatisfied(intent, relevant)) {
            return assessed(AnswerStatus.INSUFFICIENT_EVIDENCE, relevant, intent);
        }
        List<RetrievalHit> conflicts = conflicts(relevant);
        if (!conflicts.isEmpty()) {
            return assessed(AnswerStatus.CONFLICTING_SOURCES, conflicts, intent);
        }
        return assessed(AnswerStatus.ANSWERED, evidence, intent);
    }

    private Assessment assessHybrid(String question, PreparedContext context, EvidenceIntent intent) {
        List<RetrievalHit> anchors = context.evidence().stream()
                .filter(hit -> context.relevanceAnchorChunkIds().contains(hit.chunkId()))
                .toList();
        Assessment anchorAssessment = assessRag(question, anchors, intent);
        if (anchorAssessment.status() == AnswerStatus.INSUFFICIENT_EVIDENCE) {
            return anchorAssessment;
        }
        List<RetrievalHit> conflicts = conflicts(context.evidence());
        if (!conflicts.isEmpty()) {
            return assessed(AnswerStatus.CONFLICTING_SOURCES, conflicts, intent);
        }
        return assessed(AnswerStatus.ANSWERED, context.evidence(), intent);
    }

    private Assessment assessed(AnswerStatus status, List<RetrievalHit> evidence, EvidenceIntent intent) {
        Set<String> required = classifications.requiredClassifications(intent);
        Set<String> present = classifications.presentClassifications(evidence);
        return new Assessment(status, evidence, required, present, present.containsAll(required));
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

    public record Assessment(
            AnswerStatus status,
            List<RetrievalHit> evidence,
            Set<String> requiredEvidenceClasses,
            Set<String> presentEvidenceClasses,
            boolean evidenceDiversitySatisfied) {

        public Assessment {
            evidence = List.copyOf(evidence);
            requiredEvidenceClasses = Set.copyOf(requiredEvidenceClasses);
            presentEvidenceClasses = Set.copyOf(presentEvidenceClasses);
        }

        public Assessment(AnswerStatus status, List<RetrievalHit> evidence) {
            this(status, evidence, Set.of(),
                    evidence.stream().map(RetrievalHit::sourceClassification)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    true);
        }
    }
}
