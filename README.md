# knowledge-jumarag-service

`knowledge-jumarag-service` is a reusable, versioned, retrieval-grounded domain knowledge service. Salloon Score is its first planned client, but this is not a Salloon chatbot and the core model has no Salloon-specific rules.

Phase 1 provides authenticated administration and client retrieval, controlled synchronous TEXT/TXT/Markdown ingestion, immutable source versions, deterministic chunking, fake embeddings, and exact PostgreSQL/pgvector evidence retrieval. It still has **no `/ask`, answer generation, citations, real AI provider, crawler, PDF parser, or Salloon integration**.

## Boundary and architecture

AI and retrieval are consultative. Client domain systems remain authoritative. Salloon's deterministic engine, for example, remains the source of truth for scores, sporting transitions, control, and synchronization.

```text
 admin bearer token                         client API key + KB grant
         |                                             |
  /v1/admin/...                         /v1/knowledge-bases/{slug}/retrieval/search
         |                                             |
 controlled synchronous ingestion ---------------- exact evidence retrieval
         |                                             |
         +--------------- Spring JDBC -----------------+
                                 |
                    PostgreSQL 17 + pgvector
             immutable versions + vector(1536)

      Flyway migration runtime -> API runtime + worker runtime
```

Stack: Java 21, Maven Wrapper 3.9.11, Spring Boot 3.5.16, Spring AI BOM 1.1.8, Spring MVC/Validation/JdbcClient/Actuator, springdoc 2.9.1, PostgreSQL 17, pgvector 0.8.6, Flyway, Micrometer/Prometheus, and Testcontainers. The runtime image is multi-stage and non-root. No JPA, Spring AI schema initializer, paid model, external credential, or model network call is used.

## Security model

- Admin endpoints require `Authorization: Bearer <JUMARAG_ADMIN_TOKEN>`.
- Client retrieval requires `X-JumaRAG-API-Key: jrag_<public-id>_<secret>`.
- An enabled client also needs an explicit `RETRIEVE` grant for the requested knowledge base.
- The raw client key is returned only at creation. PostgreSQL stores the public prefix and an HMAC-SHA-256 digest keyed by `JUMARAG_API_KEY_PEPPER`.
- Disabling a client revokes the key immediately. Removing a grant revokes that knowledge base only.
- Liveness, readiness, OpenAPI, and Swagger remain operational endpoints; they grant no business authority.

Admin authentication is intentionally service-level for Phase 1. Production must use independent random admin-token and API-key-pepper values of at least 32 characters. Workload IAM/OAuth2/mTLS is deferred. Do not expose the service directly to the public internet.

## Build and local stack

Java 21 is required; the wrapper owns Maven:

```bash
./mvnw test
./mvnw verify
cp .env.example .env
# Replace the example secrets.
docker compose up --build --wait
```

Tests use `pgvector/pgvector:0.8.6-pg17-bookworm`, execute real Flyway migrations and retrieval SQL, and never call a paid provider.

Compose starts PostgreSQL healthy → migrations → API and worker. Local ports bind to loopback: API `8080`, worker health `8081`, PostgreSQL `55433`. OpenAPI is at `/v3/api-docs`, Swagger at `/swagger-ui.html`, probes at `/actuator/health/liveness` and `/actuator/health/readiness`, and metrics at `/actuator/prometheus`.

The same image supports `APP_RUNTIME=api`, `worker`, and one-shot `migrate`. API/worker do not run migrations. Phase 1 keeps small ingestion synchronous and introduces no unreliable in-memory queue; durable PostgreSQL jobs are deferred.

## Phase 1 API walkthrough

```bash
export API_ROOT='http://127.0.0.1:8080'
export ADMIN_TOKEN='<admin token from .env>'

# Create a knowledge base.
curl -sS -X POST "$API_ROOT/v1/admin/knowledge-bases" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"slug":"snooker-rules","name":"Snooker Rules","description":"Controlled rules","defaultLocale":"pt-BR"}'

# Create a client; preserve apiKey now because it is returned once.
curl -sS -X POST "$API_ROOT/v1/admin/api-clients" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"example-backend","metadata":{"owner":"example"}}'

# Grant RETRIEVE.
curl -sS -X POST "$API_ROOT/v1/admin/api-clients/<client-id>/grants" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"knowledgeBaseId":"<knowledge-base-id>"}'

# Ingest direct text.
curl -sS -X POST "$API_ROOT/v1/admin/knowledge-bases/<knowledge-base-id>/sources/text" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"logicalName":"Controlled notes","sourceClassification":"IMPLEMENTATION_NOTE","language":"pt-BR","title":"Rule notes","content":"# Bola livre\n\nConteúdo controlado.","metadata":{"edition":"2026"}}'

# Upload bounded UTF-8 TXT or Markdown.
curl -sS -X POST "$API_ROOT/v1/admin/knowledge-bases/<knowledge-base-id>/sources/file" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F 'logicalName=Official rules' -F 'sourceClassification=OFFICIAL_RULE' \
  -F 'language=pt-BR' -F 'title=Official rules 2026' \
  -F 'metadata={"edition":"2026"}' -F 'file=@./rules.md;type=text/markdown'

# Retrieve evidence, not an answer.
curl -sS -X POST "$API_ROOT/v1/knowledge-bases/snooker-rules/retrieval/search" \
  -H 'X-JumaRAG-API-Key: <one-time-client-api-key>' -H 'Content-Type: application/json' \
  -d '{"query":"Quando existe bola livre?","language":"pt-BR","limit":5}'
```

