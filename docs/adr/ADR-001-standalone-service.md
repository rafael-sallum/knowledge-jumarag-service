# ADR-001: Standalone service and repository

## Context

The first planned client is Salloon Score, but controlled retrieval, source provenance, and ingestion are reusable capabilities with their own release and scaling lifecycle.

## Decision

Build `knowledge-jumarag-service` as an independently versioned Java service and repository. Clients integrate over authenticated APIs rather than importing service internals.

## Consequences

The service can evolve independently and serve multiple knowledge domains. Deployment, authentication, contracts, and observability must be operated explicitly instead of relying on a client's runtime.
