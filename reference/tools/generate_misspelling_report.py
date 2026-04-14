#!/usr/bin/env python3
"""
Generate a human-readable review report of likely actress name misspellings.

For each suspect pair, shows:
  - Both actress names + title counts
  - SMB paths for titles belonging to the suspect (low-count) entry
  - Suggested correct name

Sections:
  1. Name-order swaps ("Given Family" vs "Family Given")
  2. Same-surname, dist=1 given name — one side has ≤3 titles
  3. Same-given-name, dist=1 surname — one side has ≤3 titles
"""

import sqlite3
from collections import defaultdict

DB_PATH = "/Users/pyoung/.organizer3/organizer.db"

# Volume ID → SMB base path (from organizer-config.yaml)
VOLUME_SMB = {
    'a':            '//pandora/jav_A',
    'bg':           '//pandora/jav_BG',
    'hj':           '//pandora/jav_HJ',
    'k':            '//pandora/jav_K',
    'm':            '//pandora/jav_M',
    'ma':           '//pandora/jav_MA',
    'n':            '//pandora/jav_N',
    'r':            '//pandora/jav_OR',
    's':            '//pandora/jav_S',
    'tz':           '//pandora/jav_TZ',
    'unsorted':     '//pandora/jav_unsorted',
    'qnap':         '//qnap2/jav',
    'qnap_archive': '//pandora/qnap_archive',
    'classic':      '//qnap2/JAV/classic',
    'pool':         '//pandora/jav_unsorted/_done',
    'classic_pool': '//qnap2/JAV/classic/new',
    'collections':  '//pandora/jav_collections',
}


# --- Edit distance + normalization ---

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
    n = s.lower().strip()
    for pat, rep in ROMAN_SUBS:
        n = n.replace(pat, rep)
    return n

def ndist(a, b):
    return levenshtein(normalize(a), normalize(b))


# --- Data loading ---

def load_data():
    con = sqlite3.connect(DB_PATH)

    names = {
        row[0]: row[1]
        for row in con.execute("SELECT id, canonical_name FROM actresses").fetchall()
    }

    # Count titles via both the FK column and the many-to-many junction table.
    # titles.actress_id = "filing" actress (parsed from folder name, single actress).
    # title_actresses = many-to-many (multi-cast or cross-referenced).
    title_counts = {
        row[0]: row[1]
        for row in con.execute("""
            SELECT actress_id, COUNT(*) FROM (
                SELECT actress_id FROM titles WHERE actress_id IS NOT NULL
                UNION ALL
                SELECT actress_id FROM title_actresses
            ) GROUP BY actress_id
        """).fetchall()
    }

    # For each actress, get their filing titles + locations (titles.actress_id only).
    # These are the folder paths on the server that would carry the actress name.
    title_locations = defaultdict(list)
    rows = con.execute("""
        SELECT t.actress_id, t.code, tl.volume_id, tl.path
        FROM titles t
        JOIN title_locations tl ON tl.title_id = t.id
        WHERE t.actress_id IS NOT NULL
        ORDER BY t.actress_id, t.code
    """).fetchall()
    for actress_id, code, volume_id, path in rows:
        smb_base = VOLUME_SMB.get(volume_id, f'//{volume_id}')
        smb_path = smb_base + path
        title_locations[actress_id].append((code, smb_path))

    con.close()
    return names, title_counts, title_locations


def split_name(name):
    parts = name.strip().split()
    if len(parts) >= 2:
        return parts[0], ' '.join(parts[1:])
    return name, ''


def tc(title_counts, id_):
    return title_counts.get(id_, 0)


def smb_lines(title_locations, actress_id, title_counts, indent='      '):
    locs = title_locations.get(actress_id, [])
    if not locs:
        n = title_counts.get(actress_id, 0)
        if n == 0:
            return [f'{indent}(0 titles in DB — DB record only, no files on server to rename)']
        else:
            return [f'{indent}(WARNING: {n} titles in DB but no file locations found — sync may be needed)']
    return [f'{indent}{smb_path}' for _, smb_path in locs]


