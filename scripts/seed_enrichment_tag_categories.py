#!/usr/bin/env python3
"""
Seed enrichment_tag_definitions.category for UNMAPPED rows (curated_alias IS NULL).

Rows WITH a curated_alias inherit their category from the curated tag's
category (owned by the alias — set by scripts/seed_enrichment_tag_aliases.py).
This script handles the complementary case: enrichment tags that have NO
curated_alias and therefore need a hand-curated category so the Library
tag-filter picker can group them like the curated section.

The hand map below MUST NEVER override an alias-owned category — every UPDATE
is guarded with `AND curated_alias IS NULL`.

Default mode applies the map. Use --dry-run to print what would change without
writing. The map is tiny and hand-curated, so there's no propose/review
round-trip (unlike the alias script).
"""

import argparse
import sqlite3
import sys
from pathlib import Path
from typing import Dict

# The 7 allowed categories (mirrors tags.category / TagCatalogLoader).
ALLOWED_CATEGORIES = {
    "format",
    "production_style",
    "setting",
    "role",
    "theme",
    "act",
    "body",
}

# Enrichment-tag name (EXACTLY as it appears in the `name` column) → category.
# These names are verified against the DB. Only rows WITHOUT a curated_alias
# are touched (see the guarded UPDATE in apply_categories).
CATEGORY_MAP: Dict[str, str] = {
    "Slut":                 "theme",
    "3p, 4p":               "act",
    "Slender":              "body",
    "Nasty, Hardcore":      "theme",
    "Cuckold":              "theme",
    "Abuse":                "theme",
    "Promiscuity":          "theme",
    "Handjob":              "act",
    "Rape":                 "theme",
    "Uniform":              "body",
    "Shaved":               "body",
    "Humiliation":          "theme",
    "Prostitutes":          "role",
    "Beauty Shop":          "setting",
    "Insult":               "theme",
    "Idol":                 "role",
    "Finger Fuck":          "act",
    "Pantyhose":            "body",
    "College Students":     "role",
    "Cunnilingus":          "act",
    "Submissive Men":       "theme",
    "Entertainer":          "role",
    "Urination":            "act",
    "Virgin Man":           "role",
    "Masturbation":         "act",
    "Various Professions":  "role",
    "Big cock":             "body",
    "Lingerie":             "body",
    "69":                   "act",
    "Footjob":              "act",
    "Molester":             "theme",
}


def validate_map() -> None:
    """Fail fast if any category value is outside the allowed set."""
    bad = {name: cat for name, cat in CATEGORY_MAP.items() if cat not in ALLOWED_CATEGORIES}
    if bad:
        print("ERROR: CATEGORY_MAP contains invalid category values:", file=sys.stderr)
        for name, cat in bad.items():
            print(f"    {name!r} -> {cat!r} (allowed: {sorted(ALLOWED_CATEGORIES)})", file=sys.stderr)
        sys.exit(2)


def apply_categories(db_path: Path, dry_run: bool = False) -> None:
    con = sqlite3.connect(str(db_path))
    try:
        applied, skipped = 0, 0
        for name, cat in CATEGORY_MAP.items():
            if dry_run:
                # Would this UPDATE change anything? Matches the guarded predicate.
                row = con.execute(
                    "SELECT category FROM enrichment_tag_definitions "
                    "WHERE name = ? AND curated_alias IS NULL",
                    (name,)).fetchone()
                if row is None:
                    skipped += 1
                    print(f"  SKIP   {name!r:24} (not in DB or has curated_alias)")
                elif row[0] == cat:
                    print(f"  same   {name!r:24} -> {cat} (no change)")
                else:
                    applied += 1
                    print(f"  CHANGE {name!r:24} {row[0]!r} -> {cat!r}")
                continue
            r = con.execute(
                "UPDATE enrichment_tag_definitions SET category = ? "
                "WHERE name = ? AND curated_alias IS NULL",
                (cat, name)).rowcount
            if r:
                applied += 1
            else:
                skipped += 1
        if not dry_run:
            con.commit()
        verb = "Would apply" if dry_run else "Applied"
        print(f"{verb} {applied} categories. {skipped} skipped "
              f"(name not in DB or row had a curated_alias).")
    finally:
        con.close()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db", default=str(Path.home() / ".organizer3" / "organizer.db"),
                   help="Path to organizer SQLite database (default: ~/.organizer3/organizer.db)")
    p.add_argument("--dry-run", action="store_true",
                   help="Print what would change without writing to the DB")
    args = p.parse_args()

    db = Path(args.db)
    if not db.exists():
        print(f"DB not found: {db}", file=sys.stderr)
        sys.exit(2)

    validate_map()
    apply_categories(db, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
