#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
API_ROOT="${JUMARAG_API_ROOT:-http://127.0.0.1:8080}"
: "${JUMARAG_ADMIN_TOKEN:?Set JUMARAG_ADMIN_TOKEN without placing it in this script.}"

ADMIN_HEADER="Authorization: Bearer ${JUMARAG_ADMIN_TOKEN}"
MANIFEST_ROOT="$ROOT_DIR/knowledge/onboarding/salloon"

require_command() {
  command -v "$1" >/dev/null 2>&1 || { printf 'Required command not found: %s\n' "$1" >&2; exit 1; }
}
for command_name in curl jq; do require_command "$command_name"; done

admin_get() { curl --fail --silent --show-error -H "$ADMIN_HEADER" "$API_ROOT$1"; }
admin_json() {
  local method="$1" path="$2" body="$3"
  curl --fail --silent --show-error -X "$method" -H "$ADMIN_HEADER" -H 'Content-Type: application/json' \
    --data "$body" "$API_ROOT$path"
}

resolve_kb() {
  local slug="$1" name="$2" description="$3" locale="$4" strategy="$5" existing
  existing="$(admin_get '/v1/admin/knowledge-bases' | jq -c --arg slug "$slug" '.[] | select(.slug == $slug)' | head -n 1)"
  if [[ -z "$existing" ]]; then
    admin_json POST '/v1/admin/knowledge-bases' "$(jq -n \
      --arg slug "$slug" --arg name "$name" --arg description "$description" \
      --arg locale "$locale" --arg strategy "$strategy" \
      '{slug:$slug,name:$name,description:$description,defaultLocale:$locale,contextStrategy:$strategy}')"
  else
    printf '%s' "$existing"
  fi
}

ingest_file() {
  local kb_id="$1" logical_name="$2" classification="$3" language="$4" title="$5" file="$6" metadata="$7"
  [[ -f "$file" ]] || { printf 'Prepared source missing: %s. Run prepare-sources.sh first.\n' "$file" >&2; exit 1; }
  curl --fail --silent --show-error -X POST -H "$ADMIN_HEADER" \
    --form-string "logicalName=$logical_name" --form-string "sourceClassification=$classification" \
    --form-string "language=$language" --form-string "title=$title" --form-string "metadata=$metadata" \
    -F "file=@$file;type=text/markdown" \
    "$API_ROOT/v1/admin/knowledge-bases/$kb_id/sources/file"
}

manifest_metadata() {
  jq -c '{governingBody,landingPage,canonicalUrl,edition,revision,publicationDate,retrievedAt,artifactSha256:.sha256,rights}' "$1"
}

ensure_snapshot() {
  local kb_id="$1" name="$2" versions_json="$3" metadata="$4" existing existing_items wanted_items
  existing="$(admin_get "/v1/admin/knowledge-bases/$kb_id/snapshots" | jq -c --arg name "$name" '.[] | select(.name == $name)' | head -n 1)"
  if [[ -n "$existing" ]]; then
    existing_items="$(printf '%s' "$existing" | jq -c '[.items[].sourceVersionId] | sort')"
    wanted_items="$(printf '%s' "$versions_json" | jq -c 'sort')"
    [[ "$existing_items" == "$wanted_items" ]] || {
      printf 'Snapshot %s exists with different immutable items. Choose a new snapshot name.\n' "$name" >&2
      exit 1
    }
    printf '%s' "$existing"
  else
    admin_json POST "/v1/admin/knowledge-bases/$kb_id/snapshots" "$(jq -n \
      --arg name "$name" --argjson versions "$versions_json" --argjson metadata "$metadata" \
      '{name:$name,sourceVersionIds:$versions,metadata:$metadata}')"
  fi
}

activate_snapshot() {
  local kb_id="$1" snapshot_id="$2"
  curl --fail --silent --show-error -X POST -H "$ADMIN_HEADER" \
    "$API_ROOT/v1/admin/knowledge-bases/$kb_id/snapshots/$snapshot_id/activate"
}

