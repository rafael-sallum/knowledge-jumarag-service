package com.jumatech.jumarag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.embedding.EmbeddingBackfillRepository;
import com.jumatech.jumarag.embedding.EmbeddingBackfillService;
import com.jumatech.jumarag.embedding.EmbeddingGateway;
import com.jumatech.jumarag.embedding.EmbeddingProfileResolver;
import com.jumatech.jumarag.embedding.EmbeddingRepository;
import com.jumatech.jumarag.ingestion.IngestionService;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.retrieval.RetrievalProperties;
import com.jumatech.jumarag.retrieval.RetrievalRepository;
import com.jumatech.jumarag.retrieval.RetrievalService;
import com.jumatech.jumarag.security.ApiClientService;
import com.jumatech.jumarag.snapshot.SnapshotService;
import com.jumatech.jumarag.source.SourceService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhaseThreeIntegrationTest {
    private static final String OPENAI_PROFILE = "openai-text-embedding-3-small-1536";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jumarag_phase3_test")
            .withUsername("jumarag")
            .withPassword("jumarag_test");

    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired IngestionService ingestion;
    @Autowired SnapshotService snapshots;
    @Autowired EmbeddingGateway fakeGateway;
    @Autowired EmbeddingRepository embeddings;
    @Autowired EmbeddingBackfillRepository backfillRepository;
    @Autowired RetrievalRepository retrievalRepository;
    @Autowired SourceService sources;
    @Autowired ApiClientService clients;
    @Autowired MeterRegistry metrics;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestRestTemplate http;

    @Test
    void migrationRegistersExplicitOpenAiProfileAndBackfillPreservesFakeVectors() {
        var profile = embeddings.findProfileByName(OPENAI_PROFILE).orElseThrow();
        assertThat(profile.id()).isEqualTo(EmbeddingRepository.OPENAI_TEXT_EMBEDDING_3_SMALL_PROFILE_ID);
        assertThat(profile.provider()).isEqualTo("OPENAI");
        assertThat(profile.model()).isEqualTo("text-embedding-3-small");
        assertThat(profile.dimensions()).isEqualTo(1536);
        assertThat(profile.distanceMetric()).isEqualTo("COSINE");

        var base = knowledgeBases.create(unique("phase3"), "Phase 3", "Profile isolation fixture.", "en");
        var ingested = ingestion.ingestText(
                base.id(), "Rules", "OFFICIAL_RULE", "en", "Rules",
                "A free ball applies after a qualifying foul when the incoming player is snookered.",
                objectMapper.createObjectNode());
        var snapshot = snapshots.create(
                base.id(), "S1", List.of(ingested.sourceVersionId()), objectMapper.createObjectNode());
        snapshots.activate(base.id(), snapshot.id());

        int chunks = ingested.chunkCount();
        assertThat(ingested.embeddingProfile()).isEqualTo("phase0-fake-1536");
        assertThat(countEmbeddings(ingested.sourceVersionId(), EmbeddingRepository.PHASE_ZERO_PROFILE_ID))
                .isEqualTo(chunks);
        assertThat(countEmbeddings(
                        ingested.sourceVersionId(), EmbeddingRepository.OPENAI_TEXT_EMBEDDING_3_SMALL_PROFILE_ID))
                .isZero();

        EmbeddingGateway openAiStub = openAiStub();
        EmbeddingProfileResolver openAiProfiles = new EmbeddingProfileResolver(embeddings, openAiStub);
        EmbeddingBackfillService backfill = new EmbeddingBackfillService(
                knowledgeBases, snapshots, openAiProfiles, openAiStub, embeddings,
                backfillRepository, metrics, transactions);

        var first = backfill.backfill(base.id(), OPENAI_PROFILE, snapshot.id(), 25);
        var repeated = backfill.backfill(base.id(), OPENAI_PROFILE, snapshot.id(), 25);
        assertThat(first.embedded()).isEqualTo(chunks);
        assertThat(first.remaining()).isZero();
        assertThat(repeated.embedded()).isZero();
        assertThat(repeated.alreadyEmbedded()).isEqualTo(chunks);
        assertThat(countEmbeddings(ingested.sourceVersionId(), EmbeddingRepository.PHASE_ZERO_PROFILE_ID))
                .isEqualTo(chunks);
        assertThat(countEmbeddings(
                        ingested.sourceVersionId(), EmbeddingRepository.OPENAI_TEXT_EMBEDDING_3_SMALL_PROFILE_ID))
                .isEqualTo(chunks);

        RetrievalService openAiRetrieval = new RetrievalService(
                retrievalRepository, openAiStub, knowledgeBases, sources, clients, snapshots,
                openAiProfiles, new RetrievalProperties(OPENAI_PROFILE), metrics);
        var result = openAiRetrieval.search(
                base.id(), "When does a free ball apply after a foul?", "en", null, 5, snapshot.id());
        assertThat(result.embeddingProfile()).isEqualTo(OPENAI_PROFILE);
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().getFirst().text()).contains("free ball");
    }

    @Test
    void backfillEndpointIsAdminOnlyAndClientCredentialCannotCallIt() {
        UUID baseId = knowledgeBases.create(unique("admin"), "Admin", "Auth fixture.", "en").id();
        String path = "/v1/admin/knowledge-bases/" + baseId + "/embeddings/backfill";
        Map<String, Object> body = Map.of("embeddingProfile", OPENAI_PROFILE, "batchSize", 10);

        assertThat(http.postForEntity(path, body, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var client = clients.create(unique("client"), objectMapper.createObjectNode());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-JumaRAG-API-Key", client.apiKey());
        assertThat(http.postForEntity(path, new HttpEntity<>(body, headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private int countEmbeddings(UUID sourceVersionId, UUID profileId) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM chunk_embedding ce
                        JOIN document_chunk dc ON dc.id = ce.chunk_id
                        WHERE dc.source_version_id = :versionId
                          AND ce.embedding_profile_id = :profileId
                        """)
                .param("versionId", sourceVersionId)
                .param("profileId", profileId)
                .query(Integer.class)
                .single();
    }

    private EmbeddingGateway openAiStub() {
        return new EmbeddingGateway() {
            @Override
            public float[] embed(String text) {
                return fakeGateway.embed(text);
            }

            @Override
            public int dimensions() {
                return 1536;
            }

            @Override
            public String provider() {
                return "OPENAI";
            }

            @Override
            public String model() {
                return "text-embedding-3-small";
            }
        };
    }

    private static String unique(String prefix) {
        return prefix + '-' + UUID.randomUUID().toString().substring(0, 8);
    }
}
