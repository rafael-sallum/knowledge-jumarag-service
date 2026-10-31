#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
API_ROOT="${JUMARAG_API_ROOT:-http://127.0.0.1:8080}"
: "${JUMARAG_ADMIN_TOKEN:?Set JUMARAG_ADMIN_TOKEN.}"
: "${JUMARAG_CLIENT_KEY:?Set a client key with ASK grants for the target Knowledge Base.}"

SLUG="${1:?Usage: compare-strategies.sh <knowledge-base-slug> <golden-cases.json>}"
CASES_FILE="${2:?Usage: compare-strategies.sh <knowledge-base-slug> <golden-cases.json>}"
MAX_CASES="${JUMARAG_EVALUATION_MAX_CASES:-4}"
OUTPUT_DIR="$ROOT_DIR/.local/knowledge-sources/evaluation"
mkdir -p "$OUTPUT_DIR"
OUTPUT="$OUTPUT_DIR/${SLUG}-strategy-comparison.jsonl"
: > "$OUTPUT"

ADMIN_HEADER="Authorization: Bearer ${JUMARAG_ADMIN_TOKEN}"
CLIENT_HEADER="X-JumaRAG-API-Key: ${JUMARAG_CLIENT_KEY}"
KB_ID="$(curl --fail --silent --show-error -H "$ADMIN_HEADER" "$API_ROOT/v1/admin/knowledge-bases" \
  | jq -r --arg slug "$SLUG" '.[] | select(.slug == $slug) | .id')"
[[ -n "$KB_ID" ]] || { printf 'Knowledge Base not found: %s\n' "$SLUG" >&2; exit 1; }

for strategy in RAG LONG_CONTEXT HYBRID AUTO; do
  curl --fail --silent --show-error -X PATCH -H "$ADMIN_HEADER" -H 'Content-Type: application/json' \
    --data "{\"contextStrategy\":\"$strategy\"}" \
    "$API_ROOT/v1/admin/knowledge-bases/$KB_ID/context-strategy" >/dev/null
  jq -c --argjson max "$MAX_CASES" '.cases[:$max][]' "$CASES_FILE" | while IFS= read -r golden; do
    started="$(date +%s%3N)"
    response_file="$(mktemp)"
    http_status="$(curl --silent --show-error -o "$response_file" -w '%{http_code}' -X POST \
      -H "$CLIENT_HEADER" -H 'Content-Type: application/json' \
      --data "$(printf '%s' "$golden" | jq '{question,language,evidenceIntent}')" \
      "$API_ROOT/v1/knowledge-bases/$SLUG/ask")"
    response="$(cat "$response_file")"
    rm -f "$response_file"
    elapsed="$(( $(date +%s%3N) - started ))"
    if [[ "$http_status" == "200" ]]; then
      jq -cn --argjson golden "$golden" --argjson response "$response" --arg strategy "$strategy" \
        --argjson latency "$elapsed" --argjson httpStatus "$http_status" \
        '{caseId:$golden.id,evidenceIntent:$response.evidenceIntent,configuredStrategy:$strategy,usedStrategy:$response.contextStrategyUsed,reason:$response.contextDecisionReason,status:$response.status,expectedStatus:$golden.expectedStatus,requiredEvidenceClasses:$response.requiredEvidenceClasses,presentEvidenceClasses:$response.presentEvidenceClasses,evidenceDiversitySatisfied:$response.evidenceDiversitySatisfied,citationClassifications:[$response.citations[].sourceClassification],contextChunkCount:$response.contextChunkCount,estimatedTokens:$response.contextEstimatedTokens,latencyMillis:$latency,httpStatus:$httpStatus,providerUsage:$response.providerUsage}' \
        >> "$OUTPUT"
    else
      jq -cn --argjson golden "$golden" --argjson response "$response" --arg strategy "$strategy" \
        --argjson latency "$elapsed" --argjson httpStatus "$http_status" \
        '{caseId:$golden.id,configuredStrategy:$strategy,expectedStatus:$golden.expectedStatus,httpStatus:$httpStatus,errorCode:$response.code,latencyMillis:$latency}' \
        >> "$OUTPUT"
    fi
  done
done

printf 'Wrote bounded strategy comparison to %s\n' "$OUTPUT"

