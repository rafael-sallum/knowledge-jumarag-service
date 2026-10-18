package com.jumatech.jumarag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.embedding.EmbeddingRepository;
import com.jumatech.jumarag.embedding.EmbeddingService;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseRepository;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.retrieval.RetrievalService;
import com.jumatech.jumarag.source.ChunkService;
import com.jumatech.jumarag.source.KnowledgeSource;
import com.jumatech.jumarag.source.SourceService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhaseZeroIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jumarag_test")
            .withUsername("jumarag")
            .withPassword("jumarag_test");

    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired SourceService sources;
    @Autowired ChunkService chunks;
    @Autowired EmbeddingService embeddings;
    @Autowired EmbeddingRepository embeddingRepository;
    @Autowired RetrievalService retrieval;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestRestTemplate http;

    @Test
    @Transactional
    void persistsProvenanceAndUsesPostgresForFilteredVectorRetrieval() {
        assertThat(jdbc.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
                        .query(String.class)
                        .single())
                .startsWith("0.8.");

        var base = knowledgeBases.create(
                "salloon-snooker-rules", "Snooker rules", "Controlled rules for retrieval tests.", "pt-BR");
        var otherBase = knowledgeBases.create(
                "unrelated-domain", "Other domain", "Isolation fixture.", "pt-BR");
        var source = sources.create(base.id(), KnowledgeSource.SourceType.TEXT, "Controlled rules", null);
        var otherSource = sources.create(otherBase.id(), KnowledgeSource.SourceType.TEXT, "Other rules", null);
        var version = sources.createVersion(
                source.id(), "2024-25", "a".repeat(64), "text/plain", 500, null, null);
        var otherVersion = sources.createVersion(
                otherSource.id(), "v1", "b".repeat(64), "text/plain", 100, null, null);

        var freeBall = chunks.create(
                version.id(),
                0,
                "Bola livre no snooker pode ser nomeada depois de uma falta quando há snooker.",
                "pt-BR",
                "Section 3 > Free ball",
                12,
                12,
                objectMapper.createObjectNode().put("rule", "free-ball"));
        var respottedBlack = chunks.create(
                version.id(),
                1,
                "Respotted black decide um frame empatado com a bola preta recolocada.",
                "pt-BR",
                "Section 4 > Respotted black",
                20,
                20,
                objectMapper.createObjectNode().put("rule", "respotted-black"));
        var english = chunks.create(
                version.id(),
                2,
                "A free ball can be nominated after a qualifying foul.",
                "en",
                "Section 3 > Free ball",
                12,
                12,
                objectMapper.createObjectNode());
        var isolated = chunks.create(
                otherVersion.id(),
                0,
                "Bola livre em um domínio não autorizado para esta busca.",
                "pt-BR",
                null,
                null,
                null,
                objectMapper.createObjectNode());

        embeddings.embed(freeBall.id());
        embeddings.embed(respottedBlack.id());
        embeddings.embed(english.id());
        embeddings.embed(isolated.id());

        assertThat(embeddingRepository.exists(freeBall.id(), EmbeddingRepository.PHASE_ZERO_PROFILE_ID)).isTrue();
        var hits = retrieval.search(base.id(), "regra da bola livre snooker", "pt-BR", null, 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().chunkId()).isEqualTo(freeBall.id());
        assertThat(hits).extracting(hit -> hit.chunkId()).doesNotContain(english.id(), isolated.id());
        assertThat(hits).allMatch(hit -> hit.language().equals("pt-BR"));

        jdbc.sql("UPDATE knowledge_source SET enabled = false WHERE id = :id")
                .param("id", source.id())
                .update();
        assertThat(retrieval.search(base.id(), "bola livre", "pt-BR", null, 10)).isEmpty();
    }

    @Test
    void enforcesUniquenessAndForeignKeys() {
        knowledgeBases.create("unique-base", "Unique", "Unique fixture.", "en");
        assertThatThrownBy(() -> knowledgeBases.create("unique-base", "Duplicate", "Duplicate fixture.", "en"))
                .hasMessageContaining("already exists");

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO knowledge_source (
                                    id, knowledge_base_id, source_type, logical_name, enabled, created_at, updated_at
                                ) VALUES (:id, :knowledgeBaseId, 'TEXT', 'orphan', true, now(), now())
                                """)
                        .param("id", UUID.randomUUID())
                        .param("knowledgeBaseId", UUID.randomUUID())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exposesLivenessReadinessAndOpenApi() {
        var actuator = http.getForEntity("/actuator", String.class);
        var liveness = http.getForEntity("/actuator/health/liveness", String.class);
        assertThat(liveness.getStatusCode())
                .withFailMessage(
                        "Liveness response was %s; Actuator links were %s",
                        liveness.getBody(), actuator.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/health/readiness", String.class).getBody())
                .contains("\"status\":\"UP\"");
        assertThat(http.getForEntity("/v3/api-docs", String.class).getBody())
                .contains("/v1/knowledge-bases/{knowledgeBaseId}/retrieval/search");
    }

    @Test
    void returnsCorrelatedValidationErrorsWithoutStackTraces() {
        ResponseEntity<String> response = http.postForEntity(
                "/v1/knowledge-bases",
                Map.of(
                        "slug", "Not Valid",
                        "name", "",
                        "description", "",
                        "defaultLocale", "invalid_locale"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        assertThat(response.getBody())
                .contains("\"code\":\"VALIDATION_FAILED\"")
                .contains("\"requestId\"")
                .doesNotContain("stackTrace");
    }

    @Test
    void returnsNotFoundForUnknownResourcesInsteadOfMaskingThemAsServerErrors() {
        ResponseEntity<String> response = http.getForEntity("/v1/not-a-resource", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .contains("\"code\":\"RESOURCE_NOT_FOUND\"")
                .doesNotContain("stackTrace");
    }
}
