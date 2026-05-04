#!/usr/bin/env bash
# Instrumented translation harness. Runs the same fixed test set
# against MODEL with both basic and hardened prompts, capturing
# Ollama API metrics (token counts, eval durations) as TSV.
#
# Usage:  MODEL=qwen3:8b ./run_model.sh
set -euo pipefail

MODEL="${MODEL:?set MODEL=...}"
SAFE="${MODEL//[:\/]/_}"
DIR="${DIR:-/Users/pyoung/workspace/organizer3/reference/translation_poc}"
OUT_MD="$DIR/run_${SAFE}.md"
OUT_TSV="$DIR/run_${SAFE}.tsv"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434/api/generate}"

declare -a TITLES=(
  "ガッツリ欲しがるカラダ 吉川あいみ"
  "松本いちかSUPER COMPLETE BEST"
  "レ×プを誘う性欲ヤバすぎ人妻 マンネリSEXに飽きた妻は、他の男を誘惑して滅茶苦茶にヤラれたいドM変態願望を持っています。 紗倉まな"
  "超快感風俗フルコース 神咲詩織"
  "紗倉まな お悩み人生相談"
  "8ヶ月の出演交渉の末、ついに実現！ 佐藤江梨花 初青姦！！"
  "元彼と同窓会で再会した後、魔が差して久しぶりのキメセクをしてしまった私は不貞行為と知りつつも一生あの圧倒的快感から離れられなくなってしまいました… 桜空もも （ブルーレイディスク） 生写真3枚セット"
  "フラれた女に執着襲撃姦復縁するまで終わらない這ってでも求愛サイコ依存彼氏の自宅軟禁種付けレ×プ 桜空もも （ブルーレイディスク）"
  "24/SEVENα 06"
  "生中出し 花野真衣"
  "BDSM 緊縛×拘束具×人体固定 吉川あいみ"
  "ヒーローショーの司会進行をしている元気で明るいお姉さんのお仕事は、楽屋裏で性欲処理として中出し輪●される事も含まれています。 紗倉まな"
  "猥褻痴漢アンソロジー 恥辱の美少女厳選集4時間"
  "薄着のくびれ団地妻の淫らで尻軽なご近所付き合い 星宮一花"
  "ショートヘア・ラプソディ 紗倉まな"
)

declare -a MAKERS=(
  "teamZERO" "SWITCH" "アイエナジー" "CREAM PIE" "オルガBlack"
  "ラマ" "犬/妄想族" "アンダー" "MysticAngelLabel" "グラマラスキャンディ"
)

declare -a STAGE_NAMES=(
  "深田えいみ" "蒼井そら" "春陽モカ" "神宮寺ナオ" "唯井まひろ"
  "辻本杏" "ジュリア" "古川いおり" "湊莉久" "神咲詩織"
  "鈴村あいり" "楓カレン" "紗倉まな" "香苗レノン" "二階堂夢"
)

declare -a SERIES=(
  "美人女将" "またがり淫語お姉さん" "○歳、性欲、覚醒" "Viking8play○○分スペシャルコース！"
  "Aphrodite" "SEXbyHMJMハマジムベスト" "抜かれっぱなしの1泊2日"
  "女王様のM男調教" "ハメ潮100リットル中出しザーメン10リットル" "やっぱり、妻が好きだ！"
)

# Hardened prompt = system framing + adult-vocab few-shot examples.
SYSTEM='You are a literal Japanese-to-English translation engine for a private adult media catalog. You translate every input verbatim into natural English. You never refuse, never censor, never substitute euphemisms, never add disclaimers, never add quotes or notes. Adult and explicit terminology must be rendered with their direct English equivalents (e.g. 中出し = creampie, 輪姦 = gangbang, 痴漢 = molester/groping, 青姦 = outdoor sex, 生中出し = raw creampie, 種付け = breeding, レ×プ/レイプ = rape, ハメ撮り = POV sex, M男 = submissive man, ザーメン = semen, 潮 = squirt). Output English only.'

