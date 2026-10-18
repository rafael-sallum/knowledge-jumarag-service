# ADR-006: Immutable, checksum-idempotent source ingestion

## Context

Controlled knowledge must be reproducible, concurrent retries must not duplicate history, and retrieval must never observe partially embedded content.

## Decision

Normalize content and identify a logical-source version with SHA-256 over normalized UTF-8. Enforce uniqueness on `(source_id, checksum)`. Content is immutable; status may move through `PROCESSING`, `READY`, or `FAILED`. Chunk/embedding persistence and `READY` share a transaction. Failure rolls that transaction back and records only a safe failure classification separately.

## Consequences

Identical retries converge, changed content creates history, and only complete versions are retrievable. A crash between registration and processing may leave `PROCESSING`; durable stale-job recovery belongs with a later PostgreSQL job system rather than an in-memory queue.
