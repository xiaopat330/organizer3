#!/usr/bin/env bash
# Measure operational characteristics of Ollama on this machine
# to inform translation service concurrency design:
#   A. Can both gemma4:e4b and qwen2.5:14b co-reside?
#   B. What is the swap-cost when a model needs reloading?
#   C. Does OLLAMA_NUM_PARALLEL>1 help in-model throughput?
set -euo pipefail

OUT="${OUT:-/Users/pyoung/workspace/organizer3/reference/translation_poc/MEASURE_OPS.md}"
URL="http://localhost:11434/api/generate"

mem_now() {
  # Pages × 16384 = bytes on M-series
  local stats free comp wired
  stats=$(vm_stat)
  free=$(echo "$stats" | awk '/Pages free/{gsub(/\./,""); print $3}')
  comp=$(echo "$stats" | awk '/Pages occupied by compressor/{gsub(/\./,""); print $5}')
  wired=$(echo "$stats" | awk '/Pages wired down/{gsub(/\./,""); print $4}')
  pressure=$(memory_pressure 2>/dev/null | awk '/System-wide/{print $NF}')
  echo "free_pg=$free comp_pg=$comp wired_pg=$wired pressure=${pressure:-?}"
}

ollama_ps() {
  ollama ps 2>&1 | sed 's/^/  /'
}

call() {
  local model="$1" prompt="$2"
  local payload
  payload=$(jq -n --arg model "$model" --arg prompt "$prompt" \
    '{model:$model, prompt:$prompt, stream:false, think:false, options:{temperature:0.1, num_predict:30}}')
  curl -sS "$URL" -d "$payload"
}

mark() { printf '\n=== %s ===\n' "$1" | tee -a "$OUT"; }
log()  { printf '%s\n' "$1" | tee -a "$OUT"; }

: > "$OUT"
mark "Translation service ops measurements"
log "Generated: $(date '+%Y-%m-%d %H:%M:%S')"
log "Hardware: M-series, 24 GB unified"

# --- Baseline ----------------------------------------------------------------
mark "0. Baseline (no models loaded)"
ollama stop gemma4:e4b 2>/dev/null || true
ollama stop qwen2.5:14b 2>/dev/null || true
sleep 2
log "ollama ps:"; ollama_ps | tee -a "$OUT"
log "memory: $(mem_now)"

# --- A. Co-residency ---------------------------------------------------------
mark "A. Co-residency test"
log "Loading gemma4:e4b..."
t0=$(date +%s%N)
resp=$(call gemma4:e4b "Translate to English. Reply only with translation.\n\nJapanese: 紗倉まな\nEnglish:")
t1=$(date +%s%N)
load_ms=$(( (t1 - t0) / 1000000 ))
log "  call latency=${load_ms}ms (includes cold load)"
log "  response: $(echo "$resp" | jq -r .response | head -1)"
log "  model load duration (from API): $(echo "$resp" | jq -r '.load_duration // 0' | awk '{printf "%.1fs\n", $1/1e9}')"
log "ollama ps:"; ollama_ps | tee -a "$OUT"
log "memory: $(mem_now)"

log ""
log "Now also loading qwen2.5:14b (without unloading gemma4)..."
t0=$(date +%s%N)
resp=$(call qwen2.5:14b "Translate to English. Reply only with translation.\n\nJapanese: 紗倉まな\nEnglish:")
t1=$(date +%s%N)
load_ms=$(( (t1 - t0) / 1000000 ))
log "  call latency=${load_ms}ms"
log "  response: $(echo "$resp" | jq -r .response | head -1)"
log "  model load duration: $(echo "$resp" | jq -r '.load_duration // 0' | awk '{printf "%.1fs\n", $1/1e9}')"
log "ollama ps (do BOTH appear?):"; ollama_ps | tee -a "$OUT"
log "memory: $(mem_now)"

# --- B. Swap cost ------------------------------------------------------------
mark "B. Swap cost (forced unload + reload)"
ollama stop gemma4:e4b 2>/dev/null || true
ollama stop qwen2.5:14b 2>/dev/null || true
sleep 3
log "Both stopped."
log "memory after stop: $(mem_now)"

for i in 1 2 3; do
  log ""
  log "Cycle $i: gemma4 → qwen2.5 → gemma4"
  for m in gemma4:e4b qwen2.5:14b gemma4:e4b; do
    t0=$(date +%s%N)
    resp=$(call "$m" "Translate to English. Reply only with translation.\n\nJapanese: テスト\nEnglish:")
    t1=$(date +%s%N)
    wall=$(( (t1 - t0) / 1000000 ))
    load=$(echo "$resp" | jq -r '.load_duration // 0')
    eval_dur=$(echo "$resp" | jq -r '.eval_duration // 0')
    log "  $m: wall=${wall}ms  load=$(awk "BEGIN{printf \"%.1f\", $load/1e9}")s  eval=$(awk "BEGIN{printf \"%.1f\", $eval_dur/1e9}")s"
  done
done

# --- C. NUM_PARALLEL benefit (against currently-warm gemma4) -----------------
mark "C. In-model concurrency throughput"
log "Note: depends on OLLAMA_NUM_PARALLEL daemon setting (current: \${OLLAMA_NUM_PARALLEL:-default})."
log "Test does not modify daemon config — just measures observed concurrent throughput."
log ""
# Ensure gemma4 is warm
call gemma4:e4b "Hello" >/dev/null

# 4 distinct prompts
declare -a P=(
  "Translate to English. Reply only with translation.\n\nJapanese: 美人女将\nEnglish:"
  "Translate to English. Reply only with translation.\n\nJapanese: 巨乳若妻\nEnglish:"
  "Translate to English. Reply only with translation.\n\nJapanese: 痴漢電車\nEnglish:"
  "Translate to English. Reply only with translation.\n\nJapanese: ハメ撮り\nEnglish:"
)

for parallel in 1 2 4; do
  log ""
  log "Parallel=$parallel (sending $parallel concurrent requests)"
  pids=()
  t0=$(date +%s%N)
  for ((i=0; i<parallel; i++)); do
    ( call gemma4:e4b "${P[$i]}" > "/tmp/measure_${parallel}_${i}.json" ) &
    pids+=($!)
  done
  for p in "${pids[@]}"; do wait "$p"; done
  t1=$(date +%s%N)
  total_wall=$(( (t1 - t0) / 1000000 ))
  log "  total wall: ${total_wall}ms for $parallel requests"
  log "  per-request wall (parallel, observed): $((total_wall / parallel))ms"
  for ((i=0; i<parallel; i++)); do
    f="/tmp/measure_${parallel}_${i}.json"
    en=$(jq -r .response < "$f" 2>/dev/null | head -c 60)
    eval_ms=$(jq -r '(.total_duration // 0)/1e6 | floor' < "$f" 2>/dev/null)
    log "    [$i] ${eval_ms}ms: $en"
    rm -f "$f"
  done
done

mark "Done"
log "See $OUT for full output."
echo "Wrote $OUT"
