#!/usr/bin/env python3
"""
Read results.jsonl from audit.py and produce a consolidated Markdown report.

Output: REPORT.md in this folder.
"""

import json
from collections import defaultdict
from pathlib import Path

OUT = Path(__file__).parent
RESULTS = OUT / "results.jsonl"
REPORT = OUT / "REPORT.md"


def load():
    rows = []
    for line in RESULTS.read_text().splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def _path(h):
    return h.get("titlePath") or h.get("path") or "?"


def _code(h):
    return h.get("titleCode") or h.get("code") or "?"


def fmt_multi_cover(h):
    covers = h.get("covers", [])
    names = ", ".join(covers[:6]) + (" …" if len(covers) > 6 else "")
    return f"- `{_code(h)}` ({len(covers)} covers) at `{_path(h)}` · {names}"


def fmt_misfiled(h):
    sub = h.get("subfolder") or h.get("subdir") or "?"
    files = h.get("files") or h.get("covers") or []
    names = ", ".join(files[:4]) + (" …" if len(files) > 4 else "")
    return f"- `{_code(h)}` — cover(s) in `{sub}/` under `{_path(h)}` · {names}"


def main():
    rows = load()

    ghost_covers = []
    big_sets = []  # 6+ covers, not ghost
    for r in rows:
        for h in r.get("multi_cover", {}).get("hits", []):
            code = h.get("titleCode", "")
            n = len(h.get("covers", []))
            if code == "covers":
                ghost_covers.append((r["volumeId"], h.get("path") or h.get("titlePath"), n))
            elif n >= 6:
                big_sets.append((r["volumeId"], code, h.get("path") or h.get("titlePath"), n))

    lines = [
        "# Folder-Anomaly Audit — 2026-04-16",
        "",
        f"Audited {len(rows)} volumes via `find_multi_cover_titles` + `find_misfiled_covers`.",
        "",
        "## Key findings",
        "",
    ]
    if ghost_covers:
        lines.append(f"**Sync-parser bug — ghost 'covers' titles ({len(ghost_covers)}):** folders literally named `covers` that the parser indexed as if they were titles.")
        lines.append("")
        for vol, path, n in ghost_covers:
            lines.append(f"- `{vol}` — `{path}` ({n} images)")
        lines.append("")
    if big_sets:
        lines.append(f"**Large cover sets (≥6 covers, likely photobook layouts — {len(big_sets)}):** probably legit multi-cover series; physical review recommended.")
        lines.append("")
        for vol, code, path, n in big_sets:
            lines.append(f"- `{vol}` · `{code}` — {n} covers at `{path}`")
        lines.append("")
    lines.extend([
        "## Summary",
        "",
        "| Volume | Expected | Scanned | Multi-cover hits | Misfiled-cover hits | Errors | Status |",
        "|---|---:|---:|---:|---:|---:|---|",
    ])

    grand = {"expected": 0, "scanned": 0, "mc": 0, "mf": 0, "errors": 0}

    for r in rows:
        vol = r["volumeId"]
        if r.get("skipped"):
            lines.append(f"| {vol} | — | — | — | — | — | SKIPPED: {r.get('reason','')} |")
            continue
        mc = r.get("multi_cover", {})
        mf = r.get("misfiled_covers", {})
        scanned = max(mc.get("scanned", 0), mf.get("scanned", 0))
        mc_hits = len(mc.get("hits", []))
        mf_hits = len(mf.get("hits", []))
        errs = mc.get("errors", 0) + mf.get("errors", 0)
        note = r.get("scan_error", "ok")
        grand["expected"] += r.get("expected", 0)
        grand["scanned"] += scanned
        grand["mc"] += mc_hits
        grand["mf"] += mf_hits
        grand["errors"] += errs
        lines.append(
            f"| {vol} | {r.get('expected','?')} | {scanned} | {mc_hits} | {mf_hits} | {errs} | {note} |"
        )

    lines.append(
        f"| **TOTAL** | **{grand['expected']}** | **{grand['scanned']}** | "
        f"**{grand['mc']}** | **{grand['mf']}** | **{grand['errors']}** | |"
    )
    lines.append("")

    # Per-volume detail
    for r in rows:
        vol = r["volumeId"]
        if r.get("skipped"):
            continue
        mc_hits = r.get("multi_cover", {}).get("hits", [])
        mf_hits = r.get("misfiled_covers", {}).get("hits", [])
        if not mc_hits and not mf_hits:
            continue
        lines.append(f"## {vol}")
        lines.append("")
        if mc_hits:
            lines.append(f"### Multi-cover ({len(mc_hits)})")
            lines.append("")
            for h in mc_hits:
                lines.append(fmt_multi_cover(h))
            lines.append("")
        if mf_hits:
            lines.append(f"### Misfiled covers ({len(mf_hits)})")
            lines.append("")
            for h in mf_hits:
                lines.append(fmt_misfiled(h))
            lines.append("")

    REPORT.write_text("\n".join(lines) + "\n")
    print(f"Wrote {REPORT} ({len(lines)} lines)")


if __name__ == "__main__":
    main()
