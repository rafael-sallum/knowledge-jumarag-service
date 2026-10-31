#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
STAGING_DIR="$ROOT_DIR/.local/knowledge-sources"
MANIFEST_DIR="$ROOT_DIR/knowledge/onboarding/salloon"

require_command() {
  command -v "$1" >/dev/null 2>&1 || { printf 'Required command not found: %s\n' "$1" >&2; exit 1; }
}

for command_name in jq sha256sum pdftotext pdfinfo libreoffice perl; do
  require_command "$command_name"
done

verify_artifact() {
  local manifest="$1"
  local directory="$2"
  local filename expected actual
  filename="$(jq -r '.artifactFileName' "$manifest")"
  expected="$(jq -r '.sha256' "$manifest")"
  [[ -f "$directory/$filename" ]] || { printf 'Missing local artifact: %s\n' "$directory/$filename" >&2; exit 1; }
  actual="$(sha256sum "$directory/$filename" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || {
    printf 'Checksum mismatch for %s. Treat it as a new source version and update the reviewed manifest.\n' "$filename" >&2
    exit 1
  }
}

prepare_pdf() {
  local manifest="$1"
  local directory="$2"
  local filename output pages temp page
  filename="$(jq -r '.artifactFileName' "$manifest")"
  output="$ROOT_DIR/$(jq -r '.localPreparedFile' "$manifest")"
  temp="${output}.tmp"
  pages="$(pdfinfo "$directory/$filename" | awk '/^Pages:/ {print $2}')"
  : > "$temp"
  {
    printf '# %s\n\n' "$(jq -r '.documentTitle' "$manifest")"
    printf '> Local ingestion artifact generated from the reviewed manifest. Do not commit or redistribute.\n\n'
  } >> "$temp"
  for ((page = 1; page <= pages; page++)); do
    printf '\n## PDF page %d\n\n' "$page" >> "$temp"
    pdftotext -f "$page" -l "$page" -layout "$directory/$filename" - >> "$temp"
  done
  perl -pi -e 's/\r$//; s/[ \t]+$//' "$temp"
  mv "$temp" "$output"
}

prepare_word() {
  local manifest="$1"
  local directory="$2"
  local filename output temp_dir converted
  filename="$(jq -r '.artifactFileName' "$manifest")"
  output="$ROOT_DIR/$(jq -r '.localPreparedFile' "$manifest")"
  temp_dir="$(mktemp -d)"
  trap 'rm -rf "$temp_dir"' RETURN
  libreoffice --headless --convert-to txt:Text --outdir "$temp_dir" "$directory/$filename" >/dev/null 2>&1
  converted="$temp_dir/${filename%.*}.txt"
  [[ -f "$converted" ]] || { printf 'Word text extraction failed for %s\n' "$filename" >&2; exit 1; }
  {
    printf '# %s\n\n' "$(jq -r '.documentTitle' "$manifest")"
    printf '> Local ingestion artifact generated from the reviewed manifest. Original Word page boundaries are not reliable; cite article/section headings. Do not commit or redistribute.\n\n'
    perl -pe 's/^\x{FEFF}// if $. == 1; s/\r$//; s/[ \t]+$//' "$converted"
  } > "$output"
  rm -rf "$temp_dir"
  trap - RETURN
}

WPBSA_MANIFEST="$MANIFEST_DIR/snooker/sources/wpbsa-rulebook-2024-25.json"
CBBS_RULES_MANIFEST="$MANIFEST_DIR/brazilian/sources/cbbs-sinuca-2012.json"
CBBS_REGULATION_MANIFEST="$MANIFEST_DIR/brazilian/sources/cbbs-billiards-regulation-2013.json"

verify_artifact "$WPBSA_MANIFEST" "$STAGING_DIR/snooker"
verify_artifact "$CBBS_RULES_MANIFEST" "$STAGING_DIR/brazilian"
verify_artifact "$CBBS_REGULATION_MANIFEST" "$STAGING_DIR/brazilian"
prepare_pdf "$WPBSA_MANIFEST" "$STAGING_DIR/snooker"
prepare_word "$CBBS_RULES_MANIFEST" "$STAGING_DIR/brazilian"
prepare_word "$CBBS_REGULATION_MANIFEST" "$STAGING_DIR/brazilian"

printf 'Prepared three verified, Git-ignored ingestion artifacts under %s\n' "$STAGING_DIR"

