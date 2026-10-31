# Salloon knowledge onboarding

Phase 4B makes Salloon Score the first real knowledge domain without coupling JumaRAG's runtime core to Salloon.

## Knowledge Bases

- `salloon-snooker-rules`: WPBSA official rules plus separately labeled Salloon Snooker implementation notes.
- `salloon-brazilian-rules`: CBBS Sinuca rules, the complementary billiards regulation, and separately labeled Salloon implementation notes.

`OFFICIAL_RULE` and `IMPLEMENTATION_NOTE` are distinct logical sources and immutable source versions. A snapshot records the exact versions used by answers.

## Copyright-safe source workflow

The repository contains manifests, first-party implementation notes and evaluation cases. It does not contain WPBSA/CBBS rulebooks or extracted copies. Acquire the exact manifest URLs into `.local/knowledge-sources/{snooker,brazilian}`, verify their hashes, then run:

```bash
ops/onboarding/salloon/prepare-sources.sh
```

The script checks SHA-256 before extraction. It creates ingestion-ready Markdown only under ignored `.local/knowledge-sources`. PDF pages become deterministic headings. The legacy CBBS Word files do not expose reliable page boundaries, so their prepared content preserves article/section text without invented page numbers.

If an upstream checksum changes, stop. Review the new official edition and add a new manifest/source version rather than replacing provenance silently.

## Idempotent bootstrap

With an API configured for the desired explicit embedding profile:

```bash
JUMARAG_ADMIN_TOKEN='<set-locally>' \
JUMARAG_API_ROOT='http://127.0.0.1:8080' \
ops/onboarding/salloon/bootstrap.sh
```

The bootstrap resolves or creates both KBs, ingests official and implementation sources idempotently, creates the immutable `salloon-*-rules-v1` snapshots, verifies an existing snapshot's item set, and activates it. It fails rather than mutating a same-named snapshot with different versions. Set a new snapshot name for a reviewed source update.

For real calibration, configure ingestion/retrieval as `openai-text-embedding-3-small-1536`. Fake remains the CI/default profile. The raw OpenAI and JumaRAG credentials never belong in this repository or command history.

## Golden evaluation

The two evaluation files contain 30 evidence-oriented cases. They test official rules, Salloon behavior, explicit differences and unsupported geometry advice without requiring exact generated prose. Every case declares `evidenceIntent`: official questions use `OFFICIAL_ONLY`, product-behavior questions use `IMPLEMENTATION_ONLY`, comparative questions use `OFFICIAL_AND_IMPLEMENTATION`, and unsupported/general cases may use `GENERAL`.

`compare-strategies.sh` runs a deliberately bounded subset across RAG, LONG_CONTEXT, HYBRID and AUTO and writes safe JSONL metrics under ignored `.local/knowledge-sources/evaluation`. Its default is four cases per KB; increase only deliberately.

```bash
JUMARAG_ADMIN_TOKEN='<set-locally>' \
JUMARAG_CLIENT_KEY='<ASK-client-key>' \
ops/onboarding/salloon/compare-strategies.sh \
  salloon-snooker-rules \
  knowledge/onboarding/salloon/snooker/evaluation/golden-cases.json
```

The script changes the KB strategy during calibration. Use only an isolated development/staging database and restore the reviewed strategy afterward.

The bounded Phase 4B results and Phase 4C before/after release gate are recorded in `evaluation-results.md`. Comparative answers now require relevant evidence and valid citations from both classifications before generation; a missing side yields `INSUFFICIENT_EVIDENCE`.

## Future integration boundary

The proposed `salloon.match-context.v1` is PII-free and seat-based. Only allowlisted ruleset/profile fields may become retrieval filters; sporting state remains untrusted answer context. The planned client is `Salloon Score Backend`, with `ASK` grants only for both Salloon KBs and no production credential stored here. See `context/field-mapping.md`, `integration/client-profile.json`, and `integration/backend-contract.md`.

Salloon remains authoritative for scoring, legal transitions, control and persistence. JumaRAG failure must never block sporting operation.

