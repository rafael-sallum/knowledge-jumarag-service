# Production deployment

This runbook prepares the first private JumaRAG deployment on the Salloon VPS. It does not expose JumaRAG publicly and does not couple its lifecycle or database to `/opt/salloon-score`.

## Topology and persistent layout

The Compose project is `jumarag` and contains `postgres`, one-shot `migrate`, `api`, and `worker`. PostgreSQL joins only the internal `jumarag-internal` network. The API also joins the pre-created external network `rag-client` under the alias `jumarag-api`. The worker joins a separate project-owned `jumarag-provider-egress` bridge for outbound provider access, but never joins `rag-client`. Neither PostgreSQL nor the API/worker publishes a host port in the base production Compose file.

Use this persistent layout:

```text
/opt/jumarag/
├── current -> releases/<git-sha>
├── releases/
├── infra/.env
├── backups/
└── knowledge/sources/
    ├── snooker/
    └── brazilian/
```

Releases are immutable checkouts or archives. The environment, database volume, backups, and controlled source artifacts live outside releases. The named Docker volume is `jumarag-postgres-data`; the database network is `jumarag-internal`. `jumarag-provider-egress` has no published service and exists only so worker provider calls can leave an otherwise isolated database network.

## One-time VPS preparation

As an operator with Docker access:

```bash
sudo install -d -m 755 /opt/jumarag/releases
sudo install -d -m 700 /opt/jumarag/infra /opt/jumarag/backups /opt/jumarag/knowledge/sources
docker network inspect rag-client >/dev/null 2>&1 || docker network create rag-client
```

Log in to GHCR with a GitHub token that has only the package-read access required by the private package:

```bash
printf '%s' "$GHCR_READ_TOKEN" | docker login ghcr.io -u rafael-sallum --password-stdin
```

Copy `ops/production/jumarag.env.example` to `/opt/jumarag/infra/.env`, replace every placeholder, and set mode `600`. Do not copy a development OpenAI key. Create a dedicated production OpenAI project/service-account key, keep it only in the persistent environment, and set both providers to `openai`.

The initial JVM recommendations in the example are starting points, not hard container limits: API `-Xms256m -Xmx768m`, worker `-Xms256m -Xmx1024m`, and migration `-Xms128m -Xmx512m`. Observe total VPS memory, native JVM overhead, PostgreSQL, and ingestion peaks before imposing Compose limits.

## Immutable image and release selection

The `Publish immutable image` workflow runs only after CI succeeds on `main` and publishes:

```text
ghcr.io/rafael-sallum/knowledge-jumarag-service:<40-character-git-sha>
```

It never publishes `latest`. Install the matching repository archive at `/opt/jumarag/releases/<sha>`, make it read-only to normal deployment users, then update the symlink atomically:

```bash
ln -sfn /opt/jumarag/releases/<sha> /opt/jumarag/current
```

Set `JUMARAG_IMAGE` in the persistent environment to that exact SHA tag (or a verified image digest).

## Preflight and first startup

From the selected release:

```bash
cd /opt/jumarag/current
ops/production/preflight.sh /opt/jumarag/infra/.env
docker compose --env-file /opt/jumarag/infra/.env -f compose.production.yml pull
docker compose --env-file /opt/jumarag/infra/.env -f compose.production.yml \
  up -d postgres migrate api worker
docker compose --env-file /opt/jumarag/infra/.env -f compose.production.yml wait migrate
```

Preflight rejects missing values/placeholders, mutable image references, fake production providers, a missing shared network, public PostgreSQL/API port mappings, or enabled API docs. Migration must exit successfully before API and worker startup; it is safe to rerun.

Health checks use the actual Spring endpoints:

- liveness: `/actuator/health/liveness`
- readiness: `/actuator/health/readiness`

Inspect status and logs without printing the environment:

```bash
docker compose --env-file /opt/jumarag/infra/.env -f compose.production.yml ps
docker compose --env-file /opt/jumarag/infra/.env -f compose.production.yml logs --tail=200 api worker
```

The API is reachable from the shared network as `http://jumarag-api:8080`. Swagger/OpenAPI is disabled in production. The worker exposes readiness only inside its private network and is currently the explicit runtime boundary for future durable ingestion jobs.

