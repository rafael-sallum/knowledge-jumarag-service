# knowledge-jumarag-service

`knowledge-jumarag-service` is a reusable, versioned, retrieval-grounded domain knowledge service. Salloon Score is its first planned client, but the core has no Salloon-specific rules and is not a generic chatbot.

Phase 4A adds provider-neutral context orchestration. A Knowledge Base can use focused vector retrieval, a bounded complete snapshot, retrieved anchors plus neighboring chunks, or deterministic automatic selection. Deterministic fake embedding and chat gateways remain the defaults for local development and CI. OpenAI remains opt-in at runtime; no credential is required to build or test the project and no provider call is made unless explicitly selected.

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
                    RETRIEVE evidence          ASK context strategy
                                                   |
                                  RAG / LONG_CONTEXT / HYBRID / AUTO
                                                   |
                                      provider-neutral ChatGateway
                                                   |
                                      validated citation IDs
```

Stack: Java 21, Maven Wrapper 3.9.11, Spring Boot 3.5.16, Spring AI BOM 1.1.8 with its OpenAI adapter, Spring MVC/Validation/JdbcClient/Actuator, springdoc 2.9.1, PostgreSQL 17, pgvector 0.8.6, Flyway, Micrometer/Prometheus, and Testcontainers. SQL is explicit; Flyway alone owns the schema.

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
5. one vector per chunk in the explicitly configured ingestion profile;
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
2. resolves the Knowledge Base context strategy;
3. validates the allowlisted evidence intent and prepares bounded evidence without crossing snapshot/source/language boundaries;
4. evaluates relevance, required source-class diversity, and explicit conflicts deterministically;
5. gives only prepared evidence and allowed citation IDs to `ChatGateway` when the local evidence policy returns `ANSWERED`;
6. validates every generated citation before returning it.

```bash
curl -sS -X POST "$API_ROOT/v1/knowledge-bases/snooker-rules/ask" \
  -H "X-JumaRAG-API-Key: $CLIENT_KEY" -H 'Content-Type: application/json' \
  -d '{
    "question":"When can a free ball be nominated?",
    "language":"en",
    "evidenceIntent":"OFFICIAL_ONLY",
    "contextSchema":"example.match-context.v1",
    "context":{"ruleset":"SNOOKER","profile":"SIX_RED"}
  }'
