package com.jumatech.jumarag.evaluation;

import com.jumatech.jumarag.answer.AnswerService;
import com.jumatech.jumarag.answer.AnswerStatus;
import com.jumatech.jumarag.context.ContextStrategyType;
import com.jumatech.jumarag.evidence.EvidenceIntent;
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
                retrieval.embeddingProfile(), answer.contextStrategyConfigured(), answer.contextStrategyUsed(),
                hitAtK ? 1.0 : 0.0,
                answer.status(), expectedStatus, validCitationCoverage,
                answer.evidenceIntent() == golden.evidenceIntent(),
                answer.evidenceIntent(), answer.evidenceDiversitySatisfied(),
                answer.contextChunkCount(),
                answer.contextEstimatedTokens(), null);
    }

    public Evaluation evaluate(GoldenCase golden, AnswerService.AnswerResponse answer, long latencyMillis) {
        boolean expectedStatus = answer.status() == golden.expectedStatus();
        boolean validCitationCoverage = answer.status() != AnswerStatus.ANSWERED || !answer.citations().isEmpty();
        return new Evaluation(answer.embeddingProfile(), answer.contextStrategyConfigured(),
                answer.contextStrategyUsed(), null,
                answer.status(), expectedStatus, validCitationCoverage,
                answer.evidenceIntent() == golden.evidenceIntent(),
                answer.evidenceIntent(), answer.evidenceDiversitySatisfied(),
                answer.contextChunkCount(),
                answer.contextEstimatedTokens(), latencyMillis);
    }

    public record GoldenCase(
            String question,
            String expectedLogicalSource,
            String expectedHeading,
            AnswerStatus expectedStatus,
            EvidenceIntent evidenceIntent) {
        public GoldenCase(
                String question, String expectedLogicalSource, String expectedHeading, AnswerStatus expectedStatus) {
            this(question, expectedLogicalSource, expectedHeading, expectedStatus, EvidenceIntent.GENERAL);
        }
    }

    public record Evaluation(
            String embeddingProfile,
            ContextStrategyType configuredStrategy,
            ContextStrategyType usedStrategy,
            Double hitAtK,
            AnswerStatus answerStatus,
            boolean expectedStatus,
            boolean validCitationCoverage,
            boolean expectedEvidenceIntent,
            EvidenceIntent evidenceIntent,
            boolean evidenceDiversitySatisfied,
            int contextChunkCount,
            int estimatedTokens,
            Long latencyMillis) {
        public boolean passed() {
            return (hitAtK == null || hitAtK == 1.0)
                    && expectedStatus
                    && validCitationCoverage
                    && expectedEvidenceIntent
                    && (evidenceDiversitySatisfied || answerStatus != AnswerStatus.ANSWERED);
        }
    }

    public Summary summarize(List<Evaluation> evaluations) {
        double hitRate = evaluations.stream().map(Evaluation::hitAtK).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().orElse(0);
        long statuses = evaluations.stream().filter(Evaluation::expectedStatus).count();
        long citationCoverage = evaluations.stream().filter(Evaluation::validCitationCoverage).count();
        long evidenceIntents = evaluations.stream().filter(Evaluation::expectedEvidenceIntent).count();
        long diversity = evaluations.stream().filter(evaluation ->
                evaluation.evidenceDiversitySatisfied() || evaluation.answerStatus() != AnswerStatus.ANSWERED).count();
        Set<String> profiles = evaluations.stream()
                .map(Evaluation::embeddingProfile)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        Set<ContextStrategyType> configuredStrategies = evaluations.stream().map(Evaluation::configuredStrategy)
                .collect(Collectors.toUnmodifiableSet());
        Set<ContextStrategyType> usedStrategies = evaluations.stream().map(Evaluation::usedStrategy)
                .collect(Collectors.toUnmodifiableSet());
        return new Summary(profiles, configuredStrategies, usedStrategies,
                hitRate, statuses, citationCoverage, evidenceIntents, diversity, evaluations.size());
    }

    public record Summary(
            Set<String> embeddingProfiles,
            Set<ContextStrategyType> configuredStrategies,
            Set<ContextStrategyType> usedStrategies,
            double hitAtK,
            long expectedStatuses,
            long validCitationCoverage,
            long expectedEvidenceIntents,
            long validEvidenceDiversity,
            int cases) {}
}
