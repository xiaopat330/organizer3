# Organizer3 — Usage Guide

## Starting the Shell

```
./gradlew run
```

The shell starts in **dry-run mode** by default. The prompt shows current state:

```
organizer [*DRYRUN*] >          # no volume mounted
organizer:vol-a [*DRYRUN*] >   # volume "a" mounted
organizer:vol-a >               # armed (live mode)
```

Type `help` at any time to list available commands.

---

## Volume Commands

### `volumes`

Lists all configured volumes with connection status and last-sync timestamp.

```
organizer > volumes
ID          STRUCTURE       CONNECTED   LAST SYNC
------------------------------------------------------------
a           conventional    -           2026-04-01 14:22
qnap_av     avstars         -           2026-04-13 09:00
...
```

---

### `mount <id>`

Connects to a volume over SMB and activates it as the session context.

```
organizer > mount a
Loaded index: 3,241 title(s), 187 actress(es).
Connected. Volume 'a' is now active.
organizer:vol-a [*DRYRUN*] >
```

---

### `unmount`

Disconnects from the current volume.

---

### `sync all`

Full sync for the mounted volume. Re-scans the entire filesystem and rebuilds the database index. Available for `conventional`, `exhibition`, `queue`, `sort_pool`, and `avstars` volumes.

```
organizer:vol-a > sync all
Syncing a (full) ...
  Scanning queue/ ...
  Scanning stars/library/ ...
  ...
Sync complete.
  Actresses: 187   Queue: 12   Total: 3,241
```

---

### `sync queue`

Queue-partition-only sync for `conventional` volumes. Faster when only intake content has changed.

---

### `sync`

Full sync for `queue` structure volumes (e.g., `unsorted`).

---

### `rebuild`

Runs `sync all` followed by `sync covers` in one step.

---

### `sync covers`

Collects cover images from the mounted volume's `stars/` partitions and stores them locally under `data/covers/<LABEL>/`. Requires a mounted volume.

---

### `prune-covers`

Removes local cover files whose `baseCode` does not match any title in the database. Does not require a mounted volume.

---

### `prune-thumbnails`

Removes local thumbnails whose title is no longer in the database.

---

### `clear-thumbnails`

Deletes all local thumbnails unconditionally.

---

### `background-thumbs [on|off|status]`

Controls the background thumbnail sync worker (see `spec/PROPOSAL_BACKGROUND_THUMBNAILS.md`).

- `on` — enable pre-generation for favorites, bookmarks, and recently-visited titles
- `off` — disable
- `status` (default) — print enabled state, last-cycle queue size, totals generated/evicted this session, and the most recent generation

The worker is low-priority and pauses while the web UI is being used. Default is off; to enable on startup, set `backgroundThumbnails.enabled: true` in `organizer-config.yaml`.

---

## Organize Pipeline

Commands that operate on intake content before it enters the curated library. Apply to `queue` structure volumes (e.g. `unsorted`). See `spec/PROPOSAL_ORGANIZE_PIPELINE.md` for the full pipeline design.

### `prep-fresh <partitionId> [limit] [offset]`

Prep phase (Phase 0). Turns raw video files dropped at the partition root into `(CODE)/<video|h265>/<file>` skeletons ready for human curation.

For each raw file:
1. Normalize the filename via the configured removelist/replacelist (strips legacy junk-prefix tokens like `hhd800.com@`).
2. Parse a product code. Handles standard (`JUR-717`), digit-prefix (`300MIUM-1353`), FC2PPV, and suffix-label codes (`041126_001-1PON`, `050825_001-CARIB`).
3. Compute the target folder (`(CODE)`) and subfolder (`h265/` or `video/` based on encoding hint).
4. Move the file.

Unparseable files are skipped with a reason. Collisions with existing target folders are also skipped — a human decides whether to merge.

Respects session dry-run. The shell is currently dry-run-locked; actual execution happens via the `prep_fresh_videos` MCP tool.

```
organizer:vol-unsorted [*DRYRUN*] > prep-fresh queue
[DRY RUN] 37 planned, 0 skipped  (total videos at root: 37)
  - /fresh/hhd800.com@JUR-717-h265.mkv → /fresh/(JUR-717)/h265/JUR-717-h265.mkv
  ...
```

