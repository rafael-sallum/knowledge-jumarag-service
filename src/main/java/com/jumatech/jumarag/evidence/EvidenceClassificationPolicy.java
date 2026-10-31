package com.jumatech.jumarag.evidence;

import com.jumatech.jumarag.retrieval.RetrievalHit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class EvidenceClassificationPolicy {
    public static final String OFFICIAL_RULE = "OFFICIAL_RULE";
    public static final String IMPLEMENTATION_NOTE = "IMPLEMENTATION_NOTE";

    public Set<String> eligibleClassifications(EvidenceIntent intent) {
        return switch (EvidenceIntent.defaulted(intent)) {
            case GENERAL -> Set.of();
            case OFFICIAL_ONLY -> Set.of(OFFICIAL_RULE);
            case IMPLEMENTATION_ONLY -> Set.of(IMPLEMENTATION_NOTE);
            case OFFICIAL_AND_IMPLEMENTATION -> ordered(OFFICIAL_RULE, IMPLEMENTATION_NOTE);
        };
    }

    public Set<String> requiredClassifications(EvidenceIntent intent) {
        return switch (EvidenceIntent.defaulted(intent)) {
            case GENERAL -> Set.of();
            case OFFICIAL_ONLY -> Set.of(OFFICIAL_RULE);
            case IMPLEMENTATION_ONLY -> Set.of(IMPLEMENTATION_NOTE);
            case OFFICIAL_AND_IMPLEMENTATION -> ordered(OFFICIAL_RULE, IMPLEMENTATION_NOTE);
        };
    }

    public List<Set<String>> retrievalGroups(EvidenceIntent intent) {
        return switch (EvidenceIntent.defaulted(intent)) {
            case GENERAL -> List.of(Set.of());
            case OFFICIAL_ONLY -> List.of(Set.of(OFFICIAL_RULE));
            case IMPLEMENTATION_ONLY -> List.of(Set.of(IMPLEMENTATION_NOTE));
            case OFFICIAL_AND_IMPLEMENTATION -> List.of(
                    Set.of(OFFICIAL_RULE), Set.of(IMPLEMENTATION_NOTE));
        };
    }

    public Set<String> presentClassifications(List<RetrievalHit> evidence) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        evidence.stream().map(RetrievalHit::sourceClassification).forEach(result::add);
        return java.util.Collections.unmodifiableSet(result);
    }

    public boolean diversitySatisfied(EvidenceIntent intent, List<RetrievalHit> evidence) {
        return presentClassifications(evidence).containsAll(requiredClassifications(intent));
    }

    private static Set<String> ordered(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>(List.of(values));
        return java.util.Collections.unmodifiableSet(result);
    }
}