# --- Detection passes ---

def find_swaps(names):
    name_lower = {name.lower(): id_ for id_, name in names.items()}
    results = []
    seen = set()
    for id_a, name_a in names.items():
        parts = name_a.strip().split()
        if len(parts) != 2:
            continue
        swapped_key = f"{parts[1]} {parts[0]}".lower()
        if swapped_key in name_lower and swapped_key != name_a.lower():
            id_b = name_lower[swapped_key]
            name_b = names[id_b]
            key = tuple(sorted([id_a, id_b]))
            if key not in seen:
                seen.add(key)
                results.append((id_a, name_a, id_b, name_b))
    return results


def find_surname_pairs(names, title_counts, max_dist=1, suspect_threshold=3):
    """Same surname, dist<=max_dist given name, one side has <=suspect_threshold titles."""
    by_surname = defaultdict(list)
    for id_, name in names.items():
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
                ta, tb = tc(title_counts, id_a), tc(title_counts, id_b)
                # One must be "suspect" (few titles), other must have some
                if not ((ta <= suspect_threshold < tb) or (tb <= suspect_threshold < ta)):
                    continue
                if abs(len(given_a) - len(given_b)) > max_dist + 1:
                    continue
                d = ndist(given_a, given_b)
                if 0 < d <= max_dist:
                    key = tuple(sorted([id_a, id_b]))
                    if key not in seen:
                        seen.add(key)
                        results.append((d, id_a, name_a, ta, id_b, name_b, tb))
    results.sort(key=lambda x: x[2].split()[-1])
    return results


def find_given_pairs(names, title_counts, max_dist=1, suspect_threshold=3):
    """Same given name, dist<=max_dist surname, one side has <=suspect_threshold titles."""
    by_given = defaultdict(list)
    for id_, name in names.items():
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
                ta, tb = tc(title_counts, id_a), tc(title_counts, id_b)
                if not ((ta <= suspect_threshold < tb) or (tb <= suspect_threshold < ta)):
                    continue
                if abs(len(fam_a) - len(fam_b)) > max_dist + 1:
                    continue
                d = ndist(fam_a, fam_b)
                if 0 < d <= max_dist:
                    key = tuple(sorted([id_a, id_b]))
                    if key not in seen:
                        seen.add(key)
                        results.append((d, id_a, name_a, ta, id_b, name_b, tb))
    results.sort(key=lambda x: x[2].split()[0])
    return results


# --- Formatting ---

def sep(char='=', width=90):
    return char * width


def write_swap_section(f, swaps, names, title_counts, title_locations):
    f.write(sep() + '\n')
    f.write(f'SECTION 1: NAME-ORDER SWAPS ({len(swaps)} pairs)\n')
    f.write('Same actress entered twice with Given/Family order reversed.\n')
    f.write('Action: rename folders on the server to match the canonical (correct) name.\n')
    f.write(sep() + '\n\n')

    for idx, (id_a, name_a, id_b, name_b) in enumerate(swaps, 1):
        ta = tc(title_counts, id_a)
        tb = tc(title_counts, id_b)
        # The one with more titles is likely the canonical spelling
        if ta >= tb:
            canonical_id, canonical_name, canonical_tc = id_a, name_a, ta
            suspect_id, suspect_name, suspect_tc = id_b, name_b, tb
        else:
            canonical_id, canonical_name, canonical_tc = id_b, name_b, tb
            suspect_id, suspect_name, suspect_tc = id_a, name_a, ta

        f.write(f'[{idx}] {suspect_name}  →  {canonical_name}\n')
        f.write(f'    Canonical: {canonical_name} ({canonical_tc} titles)\n')
        f.write(f'    Suspect:   {suspect_name} ({suspect_tc} titles)\n')
        f.write(f'    Titles to reassign (rename folder to "{canonical_name}"):\n')
        for line in smb_lines(title_locations, suspect_id, title_counts):
            f.write(line + '\n')
        f.write('\n')


