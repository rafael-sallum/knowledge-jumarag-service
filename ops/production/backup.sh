#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${1:-/opt/jumarag/infra/.env}"
BACKUP_DIR="${JUMARAG_BACKUP_DIR:-/opt/jumarag/backups}"
COMPOSE_FILE="$ROOT_DIR/compose.production.yml"

[[ -f "$ENV_FILE" ]] || { printf 'Environment file not found: %s\n' "$ENV_FILE" >&2; exit 1; }
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"

install -d -m 700 "$BACKUP_DIR"
umask 077
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$BACKUP_DIR/jumarag-$timestamp.dump"
partial="$target.partial"
trap 'rm -f "$partial"' EXIT

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
  pg_dump --format=custom --no-owner --no-acl --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" >"$partial"
[[ -s "$partial" ]] || { printf 'Backup is empty; refusing to publish it.\n' >&2; exit 1; }
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
  pg_restore --list <"$partial" >/dev/null
mv "$partial" "$target"
trap - EXIT
printf 'Verified PostgreSQL backup: %s\n' "$target"
