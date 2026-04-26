#!/usr/bin/env python3
"""
Seed enrichment_tag_definitions.curated_alias from reference/tags.yaml.

Two modes:

  propose  (default)  — Read enrichment_tag_definitions + reference/tags.yaml,
                        emit a YAML proposal at OUT_PATH. Review and edit by hand.

  --apply OUT_PATH    — Read the (reviewed) YAML and write curated_alias values
                        into the DB. Skips entries with curated_alias: null.

The proposal has three sections:
  - high_confidence: exact normalized matches + hand-curated semantic equivalents.
                     Safe to apply as-is.
  - review:          medium-confidence guesses needing human judgment. Set the
                     curated_alias field, or set it to null to skip.
  - unmapped:        no candidate found. Add a mapping by hand if appropriate.

Per the proposal in spec/PROPOSAL_TAG_SYSTEM_REVAMP.md, this mapping is
load-bearing — without curated_alias values, enrichment-tag queries can never
reach beyond the ~5–10% enriched subset of the library.
"""

import argparse
import re
import sqlite3
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# Hand-curated semantic equivalents the script can apply with high confidence
# even when normalized strings don't match. Keys are enrichment-tag names as
# they appear in javdb extracts; values are curated tag names from
# reference/tags.yaml.
#
# Conservative: only mappings where the meaning is unambiguous. Edge cases
# go in the "review" section so you can decide.
SEMANTIC_MAP: Dict[str, str] = {
    # Body / appearance
    "Big Tits":            "busty",
    "Busty Fetish":        "busty",
    "Slender":             "petite",         # imperfect — could also be "athletic"; flagged
    "Tall":                "athletic",       # weak — better in review
    # Acts
    "Titty Fuck":          "paizuri",
    "Facials":             "facial",
    "Deep Throating":      "deepthroat",
    "Blow":                "blowjob",
    "Handjob":             "blowjob",        # imperfect — flag
    "Footjob":             "blowjob",        # imperfect — flag
    "Dirty Words":         "dirty-talk",
    # Production
    "Solowork":            "solo-actress",
    "Debut Production":    "debut",
    "Best, Omnibus":       "compilation",
    "Image Video":         "gravure",
    "Drama":               "drama",
    "Planning":            "planning",
    "Subjectivity":        "pov",
    # Setting / role
    "OL":                  "office-lady",
    "Stewardess":          "office-lady",    # weak — flag
    "Female Doctor":       "nurse",          # weak — no doctor tag exists
    "Female Teacher":      "teacher",
    "School Girls":        "schoolgirl",
    "School Uniform":      "schoolgirl",
    "Sailor Suit":         "schoolgirl",
    "Hostesses":           "hostess",
    "Massage":             "massage-parlor",
    "Beauty Shop":         "massage-parlor", # weak — flag
    # Costume
    "Yukata":              "cosplay",
    "Kimono, Mourning":    "cosplay",
    "Sun tan":             "gyaru",          # weak — flag
    # Situational
    "Cuckold":             "violation",      # weak — flag
    "Restraint":           "sm-bdsm",
    "Restraints":          "sm-bdsm",
    "SM":                  "sm-bdsm",
    # Format / technical (no curated equivalents — listed here so they get categorised, not aliased)
}

# Mappings the script proposes for review (medium confidence). The script will
# include these in the "review" section with the proposed alias filled in;
# the reviewer can keep, change, or null them out.
REVIEW_MAP: Dict[str, str] = {
    "Tall":                "athletic",
    "Slender":             "petite",
    "Stewardess":          "office-lady",
    "Female Doctor":       "nurse",
    "Sun tan":             "gyaru",
    "Cuckold":             "violation",
    "Beauty Shop":         "massage-parlor",
    "Handjob":             "blowjob",
    "Footjob":             "blowjob",
    "Older Sister":        "sister",
    "Bride, Young Wife":   "married-woman",
    "Lingerie":            "braless",        # weak
    "Glasses":             None,             # no curated equivalent — included for visibility
    "Idol":                "celebrity-crossover",
    "Entertainer":         "celebrity-crossover",
}


def normalize(s: str) -> str:
    s = s.lower().strip()
    s = re.sub(r"[\s_]+", "-", s)
    s = re.sub(r"[^a-z0-9-]", "", s)
    return s


def load_curated_tags(yaml_path: Path) -> List[str]:
    """Extract just the `name:` strings from reference/tags.yaml."""
    names = []
    with yaml_path.open() as f:
        for line in f:
            m = re.match(r"\s+- name:\s+(\S+)", line)
            if m:
                names.append(m.group(1))
    return names


def load_enrichment_tags(db_path: Path) -> List[Tuple[str, int]]:
    """Returns [(name, title_count), ...] sorted by frequency desc."""
    con = sqlite3.connect(str(db_path))
    try:
        return [(name, cnt) for (name, cnt) in con.execute(
            "SELECT name, title_count FROM enrichment_tag_definitions ORDER BY title_count DESC, name")]
    finally:
        con.close()


