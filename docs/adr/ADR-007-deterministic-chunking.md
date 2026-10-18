# ADR-007: Deterministic structure-aware chunking

## Context

Phase 1 needs useful evidence units and future citation provenance without a paid or provider-specific tokenizer.

## Decision

Use deterministic approximate word windows with configurable target, maximum, and overlap. Boundaries prefer complete paragraphs when they fit below the hard maximum; oversized paragraphs are split deterministically. Markdown headings form a hierarchical path included in chunk context. Each chunk stores its ordinal, SHA-256, heading path, language, and word-range metadata. Defaults target 650 words, cap at 900, and overlap 100.

## Consequences

The same normalized source/configuration produces the same chunks, tests remain provider-independent, and future citations have structural anchors. Token counts are approximate; a provider tokenizer can be introduced behind `ChunkingService` if evaluation justifies it.
