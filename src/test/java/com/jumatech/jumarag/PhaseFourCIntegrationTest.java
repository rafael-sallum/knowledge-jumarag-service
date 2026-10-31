package com.jumatech.jumarag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.answer.AnswerService;
import com.jumatech.jumarag.answer.AnswerStatus;
import com.jumatech.jumarag.context.ContextStrategyType;
import com.jumatech.jumarag.evidence.EvidenceIntent;
import com.jumatech.jumarag.ingestion.IngestionService;
import com.jumatech.jumarag.knowledgebase.KnowledgeBase;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.security.ApiClientService;
import com.jumatech.jumarag.security.KnowledgeBaseGrant;
import com.jumatech.jumarag.snapshot.SnapshotService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.context.rag-max-chunks=6",
    "app.context.long-context-max-chunks=8",
    "app.context.long-context-max-characters=12000",
    "app.context.long-context-max-estimated-tokens=3000",
    "app.context.hybrid-max-chunks=8",
    "app.context.auto-strong-similarity=0.35"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhaseFourCIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jumarag_phase4c_test")
            .withUsername("jumarag")
            .withPassword("jumarag_test");

    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired IngestionService ingestion;
    @Autowired SnapshotService snapshots;
    @Autowired ApiClientService clients;
    @Autowired AnswerService answers;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestRestTemplate http;

    private KnowledgeBase base;
    private UUID clientId;
    private String apiKey;
    private UUID completeSnapshotId;
    private UUID implementationOnlySnapshotId;

    @BeforeEach
    void fixture() {
        base = knowledgeBases.create(
                "phase4c-" + UUID.randomUUID().toString().substring(0, 8),
                "Phase 4C evidence fixture", "Official and implementation evidence.", "pt-BR");
        var official = ingestion.ingestText(
                base.id(), "Controlled official Brazilian rule", "OFFICIAL_RULE", "pt-BR", "Official rule",
                "A regra oficial de recusa permite ao beneficiado devolver a ação após a falta controlada.",
                objectMapper.createObjectNode().put("fixture", true));
        var implementation = ingestion.ingestText(
                base.id(), "Controlled Salloon implementation note", "IMPLEMENTATION_NOTE", "pt-BR",
                "Implementation note",
                "A implementação Salloon transfere automaticamente a vez e registra a opção de recusa como revisão pendente.",
                objectMapper.createObjectNode().put("fixture", true));
        var complete = snapshots.create(
                base.id(), "complete", List.of(official.sourceVersionId(), implementation.sourceVersionId()),
                objectMapper.createObjectNode());
        var implementationOnly = snapshots.create(
                base.id(), "implementation-only", List.of(implementation.sourceVersionId()),
                objectMapper.createObjectNode());
        implementationOnlySnapshotId = snapshots.activate(base.id(), implementationOnly.id()).id();
        completeSnapshotId = snapshots.activate(base.id(), complete.id()).id();
        var client = clients.create("phase4c-client-" + UUID.randomUUID(), objectMapper.createObjectNode());
        clients.grant(client.client().id(), base.id(), KnowledgeBaseGrant.Permission.ASK);
        clientId = client.client().id();
        apiKey = client.apiKey();
    }

    @Test
    void defaultGeneralAndSingleClassIntentsPreserveCompatibleBehavior() {
        AnswerService.AnswerResponse general = ask(EvidenceIntent.GENERAL, completeSnapshotId);
        AnswerService.AnswerResponse official = ask(EvidenceIntent.OFFICIAL_ONLY, completeSnapshotId);
        AnswerService.AnswerResponse implementation = ask(EvidenceIntent.IMPLEMENTATION_ONLY, completeSnapshotId);

        assertThat(general.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(general.evidenceIntent()).isEqualTo(EvidenceIntent.GENERAL);
        assertThat(general.requiredEvidenceClasses()).isEmpty();
        assertThat(official.citations()).extracting(AnswerService.Citation::sourceClassification)
                .containsOnly("OFFICIAL_RULE");
        assertThat(implementation.citations()).extracting(AnswerService.Citation::sourceClassification)
                .containsOnly("IMPLEMENTATION_NOTE");
    }

    @Test
    void everyContextStrategyEnforcesComparativeDiversity() {
        for (ContextStrategyType strategy : ContextStrategyType.values()) {
            knowledgeBases.updateContextStrategy(base.id(), strategy);
            AnswerService.AnswerResponse response = ask(
                    EvidenceIntent.OFFICIAL_AND_IMPLEMENTATION, completeSnapshotId);

            assertThat(response.status()).as(strategy.name()).isEqualTo(AnswerStatus.ANSWERED);
            assertThat(response.requiredEvidenceClasses())
                    .containsExactlyInAnyOrder("OFFICIAL_RULE", "IMPLEMENTATION_NOTE");
            assertThat(response.presentEvidenceClasses())
                    .contains("OFFICIAL_RULE", "IMPLEMENTATION_NOTE");
            assertThat(response.evidenceDiversitySatisfied()).isTrue();
            assertThat(response.citations()).extracting(AnswerService.Citation::sourceClassification)
                    .contains("OFFICIAL_RULE", "IMPLEMENTATION_NOTE");
        }
    }

    @Test
    void missingComparativeSideReturnsInsufficientWithoutPretendingCompleteness() {
        knowledgeBases.updateContextStrategy(base.id(), ContextStrategyType.RAG);
        AnswerService.AnswerResponse response = ask(
                EvidenceIntent.OFFICIAL_AND_IMPLEMENTATION, implementationOnlySnapshotId);

        assertThat(response.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(response.presentEvidenceClasses()).containsExactly("IMPLEMENTATION_NOTE");
        assertThat(response.evidenceDiversitySatisfied()).isFalse();
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void askRejectsUnknownIntentAndUnsafeProviderOrStrategyFields() {
        String path = "/v1/knowledge-bases/" + base.slug() + "/ask";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-JumaRAG-API-Key", apiKey);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        var invalidIntent = http.postForEntity(path, new HttpEntity<>("""
                {"question":"controlled question","evidenceIntent":"OFFICIAL_RULE"}
                """, headers), String.class);
        assertThat(invalidIntent.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalidIntent.getBody()).contains("INVALID_REQUEST_BODY").doesNotContain(apiKey);

        var providerOverride = http.postForEntity(path, new HttpEntity<>("""
                {"question":"controlled question","provider":"openai","model":"arbitrary","contextStrategy":"LONG_CONTEXT"}
                """, headers), String.class);
        assertThat(providerOverride.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(providerOverride.getBody()).contains("INVALID_REQUEST_BODY").doesNotContain(apiKey);
    }

    private AnswerService.AnswerResponse ask(EvidenceIntent intent, UUID snapshotId) {
        return answers.ask(clientId, base.slug(), new AnswerService.AnswerRequest(
                "Qual é a regra oficial de recusa e como a implementação Salloon trata essa opção?",
                "pt-BR", "salloon.match-context.v1",
                objectMapper.createObjectNode().put("rulesetFamily", "BRAZILIAN"),
                snapshotId, intent));
    }
}