Admin APIs also list/disable clients and list/remove grants. The protected Phase-0 low-level source/chunk/embedding proof routes remain for compatibility, but normal ingestion uses the two source ingestion endpoints. OpenAPI documents both schemes. Errors contain stable `code`, safe `message`, and `requestId`; responses carry `X-Request-Id`.

## Ingestion and idempotency

1. Resolve/create a logical source by knowledge base and logical name.
2. Normalize Unicode to NFC, CRLF/CR to LF, trailing horizontal whitespace, outer whitespace, and excessive blank lines without lowercasing or semantic rewriting.
3. SHA-256 the normalized UTF-8 bytes.
4. Insert one immutable version per `(source_id, checksum)` as `PROCESSING`.
5. Deterministically chunk with configurable approximate word windows. Defaults target 650 words, cap at 900, overlap 100, and preserve Markdown heading paths plus word offsets.
6. Generate and persist one configured `phase0-fake-1536` embedding for every chunk.
7. Validate counts and transition to `READY` in the chunk/embedding transaction.

Identical normalized content returns the existing version with `duplicate=true`; changed content creates a new version. Failure rolls back chunks/embeddings and records `FAILED`. Retrieval admits only active knowledge bases, enabled sources, `READY` versions, the configured embedding profile, and authorized clients. Concurrent identical requests converge through database uniqueness.

A process crash exactly between version registration and processing can leave `PROCESSING`. Recovery of abandoned work is deferred to the durable PostgreSQL job phase instead of pretending an in-memory queue is reliable.

## Configuration and logs

- `JUMARAG_ADMIN_TOKEN` — mandatory, 32+ characters;
- `JUMARAG_API_KEY_PEPPER` — mandatory independent secret, 32+ characters;
- `JUMARAG_MAX_UPLOAD_BYTES` and `JUMARAG_MAX_UPLOAD_SIZE` — service and servlet limits;
- `JUMARAG_DEFAULT_EMBEDDING_PROFILE=phase0-fake-1536`;
- `JUMARAG_CHUNK_TARGET_WORDS`, `JUMARAG_CHUNK_MAX_WORDS`, `JUMARAG_CHUNK_OVERLAP_WORDS`.

Never commit `.env`, client keys, source bodies, or provider secrets. Structured logs include request/source/version/client identifiers and counts, never credentials, complete source content, or vectors.

## Deferred work and recommended Phase 2

Deferred: `/ask`, `ChatModel`, answers, citations, OpenAI/Ollama/Gemini/Anthropic/Azure, hybrid lexical retrieval, reranking, PDF/DOCX/HTML/OCR, URL/sitemaps, snapshots, async jobs, rate limits/quotas, public exposure, UI, end-user identity, and Salloon integration.

Recommended Phase 2 is a provider-neutral grounded-answer boundary over version-selected evidence with machine-readable provenance, `ANSWERED`/`INSUFFICIENT_EVIDENCE`/`CONFLICTING_SOURCES`, a deterministic fake chat adapter for CI, and golden retrieval/grounding evaluations—before any live provider adapter.

## Architecture decisions

- [ADR-001](docs/adr/ADR-001-standalone-service.md): standalone service and repository
- [ADR-002](docs/adr/ADR-002-consultative-ai.md): AI is consultative
- [ADR-003](docs/adr/ADR-003-postgresql-pgvector-flyway.md): PostgreSQL, pgvector, Flyway
- [ADR-004](docs/adr/ADR-004-deterministic-fake-providers.md): deterministic fake providers
- [ADR-005](docs/adr/ADR-005-api-client-authentication.md): administrator/client authentication
- [ADR-006](docs/adr/ADR-006-source-ingestion-versioning.md): source ingestion/versioning
- [ADR-007](docs/adr/ADR-007-deterministic-chunking.md): deterministic chunking
