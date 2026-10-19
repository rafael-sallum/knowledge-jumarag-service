# ADR-014: Provider resilience and cost guards

## Context

External AI calls can fail, stall, or incur repeated cost. They must not become a readiness dependency or weaken deterministic grounding.

## Decision

Provider calls have bounded HTTP timeouts, no automatic retries, safe error mapping, provider/model/operation metrics, and a small process-local circuit breaker. Question, context, evidence count/size, output tokens, and backfill batch size are bounded. Local `EvidencePolicy` decides answer status, so insufficient or conflicting evidence does not trigger chat generation. Database readiness remains independent of provider reachability.

## Consequences

Failures return retryable, non-sensitive service errors without leaking prompts, evidence, provider bodies, or credentials. The breaker is intentionally per process and conservative. Distributed rate limiting, billing quotas, durable backfill jobs, and multi-replica breaker coordination remain future work.
