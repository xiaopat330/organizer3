#!/usr/bin/env bash
# Run only the stage-names section against MODEL, append to existing TSV.
set -euo pipefail

MODEL="${MODEL:?set MODEL=...}"
SAFE="${MODEL//[:\/]/_}"
DIR="${DIR:-/Users/pyoung/workspace/organizer3/reference/translation_poc}"
TSV="$DIR/run_${SAFE}.tsv"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434/api/generate}"

declare -a STAGE_NAMES=(
  "深田えいみ" "蒼井そら" "春陽モカ" "神宮寺ナオ" "唯井まひろ"
  "辻本杏" "ジュリア" "古川いおり" "湊莉久" "神咲詩織"
  "鈴村あいり" "楓カレン" "紗倉まな" "香苗レノン" "二階堂夢"
)

# If TSV missing, create header
if [ ! -s "$TSV" ]; then
  printf 'section\tstyle\tjapanese\tenglish\ttotal_duration_ns\tprompt_tokens\teval_tokens\teval_duration_ns\n' > "$TSV"
fi

call_one() {
  local jp="$1"
  local prompt="Translate the following Japanese text to natural English. Reply with ONLY the English translation, no notes, no romanization, no quotes.

Japanese: ${jp}
English:"
  local payload
  payload=$(jq -n --arg model "$MODEL" --arg prompt "$prompt" \
    '{model:$model, prompt:$prompt, stream:false, think:false, options:{temperature:0.2}}')
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
  printf 'Stage names (15)\tbasic\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$jp_clean" "$en_clean" "$total_dur" "$prompt_eval" "$eval_count" "$eval_dur" >> "$TSV"
  printf '  %s -> %s (%dms, %d tok)\n' "$jp" "$en" "$((total_dur/1000000))" "$eval_count"
}

echo "Running stage-names section on $MODEL -> $TSV"
for jp in "${STAGE_NAMES[@]}"; do
  call_one "$jp"
done
echo "Done."
