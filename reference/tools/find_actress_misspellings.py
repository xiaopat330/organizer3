#!/usr/bin/env python3
"""
Find likely misspelled/duplicate actress names in the DB.

Detection passes:
  1. Name-order swaps: "Given Family" appears as "Family Given" (Japanese vs Western order)
  2. Low-title orphans: actresses with 1-2 titles - likely a misspelling that created a new entry
  3. Same-surname, compare given names (dist ≤ 2, normalized)
  4. Same-given-name, compare surnames (dist ≤ 1, normalized)

Romanization normalization collapses:
  ou/oo/oh → o, uu → u, aa → a, tsu → tu, shi → si, chi → ci
so "Ryou/Ryo", "Yuuki/Yuki", etc. score as near-equal.
"""

import sqlite3
from collections import defaultdict

DB_PATH = "/Users/pyoung/.organizer3/organizer.db"


# --- Edit distance ---

def levenshtein(a, b):
    if a == b:
        return 0
    if len(a) > len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        curr = [i]
        for j, cb in enumerate(b, 1):
            curr.append(min(prev[j] + 1, curr[j-1] + 1, prev[j-1] + (ca != cb)))
        prev = curr
    return prev[-1]


ROMAN_SUBS = [
    ('ou', 'o'), ('oo', 'o'), ('oh', 'o'),
    ('uu', 'u'), ('ii', 'i'), ('aa', 'a'),
    ('tsu', 'tu'), ('shi', 'si'), ('chi', 'ci'),
]

def normalize(s):
    n = s.lower().replace('  ', ' ').strip()
    for pat, rep in ROMAN_SUBS:
        n = n.replace(pat, rep)
    return n


def ndist(a, b):
    return levenshtein(normalize(a), normalize(b))


# --- Data loading ---

def load_data():
    con = sqlite3.connect(DB_PATH)
    names = con.execute("SELECT id, canonical_name FROM actresses ORDER BY canonical_name").fetchall()
    title_counts = {
        row[0]: row[1]
        for row in con.execute(
            "SELECT actress_id, COUNT(*) FROM titles WHERE actress_id IS NOT NULL GROUP BY actress_id"
        ).fetchall()
    }
    # Also get titles-per-actress as a list for orphan display
    orphan_titles = {
        row[0]: row[1]
        for row in con.execute(
            "SELECT actress_id, GROUP_CONCAT(code, ', ') FROM titles WHERE actress_id IS NOT NULL GROUP BY actress_id HAVING COUNT(*) <= 3"
        ).fetchall()
    }
    con.close()
    return names, title_counts, orphan_titles


def split_name(name):
    parts = name.strip().split()
    if len(parts) >= 2:
        return parts[0], ' '.join(parts[1:])
    return name, ''


# --- Pass 1: Name-order swaps ---

def find_swaps(names):
    """Detect 'Given Family' vs 'Family Given' — same actress, word order reversed."""
    name_set = {name.lower(): (id_, name) for id_, name in names}
    results = []
    seen = set()

    for id_a, name_a in names:
        parts = name_a.strip().split()
        if len(parts) != 2:
            continue
        swapped = f"{parts[1]} {parts[0]}".lower()
        if swapped in name_set and swapped != name_a.lower():
            id_b, name_b = name_set[swapped]
            key = tuple(sorted([id_a, id_b]))
            if key not in seen:
                seen.add(key)
                results.append((name_a, name_b, id_a, id_b))

    return results


# --- Pass 2: Low-title orphans ---

def find_orphans(names, title_counts, threshold=2):
    """Actresses with <= threshold titles — likely a misspelling that created a stray entry."""
    results = []
    for id_, name in names:
        tc = title_counts.get(id_, 0)
        if 1 <= tc <= threshold:
            results.append((tc, id_, name))
    results.sort(key=lambda x: (x[0], x[2]))
    return results


# --- Pass 3: Same-surname, compare given names ---

def find_same_surname(names, max_dist=2):
    by_surname = defaultdict(list)
    for id_, name in names:
        given, family = split_name(name)
        if family:
            by_surname[family.lower()].append((id_, name, given))

    results = []
    seen = set()
    for group in by_surname.values():
        if len(group) < 2:
            continue
        for i in range(len(group)):
            for j in range(i + 1, len(group)):
                id_a, name_a, given_a = group[i]
                id_b, name_b, given_b = group[j]
                if abs(len(given_a) - len(given_b)) > max_dist + 2:
                    continue
                d = ndist(given_a, given_b)
                if 0 < d <= max_dist:
                    key = tuple(sorted([id_a, id_b]))
                    if key not in seen:
                        seen.add(key)
                        results.append((d, name_a, name_b, id_a, id_b))
    results.sort(key=lambda x: (x[0], x[1].split()[-1], x[1]))
    return results


