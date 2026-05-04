#!/usr/bin/env bash
# Compute quality + performance metrics from one or more run_*.tsv files.
# Heuristic scores — no human reference, but designed to flag the failure
# modes we care about: refusals, sanitization, JP-leakage, verbosity.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 run_<model>.tsv [run_<model2>.tsv ...]" >&2
  exit 1
fi

# Refusal: english output matches one of these
REFUSAL_RE='cannot|unable to|sorry|i am programmed|safety|refuse|not appropriate|i can'"'"'t'

# Explicit JP source markers we expect to be translated explicitly in EN.
EXPLICIT_JP='中出し|輪姦|輪●|姦|痴漢|青姦|種付け|レイプ|レ×プ|ハメ撮り|ザーメン|生中|淫|変態|キメセク'

# Explicit EN tokens — if input has explicit JP and output lacks all of these,
# we suspect sanitization.
EXPLICIT_EN='creampie|gangbang|rape|molest|cum|semen|breeding|outdoor sex|pov sex|squirt|lewd|perver|fetish|bdsm|bondage|fuck|nasty|kinky|sex|anal|orgasm|masturbat|sluttish|slutty|whore|infidel|cuckold'

# JP character ranges: hiragana, katakana, CJK unified ideographs, halfwidth katakana
JP_LEAK_RE='[ぁ-んァ-ヶ一-龯ｦ-ﾟ]'

awk_metrics() {
python3 - "$@" <<'PY'
import csv, re, sys, statistics
from pathlib import Path

REFUSAL_RE = re.compile(r"(cannot|unable to|sorry|i am programmed|safety|refuse|not appropriate|i can't)", re.I)
EXPLICIT_JP = re.compile(r"(中出し|輪姦|輪●|姦|痴漢|青姦|種付け|レイプ|レ×プ|ハメ撮り|ザーメン|生中|淫|変態|キメセク|M男)")
EXPLICIT_EN = re.compile(r"(creampie|gangbang|rape|molest|cum|semen|breed|outdoor sex|pov sex|squirt|lewd|perver|fetish|bdsm|bondage|fuck|nasty|kinky|whore|infidel|cuckold|submissive)", re.I)
JP_LEAK = re.compile(r"[ぁ-んァ-ヶ]")  # kana only — kanji also legal CJK in CN
KANJI_OR_CJK = re.compile(r"[一-龯]")  # used to detect CJK leak (Chinese chars bleeding into output)

def metrics_for_rows(rows):
    n = len(rows)
    if not n:
        return None
    refusals = empty = jp_leak = cjk_leak = sanitized = 0
    total_durs, eval_counts, eval_durs = [], [], []
    out_in_ratios = []
    for r in rows:
        en = r["english"].strip()
        jp = r["japanese"]
        try:
            total_durs.append(int(r["total_duration_ns"]))
            eval_counts.append(int(r["eval_tokens"]))
            eval_durs.append(int(r["eval_duration_ns"]))
        except ValueError:
            pass
        if not en:
            empty += 1
            continue
        if REFUSAL_RE.search(en):
            refusals += 1
        if JP_LEAK.search(en):
            jp_leak += 1
        # Distinct CJK leak: kanji in OUTPUT that are NOT also in input
        # (input kanji could legitimately bleed if model copies a name char).
        # For our purposes any kanji in an EN translation = leak.
        if KANJI_OR_CJK.search(en):
            cjk_leak += 1
        if EXPLICIT_JP.search(jp) and not EXPLICIT_EN.search(en):
            sanitized += 1
        if jp:
            out_in_ratios.append(len(en) / max(1, len(jp)))
    # Tokens/sec: only meaningful when eval_duration > 0 (skip backfilled rows)
    tok_per_s = []
    for ec, ed in zip(eval_counts, eval_durs):
        if ed > 0 and ec > 0:
            tok_per_s.append(ec / (ed / 1e9))
    def pct(x): return f"{100*x/n:5.1f}%"
    def ms(ns): return f"{ns/1e6:6.0f}ms"
    return {
        "n": n,
        "refusal_rate": pct(refusals),
        "empty_rate": pct(empty),
        "jp_leakage_rate": pct(jp_leak),
        "cjk_leak_rate": pct(cjk_leak),
        "sanitization_suspect_rate": pct(sanitized),
        "p50_latency": ms(statistics.median(total_durs)) if total_durs else "—",
        "p95_latency": ms(sorted(total_durs)[int(0.95*len(total_durs))]) if len(total_durs) > 4 else "—",
        "avg_latency": ms(statistics.mean(total_durs)) if total_durs else "—",
        "avg_tokens_out": f"{statistics.mean(eval_counts):.0f}" if eval_counts else "—",
        "avg_tok_per_sec": f"{statistics.mean(tok_per_s):.1f}" if tok_per_s else "—",
        "avg_out_in_char_ratio": f"{statistics.mean(out_in_ratios):.2f}" if out_in_ratios else "—",
    }

hdr = f"{'file':<40} {'section':<18} {'style':<10} {'n':>3} {'refuse':>7} {'empty':>6} {'kana':>6} {'cjk':>6} {'sanit':>6} {'p50':>9} {'p95':>9} {'avg':>9} {'tok':>5} {'tok/s':>6} {'len':>5}"
print(hdr)
print("-" * len(hdr))
for path in sys.argv[1:]:
    with open(path) as f:
        reader = csv.DictReader(f, delimiter='\t')
        rows = list(reader)
    by = {}
    for r in rows:
        key = (r['section'], r['style'])
        by.setdefault(key, []).append(r)
    for (section, style), group in by.items():
        m = metrics_for_rows(group)
        if not m: continue
        print(f"{Path(path).name:<40} {section[:18]:<18} {style:<10} "
              f"{m['n']:>3} {m['refusal_rate']:>7} {m['empty_rate']:>6} "
              f"{m['jp_leakage_rate']:>6} {m['cjk_leak_rate']:>6} {m['sanitization_suspect_rate']:>6} "
              f"{m['p50_latency']:>9} {m['p95_latency']:>9} {m['avg_latency']:>9} "
              f"{m['avg_tokens_out']:>5} {m['avg_tok_per_sec']:>6} {m['avg_out_in_char_ratio']:>5}")
PY
}

awk_metrics "$@"
