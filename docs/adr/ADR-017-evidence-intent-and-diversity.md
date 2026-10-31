# ADR-017: Explicit evidence intent and source-class diversity

## Context

Semantic similarity alone is not enough for comparative questions. Phase 4B showed a Brazilian official-versus-Salloon question for which RAG, HYBRID and AUTO selected only implementation notes. The generated answer was cited, but it was not grounded on both sides of the requested comparison. Asking an LLM to infer intent or judge completeness would make this safety property nondeterministic.

## Decision

- Add an optional allowlisted evidence intent: `GENERAL`, `OFFICIAL_ONLY`, `IMPLEMENTATION_ONLY`, or `OFFICIAL_AND_IMPLEMENTATION`.
- Default missing intent to `GENERAL` for API compatibility.
- Map intent to small generic classification groups (`OFFICIAL_RULE` and `IMPLEMENTATION_NOTE`) in a core policy, not Salloon-specific controller code.
- For comparative intent, select both groups before generation, reuse one query embedding, reserve context for both groups, and require both groups to pass relevance rules.
- Require generated comparative citations to cover both groups. Local `EvidencePolicy` and `CitationValidator`, not the model, decide completeness.
- Apply the same intent contract to RAG, LONG_CONTEXT, HYBRID and AUTO without allowing clients to select a raw class, strategy, provider, or model.

## Consequences

Comparative answers fail safely as `INSUFFICIENT_EVIDENCE` when either side is unavailable. Requests and responses carry auditable diversity metadata. Additional source-class groups require deliberate policy changes rather than arbitrary request values. Comparative retrieval performs multiple filtered SQL searches but only one embedding call. This is not a general natural-language intent classifier or policy DSL.
