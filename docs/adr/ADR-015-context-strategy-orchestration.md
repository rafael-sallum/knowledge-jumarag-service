# ADR-015: Context strategy orchestration

## Context

Classic vector retrieval is useful for large corpora, but a small controlled snapshot may fit safely in a model context and some retrieved chunks need their immediate neighbors to preserve meaning. Long model context therefore changes the available tradeoffs but does not eliminate retrieval, budgets, provenance, or grounding.

## Decision

JumaRAG owns a provider-neutral `ContextStrategy` layer with `RAG`, `LONG_CONTEXT`, `HYBRID`, and deterministic `AUTO` implementations. Strategy is configured per Knowledge Base. Existing bases retain RAG during migration; new bases default to AUTO. Clients cannot override it freely.

RAG reuses one snapshot-aware vector search. LONG_CONTEXT uses the complete eligible snapshot only when all configured budgets fit and performs no query embedding. HYBRID reuses RAG anchors and adds bounded adjacent chunks from the same snapshot SourceVersion. AUTO selects LONG_CONTEXT from snapshot size alone, otherwise reuses one retrieval and chooses RAG for a strong signal or HYBRID for expansion.

Snapshot membership, enabled sources, language, grants, evidence status, context budgets, provenance, and citation allowlisting remain strategy-independent. Expanded neighbors do not count as relevance anchors. The direct retrieval endpoint remains retrieval-only.

## Consequences

Context selection can evolve independently of embedding/chat providers and exposes measurable reason codes, chunk counts, token estimates, latency, and metrics. Complete-snapshot requests fail honestly when oversized rather than silently truncating. AUTO is predictable and testable but initially uses a calibrated similarity threshold, not a semantic router. Per-user strategy choice, LLM routing, dynamic planning, and provider-specific context optimization remain deferred.
