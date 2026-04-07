# QNAP NAS — `/Volumes/JAV/stars` Structure Observations

Observed by exploring the live QNAP mount at `/Volumes/JAV/stars` (SMB share: `//qnap2/jav`).

---

## Top Level

~123 actress folders plus a small amount of noise:
- `files.txt`, `to add.txt` — loose text files, not directories
- `temp/` — staging area, not a real actress folder (see below)

---

## Inside Each Actress Folder

A mix of content types at depth 1:

### Title folders (direct, most common)
Named `Actress Name (CODE-123)` or `Actress Name - Subtitle (CODE-123)`.
The JAV code always appears in parentheses at the end.

Examples:
- `Eimi Fukada (MIAA-085)`
- `Kirara Asuka - Demosaiced (SNIS-052)`
- `Ai Hoshina, Eimi Fukada - Demosaiced (PRED-159)` — multi-actress titles

### Named subfolders containing more title folders
Arbitrary names — these are user-curated grouping buckets. Title folders sit directly inside them.

Known subfolder names observed:
- `favorites`, `_favorites` — user-curated picks
- `meh`, `ok` — personal rating buckets (seen in Sora Shiina)
- `processed` — AI-upscaled/converted titles (seen in Yua Mikami)

These subfolder names are not exhaustive — arbitrary names should be expected.

### Paren-code folders (title folders without actress prefix)
Some title folders are named with just a code in parens, no actress name:
- `(BLK-162)`, `(GDQN-001)`, `(CWP-103)`, `(RSD-012)`

These are still title folders and should be indexed normally.

### Cover/image-only folders
Contain only `.jpg`/image files — no title subfolders:
- `covers`, `_covers`, `cover`

These should be **skipped** during title indexing.

### Misc junk / edge cases
- Loose image files (`.jpg`) directly in the actress folder
- Oddly named folders: `a12738/`, `xxx-av.com-21090-FHD/`, `#Rio_Hamasaki, part 1/`, `(320_RIKU_01 BY ARSENAL-FAN)/`
- Some of these contain loose video files (not inside a proper title folder)

---

## Inside Title Folders (leaf level)

- Video files directly in the title folder
- `video/` subdirectory containing video files
- `h265/` subdirectory containing video files (same role as `video/`)
- Occasional loose image/cover file

---

## Maximum Depth

```
stars/
  Actress Name/               depth 1 — actress folder
    subfolder/                depth 2 — optional grouping bucket (favorites, meh, ok, processed, etc.)
      Title Name (CODE-123)/  depth 3 — title folder
        video/                depth 4 — video subdirectory (video/ or h265/)
    Title Name (CODE-123)/    depth 2 — title folder (direct case)
      video/                  depth 3 — video subdirectory
```

Titles can be at depth 2 (direct) or depth 3 (inside a named subfolder). No deeper title nesting observed.

---

## `temp/` Folder

The top-level `temp/` is a staging area with its own internal structure — not an actress folder. Contains:
- `temp/temp/` — nested temp
- `temp/temp/AIKA [face]/` — year-based subfolders (`2019/`, `2020/`, etc.) containing loose video files

This folder should be **excluded** from indexing.

---

## Indexing Strategy

To correctly index this volume:
1. List actress folders at depth 1 (skip non-directories and known noise: `temp`, loose files)
2. For each child directory inside an actress folder:
   - If the name contains a parseable JAV code → it's a title folder, index it
   - If the name is a known image-only folder (`covers`, `cover`, `_covers`) → skip
   - Otherwise → treat as a subfolder, recurse one level and look for title folders inside
3. Inside each title folder: collect video files directly and inside `video/` or `h265/` subdirectories
4. Loose files (images, videos) directly in actress folders or subfolders are not indexed as titles
