# ADR-011: Deterministic golden evaluation

## Context

Future embedding and chat providers need a stable baseline for retrieval and grounding quality, while normal CI must not call external or paid APIs.

## Decision

Maintain deterministic fixtures and a small evaluation harness measuring expected Hit@K-style source/heading retrieval, answer status, and citation coverage. Phase 2 uses fake gateways and real PostgreSQL/pgvector in Testcontainers.

## Consequences

Provider experiments can be compared with the same cases. The initial corpus is intentionally small and does not claim general semantic-quality coverage.