def categorize(enrichment: List[Tuple[str, int]],
               curated: List[str]) -> Dict[str, List[Tuple[str, int, Optional[str]]]]:
    """Bucket each enrichment tag into high_confidence / review / unmapped."""
    curated_set = set(curated)
    high, review, unmapped = [], [], []
    # The hand-curated REVIEW_MAP overrides SEMANTIC_MAP into the review bucket
    # (so weak/ambiguous entries are flagged for human attention rather than auto-applied).
    review_keys = set(REVIEW_MAP.keys())
    for name, cnt in enrichment:
        if name in review_keys:
            review.append((name, cnt, REVIEW_MAP[name]))
            continue
        norm = normalize(name)
        if norm in curated_set:
            high.append((name, cnt, norm))
            continue
        if name in SEMANTIC_MAP and SEMANTIC_MAP[name] in curated_set:
            high.append((name, cnt, SEMANTIC_MAP[name]))
            continue
        unmapped.append((name, cnt, None))
    return {"high_confidence": high, "review": review, "unmapped": unmapped}


def emit_yaml(buckets: Dict[str, List[Tuple[str, int, Optional[str]]]], out_path: Path) -> None:
    """Write the proposal as plain YAML (no PyYAML dependency)."""
    lines = [
        "# Enrichment tag → curated tag aliases — proposal generated by",
        "# scripts/seed_enrichment_tag_aliases.py.",
        "#",
        "# Edit the `review` and `unmapped` sections by hand:",
        "#   - To accept a proposed alias in `review`, leave it as-is or change",
        "#     curated_alias to a different curated tag.",
        "#   - To skip a mapping entirely, set curated_alias: null.",
        "#   - To map an `unmapped` entry, replace null with the curated tag name.",
        "#",
        "# Then apply with:",
        "#   python3 scripts/seed_enrichment_tag_aliases.py --apply <this-file>",
        "",
    ]
    for section, entries in buckets.items():
        lines.append(f"{section}:")
        if not entries:
            lines.append("  []")
            continue
        for name, cnt, alias in entries:
            lines.append(f"  - enrichment_tag: {yaml_str(name)}")
            lines.append(f"    title_count: {cnt}")
            lines.append(f"    curated_alias: {yaml_str(alias) if alias else 'null'}")
        lines.append("")
    out_path.write_text("\n".join(lines))
    print(f"Wrote proposal to {out_path}")
    print(f"  high_confidence: {len(buckets['high_confidence'])} entries (safe to apply)")
    print(f"  review:          {len(buckets['review'])} entries (need review)")
    print(f"  unmapped:        {len(buckets['unmapped'])} entries (no candidate)")


def yaml_str(s: str) -> str:
    """Conservative YAML string quoting — always quote so commas/Title Case round-trip."""
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


def parse_yaml_proposal(path: Path, only_section: Optional[str] = None) -> List[Tuple[str, str]]:
    """Minimal parser for the format we emitted. Returns [(enrichment_tag, curated_alias), ...]
    excluding entries with curated_alias: null. When only_section is set, only entries inside
    that top-level section are returned."""
    pairs = []
    cur_name = None
    cur_section = None
    with path.open() as f:
        for raw in f:
            line = raw.rstrip("\n")
            if line.lstrip().startswith("#") or not line.strip():
                continue
            sm = re.match(r"^([A-Za-z_]+):\s*$", line)
            if sm:
                cur_section = sm.group(1)
                cur_name = None
                continue
            if only_section is not None and cur_section != only_section:
                continue
            m = re.match(r'\s+- enrichment_tag:\s+"((?:[^"\\]|\\.)*)"\s*$', line)
            if m:
                cur_name = m.group(1).replace('\\"', '"').replace("\\\\", "\\")
                continue
            m = re.match(r'\s+curated_alias:\s+(.+?)\s*$', line)
            if m and cur_name is not None:
                val = m.group(1)
                if val == "null":
                    cur_name = None
                    continue
                vm = re.match(r'"((?:[^"\\]|\\.)*)"$', val)
                alias = vm.group(1).replace('\\"', '"').replace("\\\\", "\\") if vm else val
                pairs.append((cur_name, alias))
                cur_name = None
    return pairs


def apply_aliases(db_path: Path, proposal_path: Path, only_section: Optional[str] = None) -> None:
    pairs = parse_yaml_proposal(proposal_path, only_section=only_section)
    if not pairs:
        print("No mappings found in proposal (everything is null) — nothing to do.")
        return
    con = sqlite3.connect(str(db_path))
    try:
        # Verify each curated alias exists in the tags table (FK-style sanity check).
        curated_names = {row[0] for row in con.execute("SELECT name FROM tags")}
        # ... but tags.yaml may not all be loaded into the tags table; warn rather than fail.
        applied, skipped, missing_curated = 0, 0, []
        for enr, alias in pairs:
            if alias not in curated_names:
                missing_curated.append((enr, alias))
            r = con.execute(
                "UPDATE enrichment_tag_definitions SET curated_alias = ? WHERE name = ?",
                (alias, enr)).rowcount
            if r:
                applied += 1
            else:
                skipped += 1
        con.commit()
        print(f"Applied {applied} aliases. {skipped} skipped (enrichment tag not in DB).")
        if missing_curated:
            print(f"WARNING: {len(missing_curated)} curated_alias values are not present in the tags table.")
            print("  These FK-style references will be empty until the curated tag is loaded.")
            for enr, alias in missing_curated[:10]:
                print(f"    {enr!r} → {alias!r}")
            if len(missing_curated) > 10:
                print(f"    ... and {len(missing_curated) - 10} more")
    finally:
        con.close()


