# Sort Pool Volume Structure

**Volume:** `pool`  
**SMB path:** `//pandora/jav_unsorted/_done`  
**Local mount (observed):** `/Volumes/jav_unsorted-1/_done`  
**Structure type:** `sort_pool`

---

## Overview

This is a staging/holding area for titles that have been downloaded and basic-processed, but not yet sorted into a conventional actress volume. It is a single flat directory with no deep nesting — all title folders live directly under the root (or in one special subfolder).

**Observed counts (April 2026):**
- Root-level title folders: ~1,859
- `__later/` subfolder title folders: ~459 (374 code-only, 85 with actress names)

---

## Directory Structure

```
_done/                          ← smbPath root (volume root)
  Actress Name (CODE-123)/      ← title folder, single actress
  Actress1, Actress2 (CODE)/    ← title folder, multi-actress (comma-sep)
  Actress - Demosaiced (CODE)/  ← title folder, with note modifier
  Actress - Amateur (CODE)/     ← title folder, "Amateur" modifier
  Amateur (CODE)/               ← title folder, no actress name known
  __later/                      ← special subfolder (deferred items)
    (CARIB-CODE)/               ← code-only folder (no actress name)
    Actress Name (CODE)/        ← actress+code folder
```

Each title folder contains:
- `h265/` (video files)
- `video/` (less common)
- `*.jpg` (cover image, e.g. `abc123pl.jpg`)
- `Thumbs.db` (Windows thumbnail cache, ignorable)

---

## Folder Naming Conventions

### Root-level title folders

All follow the pattern: `{actress_part} ({code})`

| Pattern | Example |
|---------|---------|
| Single actress | `Yui Hatano (IPX-123)` |
| Multi-actress (comma-sep) | `Yui Hatano, Aika (TIKB-192)` |
| With note modifier (` - Note`) | `Ai Sayama - Demosaiced (MIDD-613)` |
| With "Amateur" modifier | `Akane - Amateur (IMJO-003)` |
| Unknown actress | `Amateur (FC2PPV-4545181)` |
| Multi-actress with note | `Yui Hatano, Aika - Demosaiced (WAAA-421)` |

**Parsing rule:** everything before the last ` (CODE)` is the actress part. Strip ` - {note}` suffixes (e.g. `- Demosaiced`, `- Amateur`) after splitting off the code. The actress part may then be split on `, ` to get individual actress names.

### `__later/` subfolder

Contains items deferred for later review. Two sub-patterns:
1. **Code-only:** `(CARIB-20191001)` — no actress name encoded, actress unknown
2. **Actress+code:** same pattern as root (`Actress Name (CODE)`)

The code format for code-only entries is often a date-based identifier (e.g., `011020-001-CARIB`, `021320 973-1PON`), typical of Caribbeancom / 1Pondo uncensored titles.

---

## Scanning Algorithm

### Partitions

| Partition ID | Path | Description |
|---|---|---|
| `pool` | `/` (root) | Main holding area — sorted title folders |
| `later` | `/__later` | Deferred items not yet ready to sort |

### Scan logic

1. **Root scan (partition: `pool`):**
   - List all subdirectories directly under `/`
   - Skip any folder starting with `__` (these are special, not titles)
   - For each remaining folder: parse actress name(s) and JAV code from folder name
   - Assign tier `LIBRARY`

2. **`__later` scan (partition: `later`):**
   - List all subdirectories under `/__later`
   - For code-only folders `(CODE)`: actress name = `null` or empty
   - For actress+code folders: parse normally
   - Assign tier `LIBRARY`

### Actress name parsing (same rules as conventional scanner)

Given folder name `Yui Hatano, Aika - Demosaiced (IPX-123)`:
1. Strip trailing `({code})` → `Yui Hatano, Aika - Demosaiced`
2. Strip ` - {note}` suffix (anything after ` - ` at end) → `Yui Hatano, Aika`
3. The first actress name is the canonical actress for the title (multi-actress titles credit the first listed)
4. Code-only folders `({code})` have no actress part → actress = null

---

## Key Differences from Other Structure Types

| Feature | `conventional` | `queue` | `sort_pool` |
|---------|---------------|---------|-------------|
| Actress subdirs | Yes (`stars/`) | No | No |
| Partition subdirs | Yes (many) | One (`fresh/`) | Two (`/`, `__later/`) |
| Folder naming | `code/` or `actress/code/` | `Actress (CODE)` | `Actress (CODE)` |
| Multi-actress folders | No | Common | Very common |
| Code-only folders | Rare | Rare | Common in `__later` |
| Special skip folders | None | None | `__` prefix |

---

## Implementation Notes

- The `SortPoolScanner` scans `Path.of("/")` directly — the smbPath IS the scan root.
- `__later` is skipped by checking `folderName.startsWith("__")` at the root level.
- To include `__later` items, add a second pass scanning `Path.of("/__later")`.
- The `sort_pool` structure YAML has `unstructuredPartitions: []` — the scanner doesn't consult partition definitions at all.
