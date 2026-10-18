# knowledge-jumarag-service

`knowledge-jumarag-service` is a reusable, versioned, retrieval-grounded domain knowledge service. Salloon Score is its first planned client, but the core has no Salloon-specific rules and is not a generic chatbot.

Phase 2 adds immutable knowledge snapshots and deterministic grounded answers with validated citations. It uses fake embedding and chat gateways only: there is **no external AI provider, paid credential, model network call, PDF/crawler support, or Salloon integration**.

## Boundary and stack

AI remains consultative. Client domain systems remain authoritative for their state and decisions.

```text
 admin bearer token                              client API key + KB grant
         |                                                   |
 ingestion -> immutable SourceVersions -> immutable Snapshot |
                                      active/retired selection
         |                                                   |
         +---- exact snapshot-filtered pgvector retrieval ---+
                                                             |
                    RETRIEVE evidence          ASK grounded answer
                                                   |
                                      deterministic ChatGateway
                                                   |
                                      validated citation IDs
```

Stack: Java 21, Maven Wrapper 3.9.11, Spring Boot 3.5.16, Spring AI BOM 1.1.8, Spring MVC/Validation/JdbcClient/Actuator, springdoc 2.9.1, PostgreSQL 17, pgvector 0.8.6, Flyway, Micrometer/Prometheus, and Testcontainers. SQL is explicit; Flyway alone owns the schema.

## Build and local stack

```bash
./mvnw test
./mvnw verify
cp .env.example .env
# Replace example secrets.
docker compose up --build --wait
```

Compose starts PostgreSQL healthy, runs one-shot migrations, then starts separate API and worker runtimes. Local bindings are API `8080`, worker health `8081`, and PostgreSQL `55433`. Production keeps PostgreSQL private.

- OpenAPI: `/v3/api-docs`
- Swagger: `/swagger-ui.html`
- liveness: `/actuator/health/liveness`
- readiness: `/actuator/health/readiness`
- metrics: `/actuator/prometheus`

## Security and grants

- `/v1/admin/**` requires `Authorization: Bearer <JUMARAG_ADMIN_TOKEN>`.
- Client endpoints require `X-JumaRAG-API-Key: jrag_<public-id>_<secret>`.
- Raw client keys are returned once; PostgreSQL stores only the public prefix and a peppered HMAC-SHA-256 digest.
- `RETRIEVE` permits direct evidence search.
- `ASK` permits `/ask` and its internal retrieval, but does not permit direct `/retrieval/search`.
- Grants are explicit per client and Knowledge Base. Neither permission grants administration.

The administrator credential is intentionally a replaceable service secret. Workload IAM/OAuth2/mTLS, rate limiting, key rotation APIs, and public exposure remain deferred.

```bash
# Create a client; apiKey appears only in this response.
curl -sS -X POST "$API_ROOT/v1/admin/api-clients" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"example-backend","metadata":{"owner":"example"}}'

# Grants are independent. Omitting permission remains backward-compatible RETRIEVE.
curl -sS -X POST "$API_ROOT/v1/admin/api-clients/$CLIENT_ID/grants" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"knowledgeBaseId\":\"$KB_ID\",\"permission\":\"ASK\"}"
```

## Ingestion and immutable versions

Phase 1 TEXT/TXT/Markdown ingestion remains unchanged:

1. normalize Unicode/line endings/whitespace deterministically;
2. SHA-256 normalized UTF-8 content;
3. create one immutable version per `(source_id, checksum)`;
4. deterministic paragraph/Markdown-aware chunking;
5. one `phase0-fake-1536` vector per chunk;
6. publish `READY` only after chunks and embeddings commit.

Repeated identical content is idempotent. Changed content creates another immutable version. Failed processing cannot become retrievable.

## Knowledge snapshots

A snapshot is the exact immutable set of SourceVersions used for retrieval and answers. It contains at most one version per logical source; every version must be `READY`, enabled, and belong to the same Knowledge Base.

Snapshots transition through:

- `DRAFT`: created but unavailable to clients;
- `ACTIVE`: default for retrieval and answers;
- `RETIRED`: previously published and available when explicitly selected.

There is at most one active snapshot per Knowledge Base. Activation locks the Knowledge Base, retires the prior active snapshot, and activates the selected snapshot transactionally. Rollback is reactivation of an older snapshot; source versions and snapshot items never change.

```bash
curl -sS -X POST "$API_ROOT/v1/admin/knowledge-bases/$KB_ID/snapshots" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"rules-2026-10-18","sourceVersionIds":["<version-a>","<version-b>"],"metadata":{"release":"2026-10-18"}}'

curl -sS -X POST "$API_ROOT/v1/admin/knowledge-bases/$KB_ID/snapshots/$SNAPSHOT_ID/activate" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Admin routes also list and get snapshots. A disabled source cannot be activated. A partial unique PostgreSQL index and Knowledge Base row lock enforce one active snapshot under concurrency.

## Snapshot-aware retrieval

Client retrieval searches only the active snapshot by default. It never searches every `READY` version. A published `snapshotId` may be supplied to reproduce a historical result; DRAFT or cross-KB snapshots are rejected.

```bash
curl -sS -X POST "$API_ROOT/v1/knowledge-bases/snooker-rules/retrieval/search" \
  -H "X-JumaRAG-API-Key: $CLIENT_KEY" -H 'Content-Type: application/json' \
  -d '{"query":"When is there a free ball?","language":"en","limit":5}'
