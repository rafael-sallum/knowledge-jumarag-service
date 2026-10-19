package com.jumatech.jumarag.evaluation;

import com.jumatech.jumarag.answer.AnswerService;
import com.jumatech.jumarag.answer.AnswerStatus;
import com.jumatech.jumarag.retrieval.RetrievalService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class GoldenEvaluationHarness {
    public Evaluation evaluate(
            GoldenCase golden,
            RetrievalService.RetrievalResult retrieval,
            AnswerService.AnswerResponse answer) {
        boolean hitAtK = golden.expectedLogicalSource() == null
                || retrieval.hits().stream().anyMatch(hit ->
                        hit.logicalSourceName().equals(golden.expectedLogicalSource())
                                && (golden.expectedHeading() == null
                                        || golden.expectedHeading().equals(hit.headingPath())));
        boolean expectedStatus = answer.status() == golden.expectedStatus();
        boolean validCitationCoverage = answer.status() != AnswerStatus.ANSWERED
                || (!answer.citations().isEmpty()
                        && answer.citations().stream().allMatch(citation -> retrieval.hits().stream()
                                .anyMatch(hit -> hit.chunkId().equals(citation.documentChunkId()))));
        return new Evaluation(
                retrieval.embeddingProfile(), hitAtK ? 1.0 : 0.0, expectedStatus, validCitationCoverage);
    }

    public record GoldenCase(
            String question,
            String expectedLogicalSource,
            String expectedHeading,
            AnswerStatus expectedStatus) {}

    public record Evaluation(
            String embeddingProfile, double hitAtK, boolean expectedStatus, boolean validCitationCoverage) {
        public boolean passed() {
            return hitAtK == 1.0 && expectedStatus && validCitationCoverage;
        }
    }

    public Summary summarize(List<Evaluation> evaluations) {
        double hitRate = evaluations.stream().mapToDouble(Evaluation::hitAtK).average().orElse(0);
        long statuses = evaluations.stream().filter(Evaluation::expectedStatus).count();
        long citationCoverage = evaluations.stream().filter(Evaluation::validCitationCoverage).count();
        Set<String> profiles = evaluations.stream()
                .map(Evaluation::embeddingProfile)
                .collect(Collectors.toUnmodifiableSet());
        return new Summary(profiles, hitRate, statuses, citationCoverage, evaluations.size());
    }

    public record Summary(
            Set<String> embeddingProfiles,
            double hitAtK,
            long expectedStatuses,
            long validCitationCoverage,
            int cases) {}
}
