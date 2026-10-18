# ADR-003: PostgreSQL, pgvector, and Flyway-owned schema

## Context

Source provenance is relational while embeddings require similarity search. Reproducible schema ownership is required across API and worker runtimes.

## Decision

Use PostgreSQL 17 with pgvector. Store Phase-0 embeddings as `vector(1536)` and execute exact cosine-distance queries in PostgreSQL. Flyway is the only schema owner; runtime schema auto-creation is disabled.

## Consequences

Relational and vector consistency share transactions and operations. Migrations must run before API/worker. Approximate indexes are deferred until corpus size and query plans justify them.
