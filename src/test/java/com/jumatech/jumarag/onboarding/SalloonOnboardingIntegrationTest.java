package com.jumatech.jumarag.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.answer.AnswerService;
import com.jumatech.jumarag.answer.AnswerStatus;
import com.jumatech.jumarag.context.ContextStrategyType;
import com.jumatech.jumarag.evaluation.GoldenEvaluationHarness;
import com.jumatech.jumarag.ingestion.IngestionService;
import com.jumatech.jumarag.knowledgebase.KnowledgeBase;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.security.ApiClientService;
import com.jumatech.jumarag.security.KnowledgeBaseGrant;
import com.jumatech.jumarag.snapshot.SnapshotService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = {
    "app.ingestion.max-upload-bytes=40000",
    "app.ingestion.target-words=180",
    "app.ingestion.max-words=260",
    "app.ingestion.overlap-words=30",
    "app.context.long-context-max-chunks=40",
    "app.context.long-context-max-characters=50000",
    "app.context.long-context-max-estimated-tokens=12500"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SalloonOnboardingIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jumarag_salloon_onboarding_test")
            .withUsername("jumarag")
            .withPassword("jumarag_test");

    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired IngestionService ingestion;
    @Autowired SnapshotService snapshots;
    @Autowired ApiClientService clients;
    @Autowired AnswerService answers;
    @Autowired ObjectMapper objectMapper;

    @Test
    void implementationNotesAreIdempotentSnapshottedAndComparableAcrossStrategies() throws Exception {
        KnowledgeBase base = knowledgeBases.create(
                "salloon-onboarding-" + UUID.randomUUID().toString().substring(0, 8),
                "Salloon onboarding fixture", "Controlled first-client fixture.", "en");
        String implementationText = Files.readString(Path.of(
                "knowledge/onboarding/salloon/snooker/implementation/salloon-snooker-implementation-notes.md"));
        var implementation = ingestion.ingestText(base.id(), "Salloon Score Snooker implementation notes",
                "IMPLEMENTATION_NOTE", "en", "Implementation notes", implementationText,
                objectMapper.createObjectNode().put("owner", "Juma Tech"));
        var duplicate = ingestion.ingestText(base.id(), "Salloon Score Snooker implementation notes",
                "IMPLEMENTATION_NOTE", "en", "Implementation notes", implementationText,
                objectMapper.createObjectNode().put("owner", "Juma Tech"));
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.sourceVersionId()).isEqualTo(implementation.sourceVersionId());

        var official = ingestion.ingestText(base.id(), "Controlled official-rule fixture", "OFFICIAL_RULE", "en",
                "Controlled fixture", "A Free Ball is controlled evidence after a foul. This CI fixture is not a reproduced rulebook.",
                objectMapper.createObjectNode().put("fixture", true));
        var snapshot = snapshots.create(base.id(), "salloon-fixture-v1",
                List.of(official.sourceVersionId(), implementation.sourceVersionId()),
                objectMapper.createObjectNode().put("domain", "salloon"));
        snapshot = snapshots.activate(base.id(), snapshot.id());
        assertThat(snapshot.items()).extracting(item -> item.sourceClassification())
                .containsExactlyInAnyOrder("OFFICIAL_RULE", "IMPLEMENTATION_NOTE");

        var createdClient = clients.create("salloon-fixture-client", objectMapper.createObjectNode());
        clients.grant(createdClient.client().id(), base.id(), KnowledgeBaseGrant.Permission.ASK);
        GoldenEvaluationHarness harness = new GoldenEvaluationHarness();
        List<GoldenEvaluationHarness.Evaluation> evaluations = new ArrayList<>();
        for (ContextStrategyType strategy : ContextStrategyType.values()) {
            knowledgeBases.updateContextStrategy(base.id(), strategy);
            AnswerService.AnswerResponse answer = answers.ask(createdClient.client().id(), base.slug(),
                    new AnswerService.AnswerRequest("How does Salloon record a Free Ball after a foul?", "en",
                            "salloon.match-context.v1", objectMapper.createObjectNode()
                                    .put("rulesetFamily", "SNOOKER").put("rulesetId", "snooker-wpbsa-2024-v1"),
                            null));
            evaluations.add(harness.evaluate(new GoldenEvaluationHarness.GoldenCase(
                    "How does Salloon record a Free Ball after a foul?",
                    "Salloon Score Snooker implementation notes", null, AnswerStatus.ANSWERED), answer, 0));
            assertThat(answer.snapshotId()).isEqualTo(snapshot.id());
            assertThat(answer.citations()).isNotEmpty();
        }
        assertThat(evaluations).allMatch(GoldenEvaluationHarness.Evaluation::passed);
        assertThat(harness.summarize(evaluations).configuredStrategies())
                .containsExactlyInAnyOrder(ContextStrategyType.values());
    }
}
