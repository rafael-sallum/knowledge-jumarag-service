#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-/opt/jumarag/infra/.env}"
COMPOSE_FILE="$ROOT_DIR/compose.production.yml"

for command_name in docker jq; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'Required command not found: %s\n' "$command_name" >&2
    exit 1
  }
done
[[ -f "$ENV_FILE" ]] || { printf 'Environment file not found: %s\n' "$ENV_FILE" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

required=(
  JUMARAG_IMAGE JUMARAG_CLIENT_NETWORK POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD
  JUMARAG_ADMIN_TOKEN JUMARAG_API_KEY_PEPPER JUMARAG_EMBEDDING_PROVIDER
  JUMARAG_CHAT_PROVIDER JUMARAG_DEFAULT_EMBEDDING_PROFILE
  JUMARAG_RETRIEVAL_EMBEDDING_PROFILE OPENAI_API_KEY
  JUMARAG_API_JAVA_TOOL_OPTIONS JUMARAG_WORKER_JAVA_TOOL_OPTIONS
  JUMARAG_MIGRATE_JAVA_TOOL_OPTIONS
)
for variable in "${required[@]}"; do
  value="${!variable:-}"
  [[ -n "$value" && "$value" != *'<'* && "$value" != *'change-me'* && "$value" != *'replace-'* ]] || {
    printf 'Required production value is unset or still a placeholder: %s\n' "$variable" >&2
    exit 1
  }
done

[[ "$JUMARAG_IMAGE" =~ ^ghcr\.io/rafael-sallum/knowledge-jumarag-service:([0-9a-f]{40})$ ||
   "$JUMARAG_IMAGE" =~ ^ghcr\.io/rafael-sallum/knowledge-jumarag-service@sha256:([0-9a-f]{64})$ ]] || {
  printf 'JUMARAG_IMAGE must be the repository GHCR image pinned by full Git SHA or digest.\n' >&2
  exit 1
}
[[ "$JUMARAG_EMBEDDING_PROVIDER" == "openai" && "$JUMARAG_CHAT_PROVIDER" == "openai" ]] || {
  printf 'Production embedding and chat providers must both be openai.\n' >&2
  exit 1
}
[[ "$JUMARAG_DEFAULT_EMBEDDING_PROFILE" == openai-* &&
   "$JUMARAG_RETRIEVAL_EMBEDDING_PROFILE" == "$JUMARAG_DEFAULT_EMBEDDING_PROFILE" ]] || {
  printf 'Production ingestion and retrieval must use the same explicit OpenAI embedding profile.\n' >&2
  exit 1
}
(( ${#JUMARAG_ADMIN_TOKEN} >= 32 && ${#JUMARAG_API_KEY_PEPPER} >= 32 )) || {
  printf 'JumaRAG service secrets must each contain at least 32 characters.\n' >&2
  exit 1
}
docker network inspect "$JUMARAG_CLIENT_NETWORK" >/dev/null 2>&1 || {
  printf 'Shared Docker network does not exist: %s\n' "$JUMARAG_CLIENT_NETWORK" >&2
  exit 1
}

config="$(mktemp)"
trap 'rm -f "$config"' EXIT
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --format json >"$config"
jq -e '
  (.services.postgres.ports // [] | length) == 0 and
  (.services.api.ports // [] | length) == 0 and
  (.services.worker.ports // [] | length) == 0 and
  .services.api.environment.JUMARAG_EMBEDDING_PROVIDER == "openai" and
  .services.api.environment.JUMARAG_CHAT_PROVIDER == "openai" and
  .services.api.environment.SPRINGDOC_API_DOCS_ENABLED == "false" and
  .services.api.environment.SPRINGDOC_SWAGGER_UI_ENABLED == "false" and
  .networks["rag-client"].external == true and
  (.services.worker.networks | has("rag-client") | not) and
  (.services.worker.networks | has("provider-egress"))
' "$config" >/dev/null || {
  printf 'Rendered production Compose violates the private production contract.\n' >&2
  exit 1
}

printf 'Production preflight passed: immutable image, real providers, private ports, external client network.\n'
