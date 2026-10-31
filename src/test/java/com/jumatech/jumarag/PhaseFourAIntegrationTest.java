package com.jumatech.jumarag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.answer.AnswerService;
import com.jumatech.jumarag.context.ContextDecisionReason;
import com.jumatech.jumarag.context.ContextStrategyType;
import com.jumatech.jumarag.evaluation.GoldenEvaluationHarness;
import com.jumatech.jumarag.ingestion.IngestionService;
import com.jumatech.jumarag.knowledgebase.KnowledgeBase;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.security.ApiClientService;
import com.jumatech.jumarag.security.KnowledgeBaseGrant;
import com.jumatech.jumarag.shared.web.DomainException;
import com.jumatech.jumarag.snapshot.SnapshotService;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = {
    "app.context.rag-max-chunks=1",
    "app.context.long-context-max-chunks=2",
    "app.context.long-context-max-characters=2000",
    "app.context.long-context-max-estimated-tokens=500",
    "app.context.hybrid-neighbor-chunks=1",
    "app.context.hybrid-max-chunks=4",
    "app.context.auto-strong-similarity=0.8"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhaseFourAIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jumarag_phase4a_test")
            .withUsername("jumarag")
            .withPassword("jumarag_test");

    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired IngestionService ingestion;
    @Autowired SnapshotService snapshots;
    @Autowired ApiClientService clients;
    @Autowired AnswerService answers;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;

    @Test
    void persistsStrategyAndLongContextUsesOnlyTheWholeActiveSnapshot() {
        KnowledgeBase base = base("long");
        assertThat(base.contextStrategy()).isEqualTo(ContextStrategyType.AUTO);
        var included = ingest(base, "Included", "A free ball follows the controlled foul condition.");
        ingest(base, "Excluded", "A meteor rule exists only outside the selected snapshot.");
        var snapshot = snapshots.create(base.id(), "S1", List.of(included.sourceVersionId()), json());
        snapshots.activate(base.id(), snapshot.id());
        UUID clientId = client(base);

        knowledgeBases.updateContextStrategy(base.id(), ContextStrategyType.LONG_CONTEXT);
        AnswerService.AnswerResponse response = ask(clientId, base, "When does a free ball follow a foul?");

        assertThat(response.contextStrategyConfigured()).isEqualTo(ContextStrategyType.LONG_CONTEXT);
        assertThat(response.contextStrategyUsed()).isEqualTo(ContextStrategyType.LONG_CONTEXT);
        assertThat(response.contextDecisionReason()).isEqualTo(ContextDecisionReason.CONFIGURED_LONG_CONTEXT);
        assertThat(response.contextCompleteSnapshot()).isTrue();
        assertThat(response.embeddingProfile()).isNull();
        assertThat(response.citations()).allMatch(citation -> citation.sourceVersionId().equals(included.sourceVersionId()));
        assertThat(knowledgeBases.require(base.id()).contextStrategy()).isEqualTo(ContextStrategyType.LONG_CONTEXT);
        assertThatThrownBy(() -> jdbc.sql("UPDATE knowledge_base SET context_strategy = 'UNSAFE' WHERE id = :id")
                .param("id", base.id()).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ragHybridAndAutoPreserveGroundingAndChooseDeterministically() {
        KnowledgeBase base = base("strategies");
        String content = """
                # First section
                anchoralpha controlled rule phrase establishes the opening condition and several supporting details.
                Additional words keep this paragraph deterministic for chunk generation and retrieval evaluation.

                # Neighbor section
                bridgephrase neighboring explanation completes the meaning with the required consequence and exception.
                More stable words ensure this is stored as a neighboring chunk in the same immutable source version.

                # Final section
                terminal marker describes an unrelated closing rule with enough text to form another stored chunk.
                Additional deterministic content keeps the snapshot above the configured long context chunk limit.
                """;
        var source = ingest(base, "Structured rules", content);
        var snapshot = snapshots.create(base.id(), "Large", List.of(source.sourceVersionId()), json());
        snapshots.activate(base.id(), snapshot.id());
        UUID clientId = client(base);

        knowledgeBases.updateContextStrategy(base.id(), ContextStrategyType.RAG);
        AnswerService.AnswerResponse rag = ask(clientId, base, "anchoralpha controlled rule phrase");
        assertThat(rag.contextStrategyUsed()).isEqualTo(ContextStrategyType.RAG);
        assertThat(rag.contextChunkCount()).isEqualTo(1);
        assertThat(rag.embeddingProfile()).isEqualTo("phase0-fake-1536");

        knowledgeBases.updateContextStrategy(base.id(), ContextStrategyType.HYBRID);
        AnswerService.AnswerResponse hybrid = ask(clientId, base, "bridgephrase explanation consequence");
        assertThat(hybrid.contextStrategyUsed()).isEqualTo(ContextStrategyType.HYBRID);
        assertThat(hybrid.contextChunkCount()).isGreaterThan(1);
        assertThat(hybrid.citations()).isNotEmpty();

        knowledgeBases.updateContextStrategy(base.id(), ContextStrategyType.AUTO);
        String exactChunk = jdbc.sql("""
                        SELECT dc.text FROM document_chunk dc
                        WHERE dc.source_version_id = :version ORDER BY dc.chunk_index LIMIT 1
                        """).param("version", source.sourceVersionId()).query(String.class).single();
        AnswerService.AnswerResponse autoStrong = ask(clientId, base, exactChunk);
        assertThat(autoStrong.contextStrategyUsed()).isEqualTo(ContextStrategyType.RAG);
        assertThat(autoStrong.contextDecisionReason()).isEqualTo(ContextDecisionReason.STRONG_RETRIEVAL_SIGNAL);

        AnswerService.AnswerResponse autoExpanded = ask(clientId, base, "bridgephrase question");
        assertThat(autoExpanded.contextStrategyUsed()).isEqualTo(ContextStrategyType.HYBRID);
        assertThat(autoExpanded.contextDecisionReason())
                .isEqualTo(ContextDecisionReason.RETRIEVAL_NEEDS_CONTEXT_EXPANSION);
        assertThat(autoExpanded.contextChunkCount()).isGreaterThan(1);
        assertThat(autoExpanded.citations()).allMatch(citation -> citation.snapshotId().equals(snapshot.id()));

        knowledgeBases.updateContextStrategy(base.id(), ContextStrategyType.LONG_CONTEXT);
        assertThatThrownBy(() -> ask(clientId, base, "anchoralpha"))
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.code()).isEqualTo("LONG_CONTEXT_BUDGET_EXCEEDED"));
    }

    @Test
    void autoUsesLongContextForSmallSnapshotWithoutEmbeddingProfile() {
        KnowledgeBase base = base("auto-small");
        var source = ingest(base, "Small", "A respotted black decides the controlled tied frame.");
        var snapshot = snapshots.create(base.id(), "Small", List.of(source.sourceVersionId()), json());
        snapshots.activate(base.id(), snapshot.id());
        UUID clientId = client(base);

        AnswerService.AnswerResponse response = ask(clientId, base, "What decides the tied frame?");

        assertThat(response.contextStrategyConfigured()).isEqualTo(ContextStrategyType.AUTO);
        assertThat(response.contextStrategyUsed()).isEqualTo(ContextStrategyType.LONG_CONTEXT);
        assertThat(response.contextDecisionReason()).isEqualTo(ContextDecisionReason.SNAPSHOT_FITS_LONG_CONTEXT);
        assertThat(response.embeddingProfile()).isNull();
        assertThat(response.contextCompleteSnapshot()).isTrue();
    }

    @Test
    void goldenHarnessComparesAllConfiguredStrategiesDeterministically() {
        KnowledgeBase base = base("golden-strategies");
        var source = ingest(base, "Golden rules", "A free ball applies after the controlled foul condition.");
        var snapshot = snapshots.create(base.id(), "Golden", List.of(source.sourceVersionId()), json());
        snapshots.activate(base.id(), snapshot.id());
        UUID clientId = client(base);
        GoldenEvaluationHarness harness = new GoldenEvaluationHarness();
        List<GoldenEvaluationHarness.Evaluation> evaluations = new ArrayList<>();

        for (ContextStrategyType configured : ContextStrategyType.values()) {
            knowledgeBases.updateContextStrategy(base.id(), configured);
            long started = System.nanoTime();
            AnswerService.AnswerResponse answer = ask(clientId, base, "When does a free ball apply?");
            evaluations.add(harness.evaluate(
                    new GoldenEvaluationHarness.GoldenCase(
                            "When does a free ball apply?", "Golden rules", null,
                            com.jumatech.jumarag.answer.AnswerStatus.ANSWERED),
                    answer, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        }

        GoldenEvaluationHarness.Summary summary = harness.summarize(evaluations);
        assertThat(evaluations).allMatch(GoldenEvaluationHarness.Evaluation::passed);
        assertThat(summary.configuredStrategies()).containsExactlyInAnyOrder(ContextStrategyType.values());
        assertThat(summary.usedStrategies()).contains(
                ContextStrategyType.RAG, ContextStrategyType.LONG_CONTEXT, ContextStrategyType.HYBRID);
        assertThat(evaluations).allMatch(evaluation -> evaluation.contextChunkCount() > 0);
        assertThat(evaluations).allMatch(evaluation -> evaluation.estimatedTokens() > 0);
    }

    private KnowledgeBase base(String prefix) {
        String slug = prefix + '-' + UUID.randomUUID().toString().substring(0, 8);
        return knowledgeBases.create(slug, prefix, "Phase 4A fixture.", "en");
    }

    private IngestionService.IngestionResult ingest(KnowledgeBase base, String name, String content) {
        return ingestion.ingestText(base.id(), name, "OFFICIAL_RULE", "en", name, content, json());
    }

    private UUID client(KnowledgeBase base) {
        var client = clients.create("client-" + UUID.randomUUID(), json());
        clients.grant(client.client().id(), base.id(), KnowledgeBaseGrant.Permission.ASK);
        return client.client().id();
    }

    private AnswerService.AnswerResponse ask(UUID clientId, KnowledgeBase base, String question) {
        return answers.ask(clientId, base.slug(), new AnswerService.AnswerRequest(
                question, "en", null, null, null));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode json() {
        return objectMapper.createObjectNode();
    }
}
