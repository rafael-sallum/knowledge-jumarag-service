# ADR-012: Provider-neutral real AI adapters

## Context

The first production-capable provider is OpenAI, but retrieval and grounded-answer orchestration must remain reusable and deterministic in CI.

## Decision

Application code continues to depend only on `EmbeddingGateway` and `ChatGateway`. Infrastructure selects fake or OpenAI implementations independently through runtime configuration. The OpenAI adapters use Spring AI 1.1.8; chat uses its clean Chat Completions integration with strict JSON Schema because this Spring AI line does not expose the Responses API cleanly enough for the existing contract. Fake/fake remains the default.

## Consequences

CI needs no provider credentials or network. OpenAI models and timeouts are configurable. Another provider or a future Responses API adapter can be added without changing ingestion, retrieval, evidence policy, or citation validation. Selecting OpenAI requires a runtime secret and fails fast if it is absent.
