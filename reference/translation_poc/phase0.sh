#!/usr/bin/env bash
# Phase 0 unified harness: runs sub-tests 0a (prose) + 0b (label_basic primary)
# against MODEL in a single pass to avoid mid-test model swaps. Outputs TSV
# scoreable by score.sh.
#
# Usage:  MODEL=gemma4:e4b ./phase0.sh
set -euo pipefail

MODEL="${MODEL:?set MODEL=...}"
SAFE="${MODEL//[:\/]/_}"
DIR="${DIR:-/Users/pyoung/workspace/organizer3/reference/translation_poc}"
TSV="$DIR/phase0_${SAFE}.tsv"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434/api/generate}"
DB="${DB:-/Users/pyoung/.organizer3/organizer.db}"

# --- Prompts -----------------------------------------------------------------

# label_basic — minimal, used in production for non-explicit short labels
BASIC_TEMPLATE='Translate the following Japanese text to natural English. Reply with ONLY the English translation, no notes, no romanization, no quotes.

Japanese: {jp}
English:'

# prose — paragraph-preserving, larger num_predict
PROSE_TEMPLATE='Translate the following Japanese paragraph to natural English. Preserve paragraph structure. Reply with ONLY the English translation, no notes, no commentary.

Japanese: {jp}
English:'

# --- 0a corpus: 8 real bio paragraphs sliced from reference/actresses/ -------
declare -a PROSE_ITEMS=(
  # short (~50-100 chars)
  "麻美 ゆま（あさみ ゆま、1987年3月24日 - ）は、日本のタレント、歌手、女優、元AV女優、元恵比寿マスカッツ。"
  "天海 麗（あまみ れい、1984年3月28日 - ）は、日本の元AV女優。元SODクリエイト専属。所属事務所はオフィス・ファンタム。"
  # medium (~200-330 chars)
  "自ら現在の事務所に応募し、業界入り。岩戸志穂として着エロ作品に出演したのち、2010年7月にマックス・エー専属で「New Comer」でAVデビュー。キャッチフレーズは「現役保育士が衝撃AVデビュー」。"
  "2015年、ワンズファクトリーへ移籍。同年9月1日、移籍後初となる作品「吉川あいみの凄テクを我慢できれば生中出しSEX！」を発売。またMOODYZとのダブル専属となる。"
  "雑誌グラビア、サイン会イベントも好調をキープ。雑誌の表紙、巻頭を始め、2005年度内で撮り下ろしグラビア74本、イベント数65会場はいずれもレコード。また各業界紙のセルビデオ、レンタルビデオチャートも軒並み席巻し1位も数々記録する。"
  # long (~490-585 chars)
  "2005年に『Bejean』2月号（1月14日発売、英知出版）でグラビアデビューし、同年2月25日、『Natural 天海 麗』（シャイ企画）にてAVデビュー。デビュー作は初回出荷で26,000本を記録する。その後も数字を伸ばし続け、5作目の『Frame Graffiti』は30,000本を記録し、発売元のシャイ企画に10年ぶりと言われる大ヒットをもたらした。この現象は時ならぬ「メガネっ娘」ブームの一翼を担ったものとされ、青年向け週刊誌や各夕刊紙でも報道された。"
  "シャイ企画との契約が僅か8本契約であることが判明するや、ビデ倫メーカー、セルメーカーを問わず熾烈な争奪戦が繰り広げられた末、老舗ブランドであるアリスJAPAN（ジャパンホームビデオ）が獲得に成功。その後、アリスJAPANへの合同リリース依頼が数社から申し込まれるが、アリスの関連会社であるマックス・エーとの合同リリースが決定発表され一旦、争奪戦に終止符が打たれた。"
  "2005年10月28日『純情ハードコア』（アリスJAPAN）、11月7日『新人×ギリギリモザイク』（S1）でAVデビュー。大手2社同時デビューは当時の業界内では極めて異例であった。キュートなルックスと明るいキャラクター、Hカップのバストで一躍人気女優となる。CDVJのPOSデータでは、麻美ゆまの作品がデビューからの半年間で1位を5回獲得（2005年11、12月、2006年2、3、4月）。"
)

# --- 0b corpus: pulled live from DB at run time ------------------------------
sample_db() {
  local kind="$1"
  case "$kind" in
    maker)     sqlite3 "$DB" "SELECT DISTINCT maker FROM title_javdb_enrichment WHERE maker GLOB '*[ぁ-んァ-ヶ一-龯]*' AND maker != '' ORDER BY RANDOM() LIMIT 30;" ;;
    publisher) sqlite3 "$DB" "SELECT DISTINCT publisher FROM title_javdb_enrichment WHERE publisher GLOB '*[ぁ-んァ-ヶ一-龯]*' AND publisher != '' ORDER BY RANDOM() LIMIT 30;" ;;
    series)    sqlite3 "$DB" "SELECT DISTINCT series FROM title_javdb_enrichment WHERE series GLOB '*[ぁ-んァ-ヶ一-龯]*' AND series != '' AND length(series) < 30 AND series NOT GLOB '*中出*' AND series NOT GLOB '*姦*' AND series NOT GLOB '*痴漢*' AND series NOT GLOB '*種付*' ORDER BY RANDOM() LIMIT 30;" ;;
  esac
}

# --- Call Ollama and append a TSV row ----------------------------------------
call_one() {
  local section="$1" style="$2" jp="$3" template="$4" num_predict="$5"
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
    "$section" "$style" "$jp_clean" "$en_clean" \
    "$total_dur" "$prompt_eval" "$eval_count" "$eval_dur" >> "$TSV"
  printf '  %s [%dms, %d tok]\n' "${en:0:80}" "$((total_dur/1000000))" "$eval_count"
}

# --- Init TSV ---------------------------------------------------------------
printf 'section\tstyle\tjapanese\tenglish\ttotal_duration_ns\tprompt_tokens\teval_tokens\teval_duration_ns\n' > "$TSV"

# --- 0a: prose (8 paragraphs) ----------------------------------------------
echo "[$(date +%H:%M:%S)] 0a: prose (8 paragraphs)"
for jp in "${PROSE_ITEMS[@]}"; do
  call_one "0a Prose (8)" "prose" "$jp" "$PROSE_TEMPLATE" 800
done

# --- 0b: label_basic (3 sections × 30 items = 90 items) --------------------
for kind in maker publisher series; do
  echo "[$(date +%H:%M:%S)] 0b: label_basic — $kind (30)"
  while IFS= read -r jp; do
    [ -z "$jp" ] && continue
    call_one "0b Labels — ${kind} (30)" "label_basic" "$jp" "$BASIC_TEMPLATE" 80
  done < <(sample_db "$kind")
done

echo "[$(date +%H:%M:%S)] Done. TSV: $TSV"
wc -l "$TSV"