```

Answer states:

- `ANSWERED`: usable evidence and at least one validated citation;
- `INSUFFICIENT_EVIDENCE`: no qualifying controlled evidence; no invented answer;
- `CONFLICTING_SOURCES`: relevant sources carry the same `assertionKey` with different `assertionValue` metadata; all conflicting evidence is surfaced.

Conflict metadata is a deliberately modest Phase 2 seam, not semantic contradiction detection. Source classification remains visible and no lower-authority source is silently discarded.

### Evidence intent and diversity

`evidenceIntent` is an optional, allowlisted request field. Its default is `GENERAL`, preserving the pre-Phase-4C selection behavior. It is an explicit backend choice rather than an LLM classification or arbitrary source filter:

- `GENERAL`: current relevance-driven selection;
- `OFFICIAL_ONLY`: only `OFFICIAL_RULE` evidence is eligible;
- `IMPLEMENTATION_ONLY`: only `IMPLEMENTATION_NOTE` evidence is eligible;
- `OFFICIAL_AND_IMPLEMENTATION`: at least one relevant item and one validated citation from each class are required.

Comparative RAG embeds the question once, searches each required class with that same vector, and interleaves the results so one class cannot consume the context. LONG_CONTEXT filters and validates the bounded snapshot corpus; HYBRID requires diverse anchors before neighbor expansion; AUTO retains its normal strategy decision but cannot bypass diversity. Missing either comparative side returns `INSUFFICIENT_EVIDENCE` without a chat call. The response exposes `evidenceIntent`, `requiredEvidenceClasses`, `presentEvidenceClasses`, and `evidenceDiversitySatisfied`. Unknown intent values and unknown `/ask` fields are rejected; clients cannot request raw source classes, strategy, provider, or model.

## Context strategies

Context selection is a Knowledge Base policy, not a free-form client request. New bases default to `AUTO`; migration preserves `RAG` for bases created before Phase 4A so rollout does not silently change existing answers. Administrators can change the policy explicitly:

```bash
curl -sS -X PATCH "$API_ROOT/v1/admin/knowledge-bases/$KB_ID/context-strategy" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"contextStrategy":"HYBRID"}'
```

- `RAG`: embeds the question once and reuses the existing snapshot-aware exact-cosine pgvector retrieval. It is focused and usually lowest-context, but depends on retrieval quality.
- `LONG_CONTEXT`: supplies the complete eligible snapshot in deterministic source/version/chunk order and does not generate a query embedding. It is suitable only when the entire language-filtered snapshot fits all configured limits. An oversized snapshot fails explicitly with `LONG_CONTEXT_BUDGET_EXCEEDED`; JumaRAG never presents an arbitrary prefix as complete evidence.
- `HYBRID`: runs RAG once, then adds the configured previous/next chunks from each anchor's same immutable SourceVersion. Anchors determine relevance; neighbors cannot manufacture a positive evidence signal.
- `AUTO`: if the full snapshot fits the long-context budget, selects `LONG_CONTEXT` without an embedding call. Otherwise it performs one retrieval: a strong top similarity selects `RAG`, while a weaker signal selects `HYBRID` using that same result. It never uses an LLM router or duplicates query embedding calls.

Every strategy still enforces the active/published snapshot, source enabled state, Knowledge Base/language isolation, ASK grant, source provenance, bounded context, deterministic evidence status, and allowlisted citations. `/retrieval/search` remains a direct vector retrieval API and does not run context strategies.

The `/ask` response exposes `contextStrategyConfigured`, `contextStrategyUsed`, `contextDecisionReason`, chunk/character/token estimates, and whether the complete snapshot was supplied. Token estimates use a deterministic four-characters-per-token approximation for budgeting; they are calibration values, not provider billing counts. Micrometer records preparation duration, failures, selected strategy, chunks, and estimated tokens without logging evidence.

## Citations and auditability

Citation IDs (`C1`, `C2`, ...) are assigned before generation. The gateway may reference only those IDs. Unknown IDs fail the generation contract and never escape to clients.

Each citation includes Knowledge Base/snapshot/source/version/chunk IDs, chunk ordinal, logical name, title, source classification, language, heading, filename, optional page location, and deterministic location metadata. Every answer also identifies retrieval/embedding profiles, provider-neutral chat provider/model, status, request ID, and optional safe token usage. Full ask history is not persisted.

## Context and grounding contract

Optional context must be a bounded JSON object. Size, nesting depth, schema identifier, and question length are validated. Context keys never become SQL or retrieval filters. Both context and source text are untrusted data, not executable instructions.

The provider-neutral contract requires future adapters to answer only from supplied evidence, surface insufficiency/conflict, ignore embedded instructions, cite only allowlisted IDs, and never expose chain-of-thought.

## Provider selection and vector-space isolation

`EmbeddingGateway` and `ChatGateway` remain application ports. The runtime independently selects fake or OpenAI infrastructure adapters:

```dotenv
JUMARAG_EMBEDDING_PROVIDER=fake
JUMARAG_CHAT_PROVIDER=fake
JUMARAG_DEFAULT_EMBEDDING_PROFILE=phase0-fake-1536
JUMARAG_RETRIEVAL_EMBEDDING_PROFILE=phase0-fake-1536
```

For an explicitly enabled OpenAI development runtime:

```dotenv
OPENAI_API_KEY=<set-locally>
JUMARAG_EMBEDDING_PROVIDER=openai
JUMARAG_CHAT_PROVIDER=openai
JUMARAG_DEFAULT_EMBEDDING_PROFILE=openai-text-embedding-3-small-1536
JUMARAG_RETRIEVAL_EMBEDDING_PROFILE=openai-text-embedding-3-small-1536
JUMARAG_OPENAI_EMBEDDING_MODEL=text-embedding-3-small
JUMARAG_OPENAI_CHAT_MODEL=gpt-6-luna
```

The fake and OpenAI vectors are different vector spaces even though both have 1,536 dimensions. The persisted profiles are distinct, ingestion and retrieval resolve an exact profile, and the SQL query joins by that profile ID. They are never compared together. A snapshot fixes source versions; an embedding profile fixes their vector representation. Those concepts remain independent.

The OpenAI profile is `OPENAI / text-embedding-3-small / 1536 / COSINE`. The adapter requests 1,536 dimensions explicitly and rejects any incompatible response. `gpt-6-luna` is the supported cost-efficient development chat default for short grounded answers and remains runtime-configurable. It is invoked with reasoning effort `none`, strict structured output, and bounded completion tokens. Model support and current pricing were verified against the official [GPT-6 Luna model page](https://developers.openai.com/api/docs/models/gpt-6-luna) and [OpenAI API pricing](https://developers.openai.com/api/docs/pricing) on 2026-10-31; operators must re-verify before production rollout.

Spring AI 1.1.8 provides a clean Chat Completions integration with strict JSON Schema output, which Phase 3 uses behind `ChatGateway`. Its OpenAI adapter does not expose the Responses API cleanly enough for this contract without bypassing Spring AI; a future adapter can adopt Responses without changing the core port.

### Re-embedding existing chunks

Existing immutable chunks can receive another explicit vector profile without re-ingestion:

```bash
curl -sS -X POST "$API_ROOT/v1/admin/knowledge-bases/$KB_ID/embeddings/backfill" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "embeddingProfile":"openai-text-embedding-3-small-1536",
    "snapshotId":"<optional-snapshot-id>",
    "batchSize":25
  }'