```

The response exposes the Knowledge Base, snapshot, retrieval profile, embedding profile, ranked chunks, and cosine similarities. Thresholds used by answer sufficiency are calibration values, not probabilities.

## Grounded `/ask`

`POST /v1/knowledge-bases/{slug}/ask` requires an `ASK` grant. The pipeline:

1. resolves the active or requested published snapshot;
2. creates a deterministic fake query embedding;
3. performs exact pgvector search inside the snapshot;
4. evaluates evidence sufficiency and explicit conflicts deterministically;
5. gives only selected evidence and allowed citation IDs to `ChatGateway`;
6. validates every generated citation before returning it.

```bash
curl -sS -X POST "$API_ROOT/v1/knowledge-bases/snooker-rules/ask" \
  -H "X-JumaRAG-API-Key: $CLIENT_KEY" -H 'Content-Type: application/json' \
  -d '{
    "question":"When can a free ball be nominated?",
    "language":"en",
    "contextSchema":"example.match-context.v1",
    "context":{"ruleset":"SNOOKER","profile":"SIX_RED"}
  }'
```

Answer states:

- `ANSWERED`: usable evidence and at least one validated citation;
- `INSUFFICIENT_EVIDENCE`: no qualifying controlled evidence; no invented answer;
- `CONFLICTING_SOURCES`: relevant sources carry the same `assertionKey` with different `assertionValue` metadata; all conflicting evidence is surfaced.

Conflict metadata is a deliberately modest Phase 2 seam, not semantic contradiction detection. Source classification remains visible and no lower-authority source is silently discarded.

## Citations and auditability

Citation IDs (`C1`, `C2`, ...) are assigned before generation. The gateway may reference only those IDs. Unknown IDs fail the generation contract and never escape to clients.

Each citation includes Knowledge Base/snapshot/source/version/chunk IDs, chunk ordinal, logical name, title, source classification, language, heading, filename, optional page location, and deterministic location metadata. Every answer also identifies retrieval/embedding profiles, fake chat provider/model, status, and request ID. Full ask history is not persisted.

## Context and grounding contract

Optional context must be a bounded JSON object. Size, nesting depth, schema identifier, and question length are validated. Context keys never become SQL or retrieval filters. Both context and source text are untrusted data, not executable instructions.

The provider-neutral contract requires future adapters to answer only from supplied evidence, surface insufficiency/conflict, ignore embedded instructions, cite only allowlisted IDs, and never expose chain-of-thought.

## Golden evaluation

The deterministic test harness measures:

- Hit@K-style expected source/heading retrieval;
- expected answer status;
- citation coverage against actually retrieved chunks.

Fixtures cover an answerable free-ball question, insufficient evidence, and explicit controlled conflicts. This becomes the comparison baseline for future providers without letting CI call them.

## Configuration

- `APP_CHAT_PROVIDER=fake`
- `JUMARAG_ANSWER_RETRIEVAL_LIMIT=8`
- `JUMARAG_ANSWER_MINIMUM_SIMILARITY=0.05`
- `JUMARAG_ANSWER_MINIMUM_TOKEN_OVERLAP=1`
- `JUMARAG_MAX_QUESTION_CHARACTERS=4000`
- `JUMARAG_MAX_CONTEXT_BYTES=8192`
- `JUMARAG_MAX_CONTEXT_DEPTH=8`

Never log client/admin secrets, question context, full evidence bodies, source bodies, or embedding vectors.

## Current limitations

Deferred: OpenAI/Ollama/Gemini/Anthropic/Azure adapters, real `ChatModel`/`EmbeddingModel`, hybrid FTS/RRF, reranking, semantic conflict detection, PDF/DOCX/HTML/OCR, URL/sitemap ingestion, durable async jobs, automatic snapshot assembly, admin UI, quotas/billing, public exposure, end-user identity, and Salloon integration.

## Architecture decisions

- [ADR-001](docs/adr/ADR-001-standalone-service.md): standalone service
- [ADR-002](docs/adr/ADR-002-consultative-ai.md): consultative AI boundary
- [ADR-003](docs/adr/ADR-003-postgresql-pgvector-flyway.md): PostgreSQL/pgvector/Flyway
- [ADR-004](docs/adr/ADR-004-deterministic-fake-providers.md): deterministic fake providers
- [ADR-005](docs/adr/ADR-005-api-client-authentication.md): administrator/client authentication
- [ADR-006](docs/adr/ADR-006-source-ingestion-versioning.md): source versioning
- [ADR-007](docs/adr/ADR-007-deterministic-chunking.md): deterministic chunking
- [ADR-008](docs/adr/ADR-008-knowledge-snapshots.md): immutable reproducible snapshots
- [ADR-009](docs/adr/ADR-009-grounded-answer-generation.md): provider-neutral grounded answers
- [ADR-010](docs/adr/ADR-010-citation-validation.md): allowlisted citations
- [ADR-011](docs/adr/ADR-011-golden-evaluation.md): deterministic golden evaluation