---

### `audit-fresh <partitionId>`

Read-only diagnostic that classifies each skeleton folder in a queue partition by graduation readiness.

Buckets:
- `READY` — actress prefix (e.g. `Yua Aida (ONED-1234)`), cover at folder base, video inside
- `NEEDS_COVER` — has actress prefix but no cover at base
- `NEEDS_ACTRESS` — bare `(CODE)` folder, still awaiting actress lookup
- `EMPTY` — skeleton shape but no video inside (investigate)
- `OTHER` — doesn't match any skeleton pattern (actress workspace folders, free-form dirs)

Output includes per-folder last-modified date so you can triage by age.

```
organizer:vol-unsorted > audit-fresh queue
Audit of /fresh — total: 193
  READY: 0
  NEEDS_ACTRESS: 192
  NEEDS_COVER: 0
  EMPTY: 0
  OTHER: 1
--- NEEDS_ACTRESS ---
  (041126_001-1PON) (mtime 2026-04-17)
  (ACHJ-083) (mtime 2026-04-16)
  ...
```

Also exposed as MCP tool `audit_fresh_skeletons`.

---

## JAV Data Commands

### `actresses <tier>`

Lists actresses in a tier sorted by title count (descending). No mount required.

Valid tiers: `library`, `minor`, `popular`, `superstar`, `goddess`

```
organizer > actresses goddess
GODDESS  (5 actresses)
  NAME                                      TITLES
  ------------------------------------------------
  Yua Mikami                                127
  Aya Sazanami                               98
  ...
```

---

### `favorites`

Lists all favorited JAV actresses with title counts.

---

### `actress search <name>`

Searches for a JAV actress by name. Uses the Claude API for name lookups when `ANTHROPIC_API_KEY` is set in the environment; falls back to DB-only search otherwise.

---

### `check-names`

Validates actress name formatting in the database and reports inconsistencies. Suggests `actress merge` for confirmed typos.

---

### `actress merge <suspect> > <canonical>`

Fixes an actress name typo by merging the suspect record into the canonical one.
Reassigns `title_actresses` rows, updates filing `actress_id`, cleans dependent
tables, and renames affected title folders on the currently mounted volume.
Folders on unmounted volumes are listed as needing manual attention. Honours
the session's dry-run flag — preview first, then run armed to execute.

```
actress merge "Rin Hatchimitsu" > "Rin Hachimitsu"
```

---

### `scan-errors`

Reports data integrity issues (titles with bad codes, missing locations, etc.).

---

### `load actress <slug>`

Loads one actress's YAML profile from `reference/actresses/<slug>/` into the database. Populates biography, measurements, filmography metadata, and tags.

---

### `load actresses`

Loads all actress YAML profiles found under `reference/actresses/`.

---

## AV Stars Commands

All AV commands begin with `av`. None require a mounted volume.

---

### `av sync`

Syncs all `avstars` volumes (`qnap_av`, `athena_av`). Walks each actress folder recursively, upserts `av_actresses` and `av_videos` records, deletes orphaned videos, and updates video counts.

---

### `av actresses`

Lists all AV actresses sorted by video count descending.

```
organizer > av actresses
  NAME                         VIDEOS   GRADE
  ------------------------------------------------
  Anissa Kate                    312     A
  Asa Akira                      284     S
  ...
```

---

### `av actress <name>`

Shows detail for one AV actress: IAFD profile fields, video count, grade, notes.

---

### `av favorites`

Lists AV actresses with `favorite = true`.

---

### `av resolve <name>`

Resolves one AV actress against IAFD: fetches her profile, downloads headshot, stores all IAFD fields on the `av_actresses` row.

---

### `av resolve all`

Resolves all AV actresses that have not yet been resolved (`iafd_id IS NULL`) against IAFD in sequence.

---

### `av curate <name>`

Interactive prompt to set curation fields for an AV actress: `favorite`, `bookmark`, `rejected`, `grade`, `notes`.

---

