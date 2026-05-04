#!/usr/bin/env python3
"""Parse the existing markdown POC outputs into TSV files compatible with score.sh.

Token-count columns are 0 (we didn't capture those in the early runs).
Section + style are inferred from headings."""
import re, sys
from pathlib import Path

POC = Path("/Users/pyoung/workspace/organizer3/reference/translation_poc")

# (input_md, output_tsv, default_style, header_to_section_map)
JOBS = [
    ("results.md",                       "run_gemma4_e4b_basic.tsv",         "basic",
     {"Title originals (15)": "Titles (15)",
      "Makers (10)": "Makers (10)",
      "Series (10)": "Series (10)"}),
    ("retry_gemma4_e4b.md",              "run_gemma4_e4b_hardened.tsv",      "hardened",
     None),  # whole file = single section
    ("results_translategemma_12b.md",    "run_translategemma_12b_basic.tsv", "basic",
     {"Title originals (15)": "Titles (15)",
      "Makers (10)": "Makers (10)",
      "Series (10)": "Series (10)"}),
]

ROW_RE = re.compile(r"^\|\s*(\d+)\s*\|\s*(.+?)\s*\|\s*(.*?)\s*\|\s*$")
H2_RE = re.compile(r"^##\s+(.+)$")

def parse(md_path: Path, default_style: str, section_map):
    rows = []
    current = "Titles (15)"  # default for retry file
    with md_path.open() as f:
        for line in f:
            line = line.rstrip("\n")
            mh = H2_RE.match(line)
            if mh:
                heading = mh.group(1).strip()
                if section_map and heading in section_map:
                    current = section_map[heading]
                continue
            m = ROW_RE.match(line)
            if not m: continue
            ms_raw, jp, en = m.groups()
            # Skip header/separator rows of markdown tables
            if jp in ("Japanese", "----------"): continue
            try:
                ms = int(ms_raw)
            except ValueError:
                continue
            total_dur_ns = ms * 1_000_000
            rows.append((current, default_style, jp, en, total_dur_ns, 0, 0, 0))
    return rows

for inp, outp, style, smap in JOBS:
    src = POC / inp
    if not src.exists():
        print(f"skip {inp} (not found)", file=sys.stderr); continue
    rows = parse(src, style, smap)
    out = POC / outp
    with out.open("w") as f:
        f.write("section\tstyle\tjapanese\tenglish\ttotal_duration_ns\tprompt_tokens\teval_tokens\teval_duration_ns\n")
        for r in rows:
            f.write("\t".join(str(x) for x in r) + "\n")
    print(f"wrote {outp} ({len(rows)} rows)")
