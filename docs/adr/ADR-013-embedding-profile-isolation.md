# ADR-013: Embedding profile isolation

## Context

The existing deterministic fake vectors and real OpenAI vectors both contain 1,536 values but belong to incompatible vector spaces. Dimension equality does not make them comparable.

## Decision

Every embedding operation resolves one persisted profile with exact provider, model, dimensions, and metric. Ingestion produces only its explicitly configured profile. Retrieval compares only the exact configured profile. The OpenAI profile is `openai-text-embedding-3-small-1536`; existing fake vectors remain under `phase0-fake-1536`. A bounded administrator backfill adds missing vectors to existing immutable chunks and never rewrites source versions or snapshots.

## Consequences

Fake and real vectors can coexist safely under the existing composite primary key. Profile mismatch fails before a provider call or SQL search. Backfill is resumable and idempotent but intentionally synchronous for small Phase 3 corpora; durable large-scale jobs remain deferred.
