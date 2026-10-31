# ADR-016: Salloon knowledge onboarding

## Context

Salloon Score is JumaRAG's first real client domain. It needs authoritative cue-sports evidence and an honest description of the deterministic product implementation. Official rulebooks are copyrighted third-party artifacts, and JumaRAG intentionally does not yet contain generic PDF/Word ingestion.

## Decision

- Maintain separate Snooker and Brazilian Sinuca Knowledge Bases.
- Represent governing-body material as `OFFICIAL_RULE` and Juma Tech-owned implementation notes as `IMPLEMENTATION_NOTE`.
- Commit source manifests, provenance and checksums, but keep downloaded/extracted third-party content under ignored `.local/knowledge-sources`.
- Use a small operational preparation script for this controlled onboarding; do not add document parsing to the runtime core.
- Build immutable active snapshots from the exact official and implementation SourceVersions.
- Evaluate evidence/citation correctness with real domain questions across all context strategies.
- Keep the proposed Salloon context DTO PII-free, seat-based and consultative.

## Consequences

Answers can distinguish official rules from current product behavior and can be reproduced against a snapshot. Source updates require explicit review and a new immutable version/snapshot. A deployment operator must acquire authoritative artifacts separately. General PDF/DOCX/crawler support and live Salloon integration remain later work.

