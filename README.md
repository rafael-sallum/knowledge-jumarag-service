# knowledge-jumarag-service

`knowledge-jumarag-service` is a reusable, versioned, retrieval-grounded domain knowledge service. Salloon Score is its first planned client, but this is not a Salloon chatbot and the core model contains no Salloon-specific rules.

Phase 0 proves the durable retrieval foundation: controlled knowledge/source versions, chunks, deterministic fake embeddings, PostgreSQL/pgvector similarity search, separate API and worker runtimes, health/metrics, Docker, and integration tests. It deliberately has **no `/ask` endpoint, chat completion, live OpenAI adapter, crawler, PDF parser, or Salloon integration**.

## Boundary

AI is consultative. Client domain systems remain authoritative. In particular, Salloon's deterministic engine remains the source of truth for scores, legal sporting transitions, control, and synchronization.

```text
       proof/admin HTTP API                 placeholder worker boundary
                 |                                      |
            API runtime                             Worker runtime
                 |                                      |
                 +---------- Spring JDBC ---------------+
                                      |
                     PostgreSQL 17 + pgvector
                 source provenance + vector(1536)

       Flyway one-shot migration runs before both runtimes
```

The Phase-0 proof endpoints have no final authentication yet and must not be exposed publicly. Service-to-service and administrator authentication are Phase 1 production gates.

## Stack

- Java 21, Maven Wrapper 3.9.11
- Spring Boot 3.5.16; Spring AI BOM 1.1.8 pinned for later adapters
- Spring MVC, Validation, JdbcClient, Actuator, Micrometer/Prometheus
- springdoc-openapi 2.9.1
- PostgreSQL 17 with pgvector 0.8.6
- Flyway-owned migrations
- Testcontainers with the production-shaped pgvector image
- multi-stage non-root JRE 21 container

Spring AI schema initialization is not used. No actual AI provider dependency or credential is required in Phase 0.

## Packages

Feature-local packages live under `com.jumatech.jumarag`:

- `knowledgebase` — knowledge-base model, persistence, service, and proof API;
- `source` — sources and immutable source versions;
- `ingestion` — document chunks and their provenance;
- `embedding` — provider boundary, deterministic fake gateway, vector persistence;
- `retrieval` — filtered exact pgvector search and timing metric;
- `operations` — runtime modes and worker boundary;
- `shared.web` — request correlation and consistent error responses.

There is intentionally no global controller/service/repository bucket and no JPA/Hibernate layer.

## Requirements

- Docker with Compose for the quickest full-stack path;
- or Java 21 plus Docker for Testcontainers.

The wrapper owns the Maven version; a workstation Maven installation is not required.

## Build and test

```bash
./mvnw test
./mvnw verify
```

Tests start `pgvector/pgvector:0.8.6-pg17-bookworm`, run real Flyway migrations, persist all Phase-0 entities/vectors, and execute filtered similarity SQL. They never call a paid provider.

## Docker Compose quickstart

```bash
cp .env.example .env
docker compose up --build --wait
```

The startup dependency is PostgreSQL healthy → migration succeeds → API and worker start. Local ports bind to loopback only:

- API: `http://127.0.0.1:8080`
- worker health only: `http://127.0.0.1:8081`
- PostgreSQL: `127.0.0.1:55433`

Useful URLs:

- API liveness: `http://127.0.0.1:8080/actuator/health/liveness`
- API readiness: `http://127.0.0.1:8080/actuator/health/readiness`
- worker readiness: `http://127.0.0.1:8081/actuator/health/readiness`
- OpenAPI JSON: `http://127.0.0.1:8080/v3/api-docs`
- Swagger UI: `http://127.0.0.1:8080/swagger-ui.html`
- Prometheus metrics: `http://127.0.0.1:8080/actuator/prometheus`

Stop without deleting the named database volume:

```bash
docker compose down
```

## Runtime modes

The same immutable image supports:

- `--app.runtime=api`: proof HTTP endpoints, OpenAPI, Actuator;
- `--app.runtime=worker`: Actuator and a placeholder background-execution boundary;
- `--app.runtime=migrate`: non-web, one-shot Flyway process that exits after migration.

API and worker keep `APP_MIGRATIONS_ENABLED=false`. This prevents a production race for schema ownership. `compose.production.yml` requires an immutable `JUMARAG_IMAGE`, publishes no database port, and keeps PostgreSQL/worker on the internal application network. API additionally joins a separately internal edge network for a future authenticated reverse proxy. Neither runtime receives general outbound access in Phase 0; that network will be added only with real providers. A proxy itself is intentionally absent from Phase 0.

## Phase-0 proof API

The OpenAPI document is the canonical request-shape reference. The proof surface supports:

- `POST|GET /v1/knowledge-bases`
- `GET /v1/knowledge-bases/{idOrSlug}`
- `POST|GET /v1/knowledge-bases/{knowledgeBaseId}/sources`
- `POST /v1/sources/{sourceId}/versions`
- `POST /v1/source-versions/{sourceVersionId}/chunks`
- `POST /v1/chunks/{chunkId}/embedding`
- `POST /v1/knowledge-bases/{knowledgeBaseId}/retrieval/search`

Retrieval always constrains knowledge base, active knowledge base, enabled source, ready source version, and embedding profile. Language and source ID are optional filters. Results expose ranked chunks and cosine similarity; no generated answer or citation claim is produced.

## Data and vector decisions

Flyway creates `knowledge_base`, `knowledge_source`, `source_version`, `document_chunk`, `embedding_profile`, and `chunk_embedding`. IDs are UUIDs, timestamps are `timestamptz`, chunk metadata is `jsonb`, and embeddings are fixed `vector(1536)`.

The seeded `phase0-fake-1536` profile defines provider/model/dimension/metric provenance. Exact cosine search (`<=>`) is intentional for a small corpus. HNSW/IVFFlat and mixed embedding spaces are deferred.

## Configuration and secrets

`.env.example` contains local-only values. Never commit `.env`, database production passwords, or provider keys. The default fake provider requires no API key and produces no network traffic. ECS JSON logs include request IDs but exclude full source text, embeddings, and credentials.

## Roadmap

1. **Phase 1:** protected administrator/service authentication; ingestion application service; TEXT/FILE registration; checksums and idempotent source-version transitions.
2. **Phase 2:** grounded `/ask`, provider adapters through Spring AI, machine-readable citations, insufficient/conflicting-evidence states.
3. **Phase 3:** controlled PDF/URL/SITEMAP ingestion and PostgreSQL-backed asynchronous jobs.
4. **Phase 4:** Salloon backend client, controlled Brazilian/Snooker knowledge bases, safe match-context DTO.
5. **Phase 5:** golden-set evaluation, hybrid retrieval/reranking, operational hardening and capacity-driven indexes.

## Architecture decisions

- [ADR-001: Standalone service and repository](docs/adr/ADR-001-standalone-service.md)
- [ADR-002: AI is consultative](docs/adr/ADR-002-consultative-ai.md)
- [ADR-003: PostgreSQL, pgvector, and Flyway-owned schema](docs/adr/ADR-003-postgresql-pgvector-flyway.md)
- [ADR-004: Deterministic fake AI providers in CI](docs/adr/ADR-004-deterministic-fake-providers.md)

This repository is designed as a portfolio-quality Java service: explicit SQL where it matters, reproducible migrations, real database integration tests, honest operational boundaries, and no architecture theater.