def report_diff(db_path: Path, prior_path: Path) -> None:
    """Compare the current DB to a prior proposal and print what's new.

    Reports:
      - tags newly present in the DB but absent from the prior proposal
      - tags whose title_count has grown >= 50% (signal that vocabulary is
        shifting — may warrant a re-look at curated_alias quality)
    """
    if not prior_path.exists():
        print(f"Prior proposal not found: {prior_path}", file=sys.stderr)
        sys.exit(2)
    # Parse prior proposal — capture name + title_count from any section.
    prior = {}  # name -> title_count
    cur_name = None
    cur_count = None
    with prior_path.open() as f:
        for raw in f:
            line = raw.rstrip("\n")
            if line.lstrip().startswith("#") or not line.strip():
                continue
            m = re.match(r'\s+- enrichment_tag:\s+"((?:[^"\\]|\\.)*)"\s*$', line)
            if m:
                if cur_name is not None and cur_count is not None:
                    prior[cur_name] = cur_count
                cur_name = m.group(1).replace('\\"', '"').replace("\\\\", "\\")
                cur_count = None
                continue
            m = re.match(r'\s+title_count:\s+(\d+)\s*$', line)
            if m and cur_name is not None:
                cur_count = int(m.group(1))
        if cur_name is not None and cur_count is not None:
            prior[cur_name] = cur_count

    current = dict(load_enrichment_tags(db_path))
    newly_added = sorted(
        [(n, c) for n, c in current.items() if n not in prior],
        key=lambda x: -x[1])
    grown = sorted(
        [(n, prior[n], c) for n, c in current.items()
         if n in prior and prior[n] > 0 and (c - prior[n]) / prior[n] >= 0.5],
        key=lambda x: -(x[2] - x[1]))

    print(f"Diff against {prior_path.name}:")
    print(f"  prior had {len(prior)} tag definitions, current has {len(current)}")
    print()
    if newly_added:
        print(f"NEW TAGS ({len(newly_added)} tags absent from prior proposal):")
        for n, c in newly_added:
            print(f"  {n!r:35} count={c}")
        print()
    else:
        print("No new tag names since last run.\n")
    if grown:
        print(f"GROWN TAGS ({len(grown)} tags with >=50% count growth):")
        for n, before, now in grown:
            print(f"  {n!r:35} {before} → {now} (+{now-before})")
        print()
    else:
        print("No significant count growth on existing tags.")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db",    default=str(Path.home() / ".organizer3" / "organizer.db"),
                   help="Path to organizer SQLite database (default: ~/.organizer3/organizer.db)")
    p.add_argument("--yaml",  default=str(Path(__file__).parent.parent / "reference" / "tags.yaml"),
                   help="Path to curated tags YAML")
    p.add_argument("--out",   default=str(Path(__file__).parent.parent / "reference" / "enrichment_tag_aliases.proposed.yaml"),
                   help="Where to write the proposal in propose mode")
    p.add_argument("--apply", metavar="PROPOSAL_FILE",
                   help="Apply the (reviewed) proposal to the DB instead of generating one")
    p.add_argument("--only-section", metavar="SECTION", default=None,
                   help="When applying, only consider entries inside this top-level section "
                        "(e.g. 'high_confidence' to skip the review section)")
    p.add_argument("--diff", metavar="PRIOR_PROPOSAL", default=None,
                   help="Compare current DB state against a previous proposal file and "
                        "print only what's NEW (added unmapped tags, count growth >= 50%%). "
                        "Useful for spotting drift after a batch of new enrichments.")
    args = p.parse_args()

    db = Path(args.db)
    if not db.exists():
        print(f"DB not found: {db}", file=sys.stderr)
        sys.exit(2)

    if args.apply:
        apply_aliases(db, Path(args.apply), only_section=args.only_section)
        return

    if args.diff:
        report_diff(db, Path(args.diff))
        return

    yaml_path = Path(args.yaml)
    if not yaml_path.exists():
        print(f"Curated tags YAML not found: {yaml_path}", file=sys.stderr)
        sys.exit(2)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    curated = load_curated_tags(yaml_path)
    enrichment = load_enrichment_tags(db)
    buckets = categorize(enrichment, curated)
    emit_yaml(buckets, out)


if __name__ == "__main__":
    main()
