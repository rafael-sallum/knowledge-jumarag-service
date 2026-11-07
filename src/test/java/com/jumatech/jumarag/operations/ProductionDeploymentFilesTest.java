package com.jumatech.jumarag.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductionDeploymentFilesTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void productionComposeIsPrivateAndUsesDedicatedStableResources() throws IOException {
        String compose = Files.readString(ROOT.resolve("compose.production.yml"));

        assertThat(compose)
                .contains("name: jumarag")
                .contains("pgvector/pgvector:0.8.6-pg17-bookworm")
                .contains("name: jumarag-postgres-data")
                .contains("name: jumarag-internal")
                .contains("name: jumarag-provider-egress")
                .contains("external: true")
                .contains("- jumarag-api")
                .contains("SPRINGDOC_API_DOCS_ENABLED: \"false\"")
                .contains("SPRINGDOC_SWAGGER_UI_ENABLED: \"false\"")
                .contains("JUMARAG_EMBEDDING_PROVIDER: ${JUMARAG_EMBEDDING_PROVIDER:?")
                .contains("JUMARAG_CHAT_PROVIDER: ${JUMARAG_CHAT_PROVIDER:?")
                .doesNotContain("latest", "0.0.0.0:");

        String apiBlock = compose.substring(compose.indexOf("  api:"), compose.indexOf("  worker:"));
        String workerBlock = compose.substring(compose.indexOf("  worker:"), compose.lastIndexOf("\nvolumes:\n"));
        String postgresBlock = compose.substring(compose.indexOf("  postgres:"), compose.indexOf("  migrate:"));
        assertThat(apiBlock).doesNotContain("ports:");
        assertThat(workerBlock).contains("- provider-egress").doesNotContain("rag-client", "ports:");
        assertThat(postgresBlock).doesNotContain("ports:");
    }

    @Test
    void imagePublishingIsShaOnlyAndWaitsForSuccessfulCi() throws IOException {
        String workflow = Files.readString(ROOT.resolve(".github/workflows/publish-image.yml"));

        assertThat(workflow)
                .contains("workflow_run:")
                .contains("workflow_run.conclusion == 'success'")
                .contains("github.event.workflow_run.head_sha")
                .contains("ghcr.io/${{ github.repository }}:${{ github.event.workflow_run.head_sha }}")
                .doesNotContain(":latest");
    }

    @Test
    void productionEnvironmentAndOperationsContainOnlyPlaceholders() throws IOException {
        String environment = Files.readString(ROOT.resolve("ops/production/jumarag.env.example"));
        String preflight = Files.readString(ROOT.resolve("ops/production/preflight.sh"));
        String clientBootstrap = Files.readString(ROOT.resolve("ops/production/bootstrap-salloon-client.sh"));

        assertThat(environment)
                .contains("JUMARAG_EMBEDDING_PROVIDER=openai")
                .contains("JUMARAG_CHAT_PROVIDER=openai")
                .contains("OPENAI_API_KEY=<dedicated-production-project-service-account-key>")
                .contains("JUMARAG_OPENAI_EMBEDDING_MODEL=text-embedding-3-small")
                .contains("JUMARAG_OPENAI_CHAT_MODEL=gpt-6-luna")
                .doesNotContain("sk-");
        assertThat(preflight)
                .contains("Production embedding and chat providers must both be openai")
                .contains(".services.postgres.ports")
                .contains(".services.api.ports")
                .contains(".services.worker.ports")
                .contains(".networks[\"rag-client\"].external == true");
        assertThat(clientBootstrap)
                .contains("salloon-production")
                .contains("permission:\"ASK\"")
                .contains("salloon-snooker-rules", "salloon-brazilian-rules")
                .doesNotContain("permission:\"RETRIEVE\"");
    }

    @Test
    void productionRunbookKeepsArtifactsAndSecretsOutsideReleases() throws IOException {
        String runbook = Files.readString(ROOT.resolve("docs/PRODUCTION_DEPLOYMENT.md"));

        assertThat(runbook)
                .contains("/opt/jumarag/infra/.env")
                .contains("/opt/jumarag/backups")
                .contains("/opt/jumarag/knowledge/sources/snooker/wpbsa-rulebook-2024-25.pdf")
                .contains("/opt/jumarag/knowledge/sources/brazilian/cbbs-regras-sinuca-2012.doc")
                .contains("/opt/jumarag/knowledge/sources/brazilian/cbbs-regulamento-esportes-bilhar-2013.doc")
                .contains("SALLOON_AI_ASSISTANT_ENABLED=false")
                .contains("Flyway migrations are never rolled backward destructively")
                .contains("No public Caddy route");
    }
}
