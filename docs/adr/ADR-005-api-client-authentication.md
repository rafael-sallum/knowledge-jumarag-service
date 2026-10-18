# ADR-005: Separate administrator and API-client credentials

## Context

Phase 0 exposed proof APIs without a production security boundary. Administration has broader authority than future client evidence retrieval, and clients must be isolated by knowledge base.

## Decision

Admin routes use a constant-time compared bearer secret supplied by configuration. Client routes use high-entropy `jrag_<public-id>_<secret>` keys. Only the public identifier and an HMAC-SHA-256 digest keyed by an independent server-side pepper are persisted. Each enabled client needs an explicit `RETRIEVE` grant for a knowledge base. Raw keys are returned only at creation.

## Consequences

Credentials are revocable and clients have least privilege without coupling to end-user identity. Admin and client key rotation are operational tasks. OAuth2 client credentials, mTLS, or workload identity may replace service secrets later without changing grants.