# --- Pass 4: Same-given-name, compare surnames ---

def find_same_given(names, max_dist=1):
    by_given = defaultdict(list)
    for id_, name in names:
        given, family = split_name(name)
        if family:
            by_given[given.lower()].append((id_, name, family))

    results = []
    seen = set()
    for group in by_given.values():
        if len(group) < 2:
            continue
        for i in range(len(group)):
            for j in range(i + 1, len(group)):
                id_a, name_a, fam_a = group[i]
                id_b, name_b, fam_b = group[j]
                if abs(len(fam_a) - len(fam_b)) > max_dist + 1:
                    continue
                d = ndist(fam_a, fam_b)
                if 0 < d <= max_dist:
                    key = tuple(sorted([id_a, id_b]))
                    if key not in seen:
                        seen.add(key)
                        results.append((d, name_a, name_b, id_a, id_b))
    results.sort(key=lambda x: (x[0], x[1].split()[0], x[1]))
    return results


# --- Output helpers ---

def tc_str(title_counts, id_):
    n = title_counts.get(id_, 0)
    return f"{n:>4}"


def print_section(title, items):
    print(f"\n{'='*80}")
    print(f"  {title}")
    print(f"{'='*80}")
    if not items:
        print("  (none)")


# --- Main ---

def main():
    print("Loading data...")
    names, title_counts, orphan_titles = load_data()
    print(f"  {len(names)} actresses, {sum(1 for v in title_counts.values() if v)} with titles")

    # --- Pass 1: Swaps ---
    swaps = find_swaps(names)
    print_section(f"PASS 1: Name-order swaps ({len(swaps)} found)", swaps)
    if swaps:
        print(f"  {'Name A':<32}  {'Name B':<32}  {'TA':>4}  {'TB':>4}")
        print(f"  {'-'*32}  {'-'*32}  {'-'*4}  {'-'*4}")
        for name_a, name_b, id_a, id_b in swaps:
            print(f"  {name_a:<32}  {name_b:<32}  {tc_str(title_counts,id_a)}  {tc_str(title_counts,id_b)}")

    # --- Pass 2: Orphans ---
    orphans = find_orphans(names, title_counts, threshold=2)
    print_section(f"PASS 2: Low-title orphans (1-2 titles, {len(orphans)} found)", orphans)
    print(f"  {'Titles':>6}  {'Name':<34}  Codes")
    print(f"  {'-'*6}  {'-'*34}  {'-'*40}")
    for tc, id_, name in orphans:
        codes = orphan_titles.get(id_, '')
        print(f"  {tc:>6}  {name:<34}  {codes}")

    # --- Pass 3: Same-surname ---
    ss_pairs = find_same_surname(names, max_dist=2)
    print_section(f"PASS 3: Same surname, similar given name ({len(ss_pairs)} pairs)", ss_pairs)
    print(f"  {'D':>1}  {'Name A':<32}  {'Name B':<32}  {'TA':>4}  {'TB':>4}")
    print(f"  {'-'*1}  {'-'*32}  {'-'*32}  {'-'*4}  {'-'*4}")
    for d, name_a, name_b, id_a, id_b in ss_pairs:
        flag = ' <--' if d == 1 and (title_counts.get(id_a,0) + title_counts.get(id_b,0)) > 5 else ''
        print(f"  {d}  {name_a:<32}  {name_b:<32}  {tc_str(title_counts,id_a)}  {tc_str(title_counts,id_b)}{flag}")

    # --- Pass 4: Same-given-name ---
    sg_pairs = find_same_given(names, max_dist=1)
    print_section(f"PASS 4: Same given name, similar surname ({len(sg_pairs)} pairs)", sg_pairs)
    print(f"  {'D':>1}  {'Name A':<32}  {'Name B':<32}  {'TA':>4}  {'TB':>4}")
    print(f"  {'-'*1}  {'-'*32}  {'-'*32}  {'-'*4}  {'-'*4}")
    for d, name_a, name_b, id_a, id_b in sg_pairs:
        flag = ' <--' if title_counts.get(id_a,0) + title_counts.get(id_b,0) > 5 else ''
        print(f"  {d}  {name_a:<32}  {name_b:<32}  {tc_str(title_counts,id_a)}  {tc_str(title_counts,id_b)}{flag}")

    print(f"\n{'='*80}")
    print("Summary:")
    print(f"  Swaps:         {len(swaps)}")
    print(f"  Orphans:       {len(orphans)}")
    print(f"  Same-surname:  {len(ss_pairs)}")
    print(f"  Same-given:    {len(sg_pairs)}")
    print("\n<-- markers = dist=1 pair with substantial titles on at least one side")
    print("Redirect to file: python3 find_actress_misspellings.py > results.txt")


if __name__ == '__main__':
    main()