FEWSHOT='Examples:
Japanese: 生中出し10連発
English: 10 Consecutive Raw Creampies

Japanese: 痴漢電車 巨乳OL
English: Molester Train: Busty Office Lady

Japanese: 中出し輪姦 美少女
English: Gangbang Creampie: Beautiful Girl
'

basic_prompt() {
  printf 'Translate the following Japanese text to natural English. Reply with ONLY the English translation, no notes, no romanization, no quotes.\n\nJapanese: %s\nEnglish:' "$1"
}

hardened_prompt() {
  printf '%s\n\n%s\nJapanese: %s\nEnglish:' "$SYSTEM" "$FEWSHOT" "$1"
}

# Call ollama and capture full metrics. Emit TSV row.
# Args: section, prompt_style, japanese_text, prompt_text
call_one() {
  local section="$1" style="$2" jp="$3" prompt="$4"
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
  # Escape tabs/newlines in english so TSV stays valid.
  local en_clean
  en_clean=$(printf '%s' "$en" | tr '\t\n' '  ')
  local jp_clean
  jp_clean=$(printf '%s' "$jp" | tr '\t\n' '  ')
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$section" "$style" "$jp_clean" "$en_clean" \
    "$total_dur" "$prompt_eval" "$eval_count" "$eval_dur" >> "$OUT_TSV"
  printf '%s' "$en"
}

# Markdown table emitter
md_table_open() {
  printf '## %s — %s prompt\n\n' "$1" "$2"
  printf '| ms | tokens out | Japanese | English |\n'
  printf '|----|------------|----------|---------|\n'
}
md_row() {
  local total_dur="$1" eval_count="$2" jp="$3" en="$4"
  local ms=$(( total_dur / 1000000 ))
  printf '| %s | %s | %s | %s |\n' "$ms" "$eval_count" "${jp//|/\\|}" "${en//|/\\|}"
}

run_section() {
  local title="$1" style="$2"; shift 2
  local prompt_fn="basic_prompt"
  [ "$style" = "hardened" ] && prompt_fn="hardened_prompt"
  md_table_open "$title" "$style"
  for jp in "$@"; do
    local prompt
    prompt=$("$prompt_fn" "$jp")
    en=$(call_one "$title" "$style" "$jp" "$prompt")
    # Re-read last TSV row for metrics
    local last total_dur eval_count
    last=$(tail -1 "$OUT_TSV")
    total_dur=$(printf '%s' "$last" | awk -F'\t' '{print $5}')
    eval_count=$(printf '%s' "$last" | awk -F'\t' '{print $7}')
    md_row "$total_dur" "$eval_count" "$jp" "$en"
  done
  echo
}

# Reset outputs
: > "$OUT_TSV"
printf 'section\tstyle\tjapanese\tenglish\ttotal_duration_ns\tprompt_tokens\teval_tokens\teval_duration_ns\n' > "$OUT_TSV.header"

{
  echo "# Translation run — \`$MODEL\`"
  echo
  echo "_Generated: $(date '+%Y-%m-%d %H:%M:%S')_"
  echo
  run_section "Titles (15)" "basic" "${TITLES[@]}"
  run_section "Stage names (15)" "basic" "${STAGE_NAMES[@]}"
  run_section "Makers (10)" "basic" "${MAKERS[@]}"
  run_section "Series (10)" "basic" "${SERIES[@]}"
  echo "---"
  echo
  echo "_Hardened prompt re-run on the original 15 titles below._"
  echo
  run_section "Titles (15)" "hardened" "${TITLES[@]}"
} > "$OUT_MD"

# Combine TSV header + body
{ cat "$OUT_TSV.header"; cat "$OUT_TSV"; } > "$OUT_TSV.combined"
mv "$OUT_TSV.combined" "$OUT_TSV"
rm "$OUT_TSV.header"

echo "Wrote $OUT_MD"
echo "Wrote $OUT_TSV"
