# ADR-009: Provider-neutral grounded answer generation

## Context

The service needs an answer boundary without coupling application orchestration to a live provider or allowing generated content to become domain authority.

## Decision

`AnswerService` resolves authorization and snapshots, retrieves controlled evidence, determines sufficiency/conflict deterministically, and calls a provider-neutral `ChatGateway`. Phase 2 supplies only a deterministic fake gateway. The gateway receives evidence and allowlisted citation identifiers, never persistence or domain mutation capabilities.

## Consequences

CI is deterministic, offline, and free. A future provider adapter can be evaluated without changing snapshot, authorization, retrieval, or citation rules. Answers remain consultative.
