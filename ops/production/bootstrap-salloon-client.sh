#!/usr/bin/env bash
set -Eeuo pipefail

API_ROOT="${JUMARAG_API_ROOT:-http://127.0.0.1:18080}"
CLIENT_NAME="${JUMARAG_SALLOON_CLIENT_NAME:-salloon-production}"
: "${JUMARAG_ADMIN_TOKEN:?Set JUMARAG_ADMIN_TOKEN in the operator shell.}"
: "${JUMARAG_CLIENT_KEY_OUTPUT:?Set an absolute, protected output path for the one-time client key.}"
[[ "$JUMARAG_CLIENT_KEY_OUTPUT" == /* ]] || {
  printf 'JUMARAG_CLIENT_KEY_OUTPUT must be an absolute path.\n' >&2
  exit 1
}
for command_name in curl jq; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'Required command not found: %s\n' "$command_name" >&2
    exit 1
  }
done

admin_get() {
  curl --fail --silent --show-error -H "Authorization: Bearer $JUMARAG_ADMIN_TOKEN" "$API_ROOT$1"
}
admin_post() {
  curl --fail --silent --show-error -X POST \
    -H "Authorization: Bearer $JUMARAG_ADMIN_TOKEN" -H 'Content-Type: application/json' \
    --data "$2" "$API_ROOT$1"
}

clients="$(admin_get '/v1/admin/api-clients')"
client_id="$(printf '%s' "$clients" | jq -r --arg name "$CLIENT_NAME" '.[] | select(.name == $name) | .id' | head -n 1)"
if [[ -z "$client_id" ]]; then
  [[ ! -e "$JUMARAG_CLIENT_KEY_OUTPUT" ]] || {
    printf 'Refusing to overwrite existing credential output: %s\n' "$JUMARAG_CLIENT_KEY_OUTPUT" >&2
    exit 1
  }
  created="$(admin_post '/v1/admin/api-clients' "$(jq -n --arg name "$CLIENT_NAME" \
    '{name:$name,metadata:{owner:"Salloon Score",environment:"production",purpose:"ASK only"}}')")"
  client_id="$(printf '%s' "$created" | jq -r '.client.id')"
  api_key="$(printf '%s' "$created" | jq -r '.apiKey')"
  [[ -n "$client_id" && "$api_key" == jrag_* ]] || {
    printf 'API client response did not contain the expected one-time credential.\n' >&2
    exit 1
  }
  install -d -m 700 "$(dirname "$JUMARAG_CLIENT_KEY_OUTPUT")"
  umask 077
  printf '%s\n' "$api_key" >"$JUMARAG_CLIENT_KEY_OUTPUT"
  unset api_key created
elif [[ ! -s "$JUMARAG_CLIENT_KEY_OUTPUT" ]]; then
  printf 'Client already exists but its one-time key is unavailable. Disable it and create a new named client.\n' >&2
  exit 1
fi

knowledge_bases="$(admin_get '/v1/admin/knowledge-bases')"
for slug in salloon-snooker-rules salloon-brazilian-rules; do
  kb_id="$(printf '%s' "$knowledge_bases" | jq -r --arg slug "$slug" '.[] | select(.slug == $slug) | .id' | head -n 1)"
  [[ -n "$kb_id" ]] || { printf 'Required Knowledge Base not found: %s\n' "$slug" >&2; exit 1; }
  admin_post "/v1/admin/api-clients/$client_id/grants" \
    "$(jq -n --arg kb "$kb_id" '{knowledgeBaseId:$kb,permission:"ASK"}')" >/dev/null
done

printf 'Salloon production client %s has ASK grants for both Salloon Knowledge Bases.\n' "$client_id"
printf 'The one-time credential was written with mode 600 to %s; it was not printed.\n' "$JUMARAG_CLIENT_KEY_OUTPUT"
