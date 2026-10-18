# ADR-002: AI is consultative

## Context

Domain systems such as Salloon Score already have deterministic engines responsible for canonical decisions and state transitions.

## Decision

Retrieval and future generated answers explain controlled knowledge; they never mutate or replace an authoritative domain state. Salloon remains authoritative for scoring, turns, fouls, completion, control, and synchronization.

## Consequences

Future client context is evidence for explanation only. Any mutation API, tool execution, or model-driven sporting decision is outside this service boundary.
