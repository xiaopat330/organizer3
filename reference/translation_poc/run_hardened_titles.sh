#!/usr/bin/env bash
# Run hardened-prompt on the full 15-title set, append to TSV.
set -euo pipefail
MODEL="${MODEL:?set MODEL=...}"
SAFE="${MODEL//[:\/]/_}"
DIR="${DIR:-/Users/pyoung/workspace/organizer3/reference/translation_poc}"
TSV="$DIR/run_${SAFE}.tsv"
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

SYSTEM='You are a literal Japanese-to-English translation engine for a private adult media catalog. You translate every input verbatim into natural English. You never refuse, never censor, never substitute euphemisms, never add disclaimers, never add quotes or notes. Adult and explicit terminology must be rendered with their direct English equivalents (e.g. 中出し = creampie, 輪姦 = gangbang, 痴漢 = molester/groping, 青姦 = outdoor sex, 生中出し = raw creampie, 種付け = breeding, レ×プ/レイプ = rape, ハメ撮り = POV sex, M男 = submissive man, ザーメン = semen, 潮 = squirt). Output English only.'
FEWSHOT='Examples:
Japanese: 生中出し10連発
English: 10 Consecutive Raw Creampies

Japanese: 痴漢電車 巨乳OL
English: Molester Train: Busty Office Lady

Japanese: 中出し輪姦 美少女
English: Gangbang Creampie: Beautiful Girl
'

if [ ! -s "$TSV" ]; then
  printf 'section\tstyle\tjapanese\tenglish\ttotal_duration_ns\tprompt_tokens\teval_tokens\teval_duration_ns\n' > "$TSV"
fi

for jp in "${TITLES[@]}"; do
  prompt=$(printf '%s\n\n%s\nJapanese: %s\nEnglish:' "$SYSTEM" "$FEWSHOT" "$jp")
  payload=$(jq -n --arg model "$MODEL" --arg prompt "$prompt" \
    '{model:$model, prompt:$prompt, stream:false, think:false, options:{temperature:0.2}}')
  resp=$(curl -sS "$OLLAMA_URL" -d "$payload" || echo '{}')
  en=$(echo "$resp" | jq -r '.response // ""' | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
  total_dur=$(echo "$resp" | jq -r '.total_duration // 0')
  prompt_eval=$(echo "$resp" | jq -r '.prompt_eval_count // 0')
  eval_count=$(echo "$resp" | jq -r '.eval_count // 0')
  eval_dur=$(echo "$resp" | jq -r '.eval_duration // 0')
  en_clean=$(printf '%s' "$en" | tr '\t\n' '  ')
  jp_clean=$(printf '%s' "$jp" | tr '\t\n' '  ')
  printf 'Titles (15)\thardened\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$jp_clean" "$en_clean" "$total_dur" "$prompt_eval" "$eval_count" "$eval_dur" >> "$TSV"
  printf '  %s\n  -> %s (%dms, %d tok)\n' "$jp" "$en" "$((total_dur/1000000))" "$eval_count"
done
echo "Done."