### `av migrate <old-folder> <new-folder>`

Copies curation fields (favorite, bookmark, rejected, grade, notes, IAFD data) from the old actress row to the new one, then deletes the old row. Used when an actress folder is renamed on disk.

---

### `av parse`

Runs `AvFilenameParser` over all AV videos, extracting studio, release date, resolution, codec, and tag strings from filenames. Stores parsed fields on `av_videos`.

---

### `av screenshots`

Generates screenshot frames for all AV videos that don't yet have them. Uses ffmpeg. Shows a progress bar:

```
[47/312] anissa_kate_2023-08-15_full_scene.mp4
```

---

### `av tags <subcommand>`

Manages AV tag definitions and applies tags to videos. Subcommands include loading tag definitions from YAML and applying tags to matched videos.

---

## Backup and Restore

### `backup`

Exports all user-altered fields (JAV + AV favorites, grades, visit history, watch history) to a timestamped snapshot file in `data/backups/`. Reports counts by category:

```
organizer > backup
Exported 1,204 actress, 18,432 title, 847 watch history, 62 av-actress, 1,841 av-video records.
Backup written to: data/backups/user-data-backup-2026-04-14T22-30-00.json
```

In dry-run mode: reports counts but does not write.

---

### `restore [path]`

Restores user data from a backup. With no argument, uses the newest snapshot in `data/backups/`. An explicit file path can be supplied.

```
organizer > restore
Using backup: user-data-backup-2026-04-14T22-30-00.json
Restored 1,198 actress records (6 skipped — not found).
Restored 18,401 title records (31 skipped — not found).
Inserted 847 watch history entries.
Restored 62 av-actress records (0 skipped — not found).
Restored 1,841 av-video records (0 skipped — not found).
```

Skipped entries are expected when restoring before all volumes have been synced — re-run after syncing.

In dry-run mode: reads and parses the file, reports what would be applied, does not touch the database.

---

## Backup Recovery Workflow

After dropping and recreating the database:

1. `load actresses` — reseeds JAV actress profiles from YAML
2. Mount each JAV volume and run `sync all`
3. `av sync` — rebuilds AV actress/video records
4. `restore` — overlays all user-altered fields from backup

---

## Shell Meta

### `help`

Lists all available commands with descriptions.

---

### `shutdown`

Exits the application. Ctrl+D also exits gracefully.

---

## Volumes Reference

| ID | SMB Path | Structure | Content |
|----|----------|-----------|---------|
| a | //pandora/jav_A | conventional | A |
| bg | //pandora/jav_BG | conventional | B–G |
| hj | //pandora/jav_HJ | conventional | H–J |
| k | //pandora/jav_K | conventional | K |
| m | //pandora/jav_M | conventional | M |
| ma | //pandora/jav_MA | conventional | MA |
| n | //pandora/jav_N | conventional | N |
| r | //pandora/jav_OR | conventional | O–R |
| s | //pandora/jav_S | conventional | S |
| tz | //pandora/jav_TZ | conventional | T–Z |
| unsorted | //pandora/jav_unsorted | queue | Intake staging |
| qnap | //qnap2/jav | exhibition | Overflow |
| qnap_archive | //pandora/qnap_archive | exhibition | Archive overflow |
| classic | //qnap2/JAV/classic | exhibition | Classic |
| pool | //pandora/jav_unsorted/_done | sort_pool | Post-sort staging |
| classic_pool | //qnap2/JAV/classic/new | sort_pool | Classic intake |
| collections | //pandora/jav_collections | collections | Curated sets |
| qnap_av | //qnap2/AV/stars | avstars | Western performers (primary) |
| athena_av | //athena/AV/stars | avstars | Western performers (secondary) |

---

## Actress Tiers (JAV)

| Tier | Title count | Folder under stars/ |
|------|-------------|---------------------|
| LIBRARY | < 5 | library/ |
| MINOR | 5–19 | minor/ |
| POPULAR | 20–49 | popular/ |
| SUPERSTAR | 50–99 | superstar/ |
| GODDESS | 100+ | goddess/ |

AV actresses have no tier system.