## Salloon knowledge bootstrap

Copyrighted rulebooks are never copied into Git or the image. Supply these exact files separately on the VPS:

```text
/opt/jumarag/knowledge/sources/snooker/wpbsa-rulebook-2024-25.pdf
/opt/jumarag/knowledge/sources/brazilian/cbbs-regras-sinuca-2012.doc
/opt/jumarag/knowledge/sources/brazilian/cbbs-regulamento-esportes-bilhar-2013.doc
```

The committed manifests define their expected SHA-256 values. A mismatch stops the operation and requires a reviewed new source version; it must never overwrite provenance silently.

Temporarily expose the API to loopback only for operator bootstrap:

```bash
docker compose --env-file /opt/jumarag/infra/.env \
  -f compose.production.yml -f ops/production/compose.bootstrap.yml up -d api
JUMARAG_SOURCE_ROOT=/opt/jumarag/knowledge/sources \
  ops/onboarding/salloon/prepare-sources.sh
set -a
source /opt/jumarag/infra/.env
set +a
JUMARAG_SOURCE_ROOT=/opt/jumarag/knowledge/sources \
JUMARAG_API_ROOT=http://127.0.0.1:18080 \
  ops/onboarding/salloon/bootstrap.sh
```

Preparation validates checksums before extraction. Bootstrap uses real embeddings, creates/resolves `salloon-snooker-rules` and `salloon-brazilian-rules`, verifies deterministic snapshot membership, activates the reviewed snapshots, and is idempotent. It prints safe KB/snapshot identifiers but no credential. After bootstrap, remove the temporary binding by recreating the API with only the base file:

```bash
docker compose --env-file /opt/jumarag/infra/.env -f compose.production.yml up -d --force-recreate api
```

## Salloon API client

With the same temporary loopback binding, create the production client once:

```bash
umask 077
set -a
source /opt/jumarag/infra/.env
set +a
JUMARAG_CLIENT_KEY_OUTPUT=/opt/jumarag/infra/salloon-production.api-key \
  ops/production/bootstrap-salloon-client.sh
```

The script creates logical client `salloon-production`, stores its returned secret once in the protected output file without printing it, and grants only `ASK` on the two Salloon KBs. The database stores only the key hash. Move the credential into the Salloon backend secret environment through the normal operator channel, then remove the staging file after verifying that transfer. Never place it in a release or frontend environment.

## Backup and restore

Create and validate a timestamped custom-format dump:

```bash
ops/production/backup.sh /opt/jumarag/infra/.env
```

Backups are written with mode-restrictive defaults under `/opt/jumarag/backups` and verified with `pg_restore --list` before publication. Also protect the named volume with the VPS/provider snapshot policy; a volume snapshot is supplementary, not a replacement for a portable logical dump.

Test restore in an isolated PostgreSQL 17/pgvector database before relying on a backup. A typical clean-target restore is:

```bash
pg_restore --exit-on-error --no-owner --no-acl --clean --if-exists \
  --dbname='<isolated target connection>' /opt/jumarag/backups/jumarag-<timestamp>.dump
```

Never run that clean restore against production without an approved recovery event and verified target.

## Upgrade and rollback

Before upgrade: back up, install the new SHA release, set the exact image, run preflight, pull, run forward migrations, and recreate API/worker. Smoke readiness and both KBs before enabling any consumer change.

Rollback changes the release symlink and `JUMARAG_IMAGE` to a previously verified SHA and recreates API/worker. Flyway migrations are never rolled backward destructively. Roll back application code only when the older image is compatible with the already-applied schema; otherwise restore the verified pre-upgrade dump into a controlled recovery target and follow an explicit incident plan.

## Activation order

Keep `SALLOON_AI_ASSISTANT_ENABLED=false` until all steps succeed:

1. JumaRAG stack ready with real providers.
2. Both production KBs bootstrapped and snapshots active.
3. Minimal Salloon production client/grants created.
4. Direct authenticated JumaRAG smoke completed from the private path.
5. Salloon backend joins `rag-client` and receives its private URL/key.
6. Integration smoke succeeds while the assistant remains hidden.
7. Enable the Salloon feature flag last.

No public Caddy route is required or allowed for this initial deployment.
