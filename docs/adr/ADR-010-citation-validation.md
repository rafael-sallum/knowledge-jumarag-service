# ADR-010: Preassigned and validated citations

## Context

Generated citation labels can be fabricated unless they are bound to retrieved provenance before answer generation.

## Decision

The application assigns stable request-local IDs (`C1`, `C2`, ...) to selected chunks. `ChatGateway` may return only those identifiers. Post-generation validation rejects unknown identifiers and requires at least one valid citation for `ANSWERED`. Response citations are reconstructed from persisted provenance, not provider text.

## Consequences

Hallucinated references cannot reach clients. Citations remain machine-readable and reproducible against a snapshot. Claim-level grounding can be added later without changing provenance identity.