def write_pair_section(f, pairs, title_locations, title_counts, section_num, title, note):
    f.write(sep() + '\n')
    f.write(f'SECTION {section_num}: {title} ({len(pairs)} pairs)\n')
    f.write(note + '\n')
    f.write(sep() + '\n\n')

    for idx, (d, id_a, name_a, ta, id_b, name_b, tb) in enumerate(pairs, 1):
        # suspect = fewer titles
        if ta <= tb:
            suspect_id, suspect_name, suspect_tc = id_a, name_a, ta
            canonical_id, canonical_name, canonical_tc = id_b, name_b, tb
        else:
            suspect_id, suspect_name, suspect_tc = id_b, name_b, tb
            canonical_id, canonical_name, canonical_tc = id_a, name_a, ta

        f.write(f'[{idx}] {suspect_name}  →  {canonical_name}  (dist={d})\n')
        f.write(f'    Likely correct: {canonical_name} ({canonical_tc} titles)\n')
        f.write(f'    Suspect entry:  {suspect_name} ({suspect_tc} titles)\n')
        f.write(f'    Titles to reassign:\n')
        for line in smb_lines(title_locations, suspect_id, title_counts):
            f.write(line + '\n')
        f.write('\n')


# --- Main ---

def main(output_path='reference/tools/misspelling_review.txt'):
    print("Loading data...")
    names, title_counts, title_locations = load_data()
    print(f"  {len(names)} actresses, {len(title_locations)} with located titles")

    print("Computing swaps...")
    swaps = find_swaps(names)
    print(f"  {len(swaps)} swap pairs")

    print("Computing surname pairs (dist=1, one side ≤3 titles)...")
    surname_pairs = find_surname_pairs(names, title_counts, max_dist=1, suspect_threshold=3)
    print(f"  {len(surname_pairs)} pairs")

    print("Computing given-name pairs (dist=1, one side ≤3 titles)...")
    given_pairs = find_given_pairs(names, title_counts, max_dist=1, suspect_threshold=3)
    print(f"  {len(given_pairs)} pairs")

    print(f"Writing report to {output_path}...")
    with open(output_path, 'w', encoding='utf-8') as f:
        f.write('ACTRESS NAME MISSPELLING REVIEW REPORT\n')
        f.write(f'Generated: 2026-04-13\n')
        f.write(f'Total actresses in DB: {len(names)}\n')
        f.write('\n')
        f.write('HOW TO USE THIS REPORT\n')
        f.write('-' * 40 + '\n')
        f.write('Each entry shows a suspected duplicate/misspelling pair.\n')
        f.write('"Suspect entry" = the one that appears wrong (fewer titles).\n')
        f.write('"Likely correct" = the canonical spelling (more titles).\n')
        f.write('SMB paths show where the affected title folders are located on the server.\n')
        f.write('Action: on the server, rename the folder to use the correct actress name.\n')
        f.write('\n')
        f.write(f'  Section 1 - Name-order swaps:     {len(swaps)} pairs\n')
        f.write(f'  Section 2 - Given-name typos:     {len(surname_pairs)} pairs\n')
        f.write(f'  Section 3 - Surname typos:        {len(given_pairs)} pairs\n')
        f.write('\n\n')

        write_swap_section(f, swaps, names, title_counts, title_locations)
        f.write('\n\n')
        write_pair_section(
            f, surname_pairs, title_locations, title_counts,
            section_num=2,
            title='GIVEN-NAME TYPOS (same surname, dist=1 given name)',
            note='One side has ≤3 titles suggesting it is a stray misspelling entry.'
        )
        f.write('\n\n')
        write_pair_section(
            f, given_pairs, title_locations, title_counts,
            section_num=3,
            title='SURNAME TYPOS (same given name, dist=1 surname)',
            note='One side has ≤3 titles suggesting it is a stray misspelling entry.'
        )

        f.write(sep() + '\n')
        f.write('END OF REPORT\n')

    print(f"Done. Report written to {output_path}")
    print(f"  {len(swaps) + len(surname_pairs) + len(given_pairs)} total items to review")


if __name__ == '__main__':
    main()
