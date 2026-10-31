# Salloon onboarding production-readiness checklist

Before deployment/integration:

- acquire official artifacts from the manifest URLs and verify SHA-256 or consciously approve a new immutable source version;
- review extraction quality and section boundaries;
- reconcile every `REQUIRES_REVIEW` implementation note;
- configure production PostgreSQL/pgvector, backups and restore tests;
- inject admin/provider secrets through deployment secret management;
- create a dedicated Salloon API client, rotate/store its raw key once, and grant only `ASK` to the two KBs;
- run the idempotent bootstrap against the production API and record the activated snapshot IDs;
- configure network allowlisting/TLS and keep PostgreSQL private;
- validate liveness/readiness, provider timeout/circuit-breaker and safe logs;
- establish request rate limits and operational token/cost budgets;
- run the approved golden subset against the production provider configuration;
- define source-update monitoring and snapshot rollback procedure;
- keep JumaRAG failure isolated from all Salloon sporting workflows.

