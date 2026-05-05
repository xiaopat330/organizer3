#!/usr/bin/env bash
# Phase 6 strategy spot-check: pick a translation strategy for full Japanese
# AV title strings (title_javdb_enrichment.title_original).
#
# Runs three candidate prompts against MODEL on a fresh DB sample of titles,
# bucketed by length. Output is a TSV scoreable by inspection or score.sh.
#
# Usage:  MODEL=gemma4:e4b ./phase6.sh
#         MODEL=qwen2.5:14b ./phase6.sh   (optional fallback comparison)
set -euo pipefail

MODEL="${MODEL:?set MODEL=...}"
SAFE="${MODEL//[:\/]/_}"
DIR="${DIR:-/Users/pyoung/workspace/organizer3/reference/translation_poc}"
TSV="$DIR/phase6_${SAFE}.tsv"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434/api/generate}"
DB="${DB:-/Users/pyoung/.organizer3/organizer.db}"
SAMPLE_PER_BUCKET="${SAMPLE_PER_BUCKET:-7}"

# --- Prompts -----------------------------------------------------------------

# label_basic — production prompt for short proper-noun labels (studios etc.)
BASIC_TEMPLATE='Translate the following Japanese text to natural English. Reply with ONLY the English translation, no notes, no romanization, no quotes.

Japanese: {jp}
English:'

# prose — production prompt for paragraph-structure bio text.
PROSE_TEMPLATE='Translate the following Japanese paragraph to natural English. Preserve paragraph structure. Reply with ONLY the English translation, no notes, no commentary.

Japanese: {jp}
English:'

# title — candidate Phase 6 prompt tuned for AV title strings. Emphasizes
# preserving names/codes verbatim and avoiding the prose-style preamble.
TITLE_TEMPLATE='Translate the following Japanese AV title to natural English.

Rules:
- Preserve any personal names verbatim (do not translate).
- Preserve any product codes or numbers verbatim.
- Output ONE LINE. No quotes, no notes, no romanization, no commentary.
- Do not soften or sanitize explicit terms.

Japanese: {jp}
English:'

# --- Sample titles by length bucket -----------------------------------------
# Targets a mix of simple and complex shapes:
#   short  : <= 20 JP chars
#   medium : 21-50
#   long   : 51+
sample_bucket() {
  local kind="$1"
  case "$kind" in
    short)  sqlite3 "$DB" "SELECT DISTINCT title_original FROM title_javdb_enrichment WHERE title_original GLOB '*[ぁ-んァ-ヶ一-龯]*' AND title_original != '' AND length(title_original) BETWEEN 8 AND 20 ORDER BY RANDOM() LIMIT $SAMPLE_PER_BUCKET;" ;;
    medium) sqlite3 "$DB" "SELECT DISTINCT title_original FROM title_javdb_enrichment WHERE title_original GLOB '*[ぁ-んァ-ヶ一-龯]*' AND title_original != '' AND length(title_original) BETWEEN 21 AND 50 ORDER BY RANDOM() LIMIT $SAMPLE_PER_BUCKET;" ;;
    long)   sqlite3 "$DB" "SELECT DISTINCT title_original FROM title_javdb_enrichment WHERE title_original GLOB '*[ぁ-んァ-ヶ一-龯]*' AND title_original != '' AND length(title_original) >= 51 ORDER BY RANDOM() LIMIT $SAMPLE_PER_BUCKET;" ;;
  esac
}

# --- Call Ollama and append a TSV row ---------------------------------------
call_one() {
  local bucket="$1" style="$2" jp="$3" template="$4" num_predict="$5"
  local prompt="${template/\{jp\}/$jp}"
  local payload
  payload=$(jq -n --arg model "$MODEL" --arg prompt "$prompt" --argjson np "$num_predict" \
    '{model:$model, prompt:$prompt, stream:false, think:false, options:{temperature:0.2, num_predict:$np}}')
  local resp
  resp=$(curl -sS "$OLLAMA_URL" -d "$payload" || echo '{}')
  local en total_dur prompt_eval eval_count eval_dur
  en=$(echo "$resp" | jq -r '.response // ""' | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
  total_dur=$(echo "$resp" | jq -r '.total_duration // 0')
  prompt_eval=$(echo "$resp" | jq -r '.prompt_eval_count // 0')
  eval_count=$(echo "$resp" | jq -r '.eval_count // 0')
  eval_dur=$(echo "$resp" | jq -r '.eval_duration // 0')
  local en_clean jp_clean
  en_clean=$(printf '%s' "$en" | tr '\t\n' '  ')
  jp_clean=$(printf '%s' "$jp" | tr '\t\n' '  ')
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$bucket" "$style" "$jp_clean" "$en_clean" \
    "$total_dur" "$prompt_eval" "$eval_count" "$eval_dur" >> "$TSV"
  printf '  [%s/%s] %s -> %s [%dms, %d tok]\n' \
    "$bucket" "$style" "${jp:0:30}" "${en:0:60}" "$((total_dur/1000000))" "$eval_count"
}

# --- Init TSV ---------------------------------------------------------------
printf 'bucket\tstyle\tjapanese\tenglish\ttotal_duration_ns\tprompt_tokens\teval_tokens\teval_duration_ns\n' > "$TSV"

echo "Phase 6 title-translation spot-check"
echo "  model: $MODEL"
echo "  out:   $TSV"
echo "  per-bucket sample: $SAMPLE_PER_BUCKET"
echo

# --- Run three candidates per sample, three buckets -------------------------
for bucket in short medium long; do
  echo "[bucket: $bucket]"
  while IFS= read -r jp; do
    [ -z "$jp" ] && continue
    call_one "$bucket" "label_basic" "$jp" "$BASIC_TEMPLATE" 192
    call_one "$bucket" "prose"       "$jp" "$PROSE_TEMPLATE" 384
    call_one "$bucket" "title"       "$jp" "$TITLE_TEMPLATE" 192
  done < <(sample_bucket "$bucket")
done

echo
echo "Done. Results: $TSV"
echo
echo "Quick view (per-strategy avg latency, en char count):"
awk -F'\t' 'NR>1 { ms=$5/1000000; len=length($4); n[$2]++; sum_ms[$2]+=ms; sum_len[$2]+=len } END { for (s in n) printf "  %-12s n=%d  avg_ms=%d  avg_en_chars=%d\n", s, n[s], sum_ms[s]/n[s], sum_len[s]/n[s] }' "$TSV"
