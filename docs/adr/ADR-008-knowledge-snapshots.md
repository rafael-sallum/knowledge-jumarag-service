# ADR-008: Immutable knowledge snapshots

## Context

Searching every `READY` source version makes results change whenever ingestion adds a version and cannot reproduce a prior answer.

## Decision

Persist immutable snapshots containing exactly one ready version per logical source. Activation is explicit, serialized by a Knowledge Base row lock, and protected by a partial unique index allowing one active snapshot per Knowledge Base. Previous snapshots are retired, not deleted; reactivation provides rollback. Client retrieval defaults to the active snapshot and may address a published historical snapshot explicitly.

## Consequences

Answers identify their exact corpus. Ingestion alone no longer changes client retrieval. Administrators must create and activate a snapshot before clients can retrieve or ask.