```

Backfill is administrator-only, synchronous and bounded to 100 chunks per call. It takes a database advisory lock per Knowledge Base/profile, skips existing vectors, and is idempotent through the existing `(chunk_id, embedding_profile_id)` primary key. Successfully completed vectors commit if a later provider call fails; retry resumes only missing work. Fake vectors, source versions, and snapshots are never changed. Larger corpora require a future durable job runner.

## Provider resilience and cost controls

- OpenAI connect/read calls use the configured bounded timeout (20 seconds by default).
- A local per-process circuit opens after three consecutive failures for 30 seconds by default.
- Spring AI retry attempts are set to one, avoiding hidden multiplication of paid calls.
- provider failures map to safe `503` errors without response bodies, credentials, prompts, evidence, context, or vectors in logs;
- questions, context depth/bytes, retrieved evidence count/characters, output tokens, and backfill batch size are bounded;
- `INSUFFICIENT_EVIDENCE` and `CONFLICTING_SOURCES` are decided locally and do not invoke paid chat generation;
- readiness remains database-based and never depends on OpenAI reachability;
- Micrometer records provider/model/operation outcome and duration; optional token usage is returned without making the public contract provider-specific.

When an OpenAI adapter is selected without `OPENAI_API_KEY`, startup fails clearly. Fake mode boots without the key. Keep the key only in ignored local `.env` or production secret management; never use Docker build arguments or commit credentials. Actuator exposes only health, info, and Prometheus and never environment/configuration endpoints.

## Golden evaluation

The deterministic test harness measures:

- Hit@K-style expected source/heading retrieval;
- expected answer status;
- citation coverage against actually supplied chunks;
- context strategy used, chunk count, estimated tokens, and optional latency.

Fixtures cover an answerable free-ball question, insufficient evidence, and explicit controlled conflicts. Reports identify the embedding profile so the same corpus can be evaluated manually against fake and OpenAI spaces while CI remains deterministic and provider-free.
Phase 4A fixtures additionally compare focused RAG, bounded complete snapshots, neighboring expansion, and deterministic AUTO selection. Hit@K is reported only when retrieval applies.

## Configuration

- `JUMARAG_EMBEDDING_PROVIDER=fake`
- `JUMARAG_CHAT_PROVIDER=fake`
- `JUMARAG_DEFAULT_EMBEDDING_PROFILE=phase0-fake-1536`
- `JUMARAG_RETRIEVAL_EMBEDDING_PROFILE=phase0-fake-1536`
- `JUMARAG_ANSWER_RETRIEVAL_LIMIT=8`
- `JUMARAG_ANSWER_MINIMUM_SIMILARITY=0.05`
- `JUMARAG_ANSWER_MINIMUM_TOKEN_OVERLAP=1`
- `JUMARAG_MAX_QUESTION_CHARACTERS=4000`
- `JUMARAG_MAX_CONTEXT_BYTES=8192`
- `JUMARAG_MAX_CONTEXT_DEPTH=8`
- `JUMARAG_OPENAI_MAX_EVIDENCE_CHUNKS=6`
- `JUMARAG_OPENAI_MAX_EVIDENCE_CHARACTERS=12000`
- `JUMARAG_RAG_MAX_CHUNKS=6`
- `JUMARAG_RAG_MAX_CHARACTERS=12000`
- `JUMARAG_RAG_MAX_ESTIMATED_TOKENS=3000`
- `JUMARAG_LONG_CONTEXT_MAX_CHUNKS=24`
- `JUMARAG_LONG_CONTEXT_MAX_CHARACTERS=24000`
- `JUMARAG_LONG_CONTEXT_MAX_ESTIMATED_TOKENS=6000`
- `JUMARAG_HYBRID_NEIGHBOR_CHUNKS=1`
- `JUMARAG_HYBRID_MAX_CHUNKS=12`
- `JUMARAG_HYBRID_MAX_CHARACTERS=18000`
- `JUMARAG_HYBRID_MAX_ESTIMATED_TOKENS=4500`
- `JUMARAG_AUTO_STRONG_SIMILARITY=0.35`
- `JUMARAG_OPENAI_TIMEOUT_SECONDS=20`
- `JUMARAG_OPENAI_MAX_OUTPUT_TOKENS=256`
- `JUMARAG_PROVIDER_FAILURE_THRESHOLD=3`
- `JUMARAG_PROVIDER_CIRCUIT_OPEN_SECONDS=30`

Never log client/admin secrets, question context, full evidence bodies, source bodies, or embedding vectors.

## Salloon knowledge onboarding

Salloon Score is the first real domain onboarded through controlled content rather than runtime coupling. Phase 4B defines two independent Knowledge Bases:

- `salloon-snooker-rules` for the current WPBSA rulebook and Salloon Snooker implementation notes;
- `salloon-brazilian-rules` for CBBS Sinuca rules, the complementary billiards regulation and Salloon Brazilian implementation notes.

Authoritative third-party documents are not committed. Reviewed manifests contain canonical URLs, edition/revision, retrieval date and SHA-256. A controlled preparation script extracts locally acquired artifacts into ignored `.local/knowledge-sources`; the existing TEXT/Markdown ingestion API then creates immutable source versions and embeddings. First-party implementation notes cite the read-only Salloon engine/tests and label matches, specializations, simplifications, differences and unresolved reviews explicitly.

The onboarding package includes 30 evidence-oriented golden questions with explicit evidence intents, an idempotent bootstrap, a bounded strategy-comparison script, `salloon.match-context.v1`, the planned `Salloon Score Backend` client profile, and the future backend-to-backend contract. Comparative official-versus-implementation questions cannot be reported as grounded unless both sides are present and cited. See [the onboarding guide](knowledge/onboarding/salloon/README.md). Live Salloon API/frontend integration remains deferred.

## Current limitations

OpenAI is the only real adapter. The service has no multi-provider routing, usage quotas, durable high-volume embedding jobs, provider-backed semantic conflict detection, or production provider health probe. The circuit breaker is process-local, appropriate for initial single-replica operation.

Deferred: Salloon integration, Ollama/Gemini/Anthropic/Azure, Responses API migration, hybrid FTS/RRF, reranking, semantic conflict detection, PDF/DOCX/HTML/OCR, URL/sitemap ingestion, durable async jobs/batching, automatic snapshot assembly, admin UI, quotas/billing, public exposure, and end-user identity.

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
- [ADR-012](docs/adr/ADR-012-provider-neutral-real-ai.md): provider-neutral real AI adapters
- [ADR-013](docs/adr/ADR-013-embedding-profile-isolation.md): vector-space identity and safe backfill
- [ADR-014](docs/adr/ADR-014-provider-resilience-and-cost-guards.md): failure isolation and bounded cost
- [ADR-015](docs/adr/ADR-015-context-strategy-orchestration.md): provider-neutral context strategy orchestration
- [ADR-016](docs/adr/ADR-016-salloon-knowledge-onboarding.md): controlled Salloon knowledge onboarding
- [ADR-017](docs/adr/ADR-017-evidence-intent-and-diversity.md): explicit evidence intent and source-class diversity
