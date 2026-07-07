#!/usr/bin/env bash
# Extract plain-text paragraphs + section counts from the two legal .docx files.
# Repeatability/fidelity aid (FR-012, SC-002): re-run after any docx change and
# diff the output against the generated HTML text. Requires: unzip, sed, grep.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
ASSETS="$REPO/app/src/main/assets"
OUT="${1:-$REPO/tools/legal-sites/out}"
mkdir -p "$OUT"

extract() {
  local docx="$1" name="$2"
  local tmp; tmp="$(mktemp -d)"
  unzip -o -q "$docx" -d "$tmp"
  # One line per <w:p> paragraph, tags stripped, blank lines dropped.
  sed -e 's/<w:p [^>]*>/\n/g; s/<w:p>/\n/g' "$tmp/word/document.xml" \
    | sed -e 's/<[^>]*>//g' \
    | sed -e 's/&amp;/\&/g; s/&lt;/</g; s/&gt;/>/g; s/&quot;/"/g; s/&#39;/'"'"'/g' \
    | grep -v '^[[:space:]]*$' > "$OUT/$name.txt"
  local paras sections
  paras="$(wc -l < "$OUT/$name.txt" | tr -d ' ')"
  sections="$(grep -cE '^[0-9]+\. ' "$OUT/$name.txt" || true)"
  printf '%-16s paragraphs=%s  numbered-lines=%s  -> %s\n' "$name" "$paras" "$sections" "$OUT/$name.txt"
  rm -rf "$tmp"
}

extract "$ASSETS/privacy_policy.docx"   privacy_policy
extract "$ASSETS/terms_conditions.docx" terms_conditions
