# ADR-004: Deterministic fake AI providers in CI

## Context

Normal builds must be reliable, free of paid-provider calls, and independent of network credentials.

## Decision

Use an `EmbeddingGateway` boundary and a deterministic 1536-dimensional fake implementation for local development and CI. It uses stable token hashing and normalization, not a remote model. Real Spring AI provider adapters come in a later phase.

## Consequences

Similarity tests are repeatable and exercise the real pgvector path. Fake embeddings demonstrate architecture, not semantic production quality, and must never be presented as a real model.