bootstrap_snooker() {
  local manifest kb kb_id official implementation snapshot versions
  manifest="$MANIFEST_ROOT/snooker/sources/wpbsa-rulebook-2024-25.json"
  kb="$(resolve_kb 'salloon-snooker-rules' 'Salloon Snooker Rules' \
    'Official Snooker rules and separately labeled Salloon implementation notes.' 'en' "${SNOOKER_CONTEXT_STRATEGY:-AUTO}")"
  kb_id="$(printf '%s' "$kb" | jq -r '.id')"
  official="$(ingest_file "$kb_id" "$(jq -r '.logicalName' "$manifest")" OFFICIAL_RULE en \
    "$(jq -r '.documentTitle + " " + .edition' "$manifest")" \
    "$ROOT_DIR/$(jq -r '.localPreparedFile' "$manifest")" "$(manifest_metadata "$manifest")")"
  implementation="$(ingest_file "$kb_id" 'Salloon Score Snooker implementation notes' IMPLEMENTATION_NOTE en \
    'Salloon Score Snooker implementation notes' \
    "$MANIFEST_ROOT/snooker/implementation/salloon-snooker-implementation-notes.md" \
    '{"owner":"Juma Tech","domain":"Salloon Score","reviewedAt":"2026-10-31"}')"
  versions="$(jq -n --arg a "$(printf '%s' "$official" | jq -r '.sourceVersionId')" \
    --arg b "$(printf '%s' "$implementation" | jq -r '.sourceVersionId')" '[$a,$b]')"
  snapshot="$(ensure_snapshot "$kb_id" "${SNOOKER_SNAPSHOT_NAME:-salloon-snooker-rules-v1}" "$versions" \
    '{"domain":"salloon","rulesetFamily":"SNOOKER","release":"v1"}')"
  activate_snapshot "$kb_id" "$(printf '%s' "$snapshot" | jq -r '.id')" >/dev/null
  printf 'Snooker KB %s active snapshot %s\n' "$kb_id" "$(printf '%s' "$snapshot" | jq -r '.id')"
}

bootstrap_brazilian() {
  local rules_manifest regulation_manifest kb kb_id rules regulation implementation snapshot versions
  rules_manifest="$MANIFEST_ROOT/brazilian/sources/cbbs-sinuca-2012.json"
  regulation_manifest="$MANIFEST_ROOT/brazilian/sources/cbbs-billiards-regulation-2013.json"
  kb="$(resolve_kb 'salloon-brazilian-rules' 'Salloon Brazilian Sinuca Rules' \
    'Official CBBS Sinuca rules, complementary regulation and separately labeled Salloon implementation notes.' \
    'pt-BR' "${BRAZILIAN_CONTEXT_STRATEGY:-AUTO}")"
  kb_id="$(printf '%s' "$kb" | jq -r '.id')"
  rules="$(ingest_file "$kb_id" "$(jq -r '.logicalName' "$rules_manifest")" OFFICIAL_RULE pt-BR \
    "$(jq -r '.documentTitle + " " + .edition' "$rules_manifest")" \
    "$ROOT_DIR/$(jq -r '.localPreparedFile' "$rules_manifest")" "$(manifest_metadata "$rules_manifest")")"
  regulation="$(ingest_file "$kb_id" "$(jq -r '.logicalName' "$regulation_manifest")" OFFICIAL_RULE pt-BR \
    "$(jq -r '.documentTitle + " " + .edition' "$regulation_manifest")" \
    "$ROOT_DIR/$(jq -r '.localPreparedFile' "$regulation_manifest")" "$(manifest_metadata "$regulation_manifest")")"
  implementation="$(ingest_file "$kb_id" 'Salloon Score Brazilian sinuca implementation notes' IMPLEMENTATION_NOTE pt-BR \
    'Notas de implementação da Sinuca brasileira no Salloon Score' \
    "$MANIFEST_ROOT/brazilian/implementation/salloon-brazilian-implementation-notes.md" \
    '{"owner":"Juma Tech","domain":"Salloon Score","reviewedAt":"2026-10-31"}')"
  versions="$(jq -n --arg a "$(printf '%s' "$rules" | jq -r '.sourceVersionId')" \
    --arg b "$(printf '%s' "$regulation" | jq -r '.sourceVersionId')" \
    --arg c "$(printf '%s' "$implementation" | jq -r '.sourceVersionId')" '[$a,$b,$c]')"
  snapshot="$(ensure_snapshot "$kb_id" "${BRAZILIAN_SNAPSHOT_NAME:-salloon-brazilian-rules-v1}" "$versions" \
    '{"domain":"salloon","rulesetFamily":"BRAZILIAN","release":"v1"}')"
  activate_snapshot "$kb_id" "$(printf '%s' "$snapshot" | jq -r '.id')" >/dev/null
  printf 'Brazilian KB %s active snapshot %s\n' "$kb_id" "$(printf '%s' "$snapshot" | jq -r '.id')"
}

bootstrap_snooker
bootstrap_brazilian

