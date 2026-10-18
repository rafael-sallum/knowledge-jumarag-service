package com.jumatech.jumarag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.ingestion.IngestionService;
import com.jumatech.jumarag.knowledgebase.KnowledgeBase;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.retrieval.RetrievalService;
import com.jumatech.jumarag.security.ApiClientService;
import com.jumatech.jumarag.shared.web.DomainException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhaseOneIntegrationTest {
    private static final String ADMIN_TOKEN = "test-admin-token-with-at-least-thirty-two-characters";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("jumarag_phase1_test")
            .withUsername("jumarag")
            .withPassword("jumarag_test");

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper objectMapper;
    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired ApiClientService apiClients;
    @Autowired IngestionService ingestion;
    @Autowired RetrievalService retrieval;
    @Autowired JdbcClient jdbc;

    @Test
    void separatesAdminAndClientAuthenticationAndEnforcesKnowledgeBaseGrants() throws Exception {
        ResponseEntity<String> missing = http.postForEntity(
                "/v1/admin/knowledge-bases", Map.of(), String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missing.getBody()).contains("ADMIN_AUTHENTICATION_REQUIRED").doesNotContain(ADMIN_TOKEN);
        assertThat(missing.getHeaders().getFirst("X-Request-Id")).isNotBlank();

        HttpHeaders wrongAdmin = new HttpHeaders();
        wrongAdmin.setBearerAuth("wrong-admin-token-that-is-still-long-enough");
        assertThat(http.exchange(
                                "/v1/admin/knowledge-bases",
                                HttpMethod.GET,
                                new HttpEntity<>(wrongAdmin),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        KnowledgeBase base = createKnowledgeBase("secured");
        JsonNode createdClient = createClient("secured-client");
        UUID clientId = UUID.fromString(createdClient.at("/client/id").asText());
        String apiKey = createdClient.get("apiKey").asText();
        assertThat(apiKey).startsWith("jrag_");

        String listed = http.exchange(
                        "/v1/admin/api-clients",
                        HttpMethod.GET,
                        new HttpEntity<>(adminHeaders(MediaType.APPLICATION_JSON)),
                        String.class)
                .getBody();
        assertThat(listed).contains("secured-client").doesNotContain(apiKey);
        assertThat(jdbc.sql("SELECT octet_length(secret_hash) FROM api_client WHERE id = :id")
                        .param("id", clientId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(32);

        HttpHeaders clientHeaders = clientHeaders(apiKey);
        assertThat(http.exchange(
                                "/v1/admin/knowledge-bases",
                                HttpMethod.GET,
                                new HttpEntity<>(clientHeaders),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ingestion.ingestText(
                base.id(),
                "Secured rules",
                "OFFICIAL_RULE",
                "pt-BR",
                "Bola livre",
                "Bola livre é uma regra controlada para busca autorizada.",
                objectMapper.createObjectNode());
        String retrievalPath = "/v1/knowledge-bases/" + base.slug() + "/retrieval/search";
        HttpEntity<Map<String, Object>> search = new HttpEntity<>(
                Map.of("query", "regra bola livre", "language", "pt-BR"), clientHeaders);
        assertThat(http.postForEntity(retrievalPath, search, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> grant = http.postForEntity(
                "/v1/admin/api-clients/" + clientId + "/grants",
                new HttpEntity<>(Map.of("knowledgeBaseId", base.id()), adminHeaders(MediaType.APPLICATION_JSON)),
                String.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> permitted = http.postForEntity(retrievalPath, search, String.class);
        assertThat(permitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(permitted.getBody()).contains("Bola livre é uma regra controlada");

        assertThat(http.exchange(
                                "/v1/admin/api-clients/" + clientId + "/grants/" + base.id(),
                                HttpMethod.DELETE,
                                new HttpEntity<>(adminHeaders(MediaType.APPLICATION_JSON)),
                                Void.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.postForEntity(retrievalPath, search, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        http.postForEntity(
                "/v1/admin/api-clients/" + clientId + "/disable",
                new HttpEntity<>(adminHeaders(MediaType.APPLICATION_JSON)),
                String.class);
        assertThat(http.postForEntity(retrievalPath, search, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity(
                                retrievalPath,
                                new HttpEntity<>(Map.of("query", "bola"), clientHeaders(apiKey + "wrong")),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ingestsTextIdempotentlyCreatesImmutableChangedVersionsAndRetrievesImmediately() {
        KnowledgeBase base = knowledgeBases.create(
                unique("text-ingestion"), "Text ingestion", "Phase 1 text test.", "pt-BR");
        JsonNode metadata = objectMapper.createObjectNode().put("edition", "2026");
        String content = "# Falta e retorno\r\n\r\nUma falta pode permitir retorno conforme a regra.   \r\n";

        var first = ingestion.ingestText(
                base.id(), "Official rules", "OFFICIAL_RULE", "pt-BR", "Falta", content, metadata);
        var duplicate = ingestion.ingestText(
                base.id(),
                "Official rules",
                "OFFICIAL_RULE",
                "pt-BR",
                "Falta",
                "# Falta e retorno\n\nUma falta pode permitir retorno conforme a regra.\n",
                metadata);
        var changed = ingestion.ingestText(
                base.id(),
                "Official rules",
                "OFFICIAL_RULE",
                "pt-BR",
                "Falta",
                content + "Uma edição nova esclarece a reposição.",
                metadata);

        assertThat(first.status().name()).isEqualTo("READY");
        assertThat(first.chunkCount()).isPositive().isEqualTo(first.embeddingCount());
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.sourceVersionId()).isEqualTo(first.sourceVersionId());
        assertThat(duplicate.checksum()).isEqualTo(first.checksum());
        assertThat(changed.duplicate()).isFalse();
        assertThat(changed.knowledgeSourceId()).isEqualTo(first.knowledgeSourceId());
        assertThat(changed.sourceVersionId()).isNotEqualTo(first.sourceVersionId());
        assertThat(jdbc.sql("SELECT count(*) FROM source_version WHERE source_id = :sourceId")
                        .param("sourceId", first.knowledgeSourceId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbc.sql("UPDATE source_version SET normalized_content = 'tampered' WHERE id = :id")
                        .param("id", first.sourceVersionId())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        var client = apiClients.create(unique("text-client"), objectMapper.createObjectNode());
        apiClients.grant(client.client().id(), base.id());
        var hits = retrieval.searchAuthorized(
                client.client().id(), base.slug(), "falta retorno regra", "pt-BR", null, 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().sourceId()).isEqualTo(first.knowledgeSourceId());
        assertThat(retrieval.searchAuthorized(
                        client.client().id(), base.slug(), "falta retorno", "en", null, 10))
                .isEmpty();

        jdbc.sql("UPDATE knowledge_source SET enabled = false WHERE id = :id")
                .param("id", first.knowledgeSourceId())
                .update();
        assertThat(retrieval.searchAuthorized(
                        client.client().id(), base.slug(), "falta retorno", "pt-BR", null, 10))
                .isEmpty();
    }

    @Test
    void acceptsTxtAndMarkdownFilesAndRejectsUnsupportedAndOversizedUploads() throws Exception {
        KnowledgeBase base = createKnowledgeBase("files");

        JsonNode txt = upload(base.id(), "notes.txt", "text/plain", "Regra em arquivo de texto.", "TXT rules");
        JsonNode markdown = upload(
                base.id(),
                "../rules.md",
                "text/markdown",
                "# Snooker\n\n## Bola livre\n\nUma regra em Markdown.",
                "Markdown rules");

        assertThat(txt.get("status").asText()).isEqualTo("READY");
        assertThat(markdown.get("status").asText()).isEqualTo("READY");
        UUID markdownVersion = UUID.fromString(markdown.get("sourceVersionId").asText());
        assertThat(jdbc.sql("SELECT original_filename FROM source_version WHERE id = :id")
                        .param("id", markdownVersion)
                        .query(String.class)
                        .single())
                .isEqualTo("rules.md");
        assertThat(jdbc.sql("SELECT heading_path FROM document_chunk WHERE source_version_id = :id")
                        .param("id", markdownVersion)
                        .query(String.class)
                        .list())
                .contains("Snooker > Bola livre");

        ResponseEntity<String> unsupported = uploadRaw(
                base.id(), "rules.pdf", "application/pdf", "not-a-pdf".getBytes(StandardCharsets.UTF_8), "PDF");
        assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unsupported.getBody()).contains("UNSUPPORTED_SOURCE_FILE");

        MockMultipartFile oversized = new MockMultipartFile(
                "file", "large.txt", "text/plain", new byte[4097]);
        assertThatThrownBy(() -> ingestion.ingestFile(
                        base.id(),
                        "Large file",
                        "CONTROLLED_SOURCE",
                        "pt-BR",
                        "Large",
                        objectMapper.createObjectNode(),
                        oversized))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("configured limit");
    }

    @Test
    void preventsConcurrentDuplicateVersionsAndDoesNotPublishFailedPartialIngestion() throws Exception {
        KnowledgeBase base = knowledgeBases.create(
                unique("concurrent"), "Concurrent", "Concurrency fixture.", "pt-BR");
        Callable<IngestionService.IngestionResult> request = () -> ingestion.ingestText(
                base.id(),
                "Concurrent source",
                "OFFICIAL_RULE",
                "pt-BR",
                "Same content",
                "Conteúdo idêntico submetido ao mesmo tempo.",
                objectMapper.createObjectNode());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(request);
            var second = executor.submit(request);
            UUID firstVersion = first.get().sourceVersionId();
            UUID secondVersion = second.get().sourceVersionId();
            assertThat(firstVersion).isEqualTo(secondVersion);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM source_version sv JOIN knowledge_source ks ON ks.id = sv.source_id WHERE ks.knowledge_base_id = :id")
                        .param("id", base.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);

        assertThatThrownBy(() -> ingestion.ingestText(
                        base.id(),
                        "Embedding failure",
                        "IMPLEMENTATION_NOTE",
                        "pt-BR",
                        "No indexable tokens",
                        "😀 😀 😀",
                        objectMapper.createObjectNode()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("without publishing partial content");
        UUID failedVersion = jdbc.sql("SELECT sv.id FROM source_version sv JOIN knowledge_source ks ON ks.id = sv.source_id WHERE ks.knowledge_base_id = :id AND ks.logical_name = 'Embedding failure'")
                .param("id", base.id())
                .query(UUID.class)
                .single();
        assertThat(jdbc.sql("SELECT status FROM source_version WHERE id = :id")
                        .param("id", failedVersion)
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM document_chunk WHERE source_version_id = :id")
                        .param("id", failedVersion)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    private KnowledgeBase createKnowledgeBase(String label) throws Exception {
        String slug = unique(label);
        ResponseEntity<String> response = http.postForEntity(
                "/v1/admin/knowledge-bases",
                new HttpEntity<>(
                        Map.of(
                                "slug", slug,
                                "name", label + " knowledge",
                                "description", "Phase 1 integration fixture.",
                                "defaultLocale", "pt-BR"),
                        adminHeaders(MediaType.APPLICATION_JSON)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readValue(response.getBody(), KnowledgeBase.class);
    }

    private JsonNode createClient(String label) throws Exception {
        ResponseEntity<String> response = http.postForEntity(
                "/v1/admin/api-clients",
                new HttpEntity<>(
                        Map.of("name", unique(label), "metadata", Map.of("purpose", "integration-test")),
                        adminHeaders(MediaType.APPLICATION_JSON)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode upload(UUID knowledgeBaseId, String filename, String mediaType, String content, String logicalName)
            throws Exception {
        ResponseEntity<String> response = uploadRaw(
                knowledgeBaseId, filename, mediaType, content.getBytes(StandardCharsets.UTF_8), logicalName);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private ResponseEntity<String> uploadRaw(
            UUID knowledgeBaseId, String filename, String mediaType, byte[] content, String logicalName) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("logicalName", logicalName);
        parts.add("sourceClassification", "CONTROLLED_SOURCE");
        parts.add("language", "pt-BR");
        parts.add("title", logicalName);
        parts.add("metadata", "{\"fixture\":true}");
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = adminHeaders(MediaType.MULTIPART_FORM_DATA);
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(mediaType));
        parts.set("file", new HttpEntity<>(parts.getFirst("file"), fileHeaders));
        return http.exchange(
                "/v1/admin/knowledge-bases/" + knowledgeBaseId + "/sources/file",
                HttpMethod.POST,
                new HttpEntity<>(parts, headers),
                String.class);
    }

    private static HttpHeaders adminHeaders(MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ADMIN_TOKEN);
        headers.setContentType(contentType);
        return headers;
    }

    private static HttpHeaders clientHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-JumaRAG-API-Key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
