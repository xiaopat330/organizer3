# Organizer3 — Functional Specification

## Overview

Organizer3 is a CLI + web application for managing a large, distributed adult video library across multiple NAS volumes over SMB. It indexes content from two parallel, non-overlapping library systems:

- **JAV library** — Japanese adult video, organized by actress and title code across ~17 conventional/queue/exhibition/collections volumes
- **AV Stars library** — Western/European performer content, organized by actress folder on a dedicated `avstars` volume

Both libraries share the same database, shell, and web UI, but use completely separate data models. There is no cross-linking between JAV and AV records.

---

## 1. Core Concepts

### 1.1 Volumes

A **volume** is a logical storage unit mapped to a physical path on a remote SMB share. Each volume has:
- A short **ID** (e.g., `a`, `qnap_av`)
- An **SMB path** (e.g., `//pandora/jav_A`)
- A **server** reference (resolves to credentials and host)
- A **structure type** that determines folder layout and available sync commands

### 1.2 Structure Types

#### `conventional` (main JAV library volumes)
Tiered actress structure under `stars/`, plus unstructured partitions (`queue/`, `archive/`, etc.).
Sync commands: `sync all` (full), `sync queue` (queue partition only).

#### `queue` (intake/staging volumes)
Single flat partition for incoming titles awaiting sorting.
Sync commands: `sync` / `sync all`.

#### `exhibition` (flat actress layout, no tier subfolders)
Actress folders directly under `stars/`, no tier subdirectories.
All actresses stored with tier `LIBRARY` in the DB.
Sync commands: `sync all`.

#### `collections` (curated sets)
Unstructured partitions; sync not yet implemented.

#### `sort_pool` (post-sort staging)
Pool volumes where sorted titles await placement into a conventional volume.
Sync commands: `sync all`.

#### `avstars` (Western performer library)
Volume root contains top-level actress folders; each folder is recursively walked for video files. No partition structure. Dedicated schema (`av_actresses`, `av_videos`).
Sync commands: `sync all`.

### 1.3 JAV Content Model

**Title** — a JAV release identified by a code (`ABP-123`). Normalized to a `baseCode` (5-digit zero-padded, no suffix). A title can exist in multiple physical locations across volumes (detected as duplicates when `title_locations` has > 1 row for the same title).

**Actress** — a JAV performer, identified by canonical name. May have aliases. Belongs to a tier based on title count in the library.

| Tier | Title count |
|------|-------------|
| LIBRARY | < 5 |
| MINOR | 5–19 |
| POPULAR | 20–49 |
| SUPERSTAR | 50–99 |
| GODDESS | 100+ |

**Actress metadata** is enriched via YAML profiles (`load actress` / `load actresses`), which populate biography, measurements, filmography metadata, and a 59-tag taxonomy.

### 1.4 AV Stars Content Model

**AvActress** — keyed by `(volumeId, folderName)`. Identity and metadata optionally enriched from IAFD via `av resolve`. User fields: `favorite`, `bookmark`, `rejected`, `grade`, `notes`, `visitCount`, `lastVisitedAt`.

**AvVideo** — keyed by `(avActressId, relativePath)`. Contains parsed metadata (studio, date, resolution, codec, tags). User fields: `favorite`, `bookmark`, `watched`, `watchCount`, `lastWatchedAt`.

**AvTagDefinition** — canonical tag vocabulary (`av_tag_definitions`). Tags are applied to videos via `av_video_tags`.

**AvVideoScreenshot** — up to N extracted frames per video, stored locally, served by the web UI.

There is no release code, no tier system, and no cross-linking with JAV actresses.

---

## 2. Interactive Shell

The application runs as an interactive CLI shell (JLine3). The prompt shows current state:

```
organizer [*DRYRUN*] >          # no volume mounted, dry-run mode
organizer:vol-a [*DRYRUN*] >   # volume "a" mounted, dry-run mode
organizer:vol-a >               # volume "a" mounted, armed mode
```

The shell is also embedded in the web UI as a web terminal (WebSocket-backed).

### Dry-Run vs Armed Mode

The shell starts in **dry-run mode**. File operation commands report what would happen without executing. Sync, backup, restore, and AV operations are not affected by this flag — sync is inherently read-only from the filesystem perspective; backup/restore are suppressed in dry-run (report counts but do not write). `arm`/`test` toggle commands are not yet implemented.

---

## 3. Commands

### Volume commands (require mount except where noted)

| Command | Mount? | Description |
|---------|--------|-------------|
| `volumes` | No | List all configured volumes with sync status |
| `mount <id>` | — | Open SMB connection, set as active volume |
| `unmount` | No | Close SMB connection |
| `sync all` | Yes | Full sync for conventional/exhibition/queue/sort\_pool/avstars |
| `sync queue` | Yes | Queue-partition-only sync for conventional volumes |
| `sync` | Yes | Full sync for queue volumes |
| `rebuild` | Yes | `sync all` + `sync covers` in one step |
| `sync covers` | Yes | Collect cover images from the mounted volume's stars partitions |
| `prune-covers` | No | Remove local covers with no matching title in the DB |
| `prune-thumbnails` | No | Remove orphaned local thumbnails |
| `clear-thumbnails` | No | Delete all local thumbnails |

### JAV data commands (no mount required)

| Command | Description |
|---------|-------------|
| `actresses <tier>` | List actresses in a tier, sorted by title count |
| `favorites` | List favorited JAV actresses |
| `actress search <name>` | Search for an actress by name (uses Claude API if key set) |
| `check-names` | Validate actress name formatting |
| `scan-errors` | Report data integrity issues |
| `load actress <slug>` | Load one actress YAML profile into the DB |
| `load actresses` | Load all actress YAML profiles |

### AV Stars commands (no mount required)

| Command | Description |
|---------|-------------|
| `av sync` | Sync the `qnap_av` (and `athena_av`) volume(s) |
| `av actresses` | List all AV actresses sorted by video count |
| `av actress <name>` | Show detail for one AV actress |
| `av favorites` | List favorited AV actresses |
| `av resolve <name>` | Resolve one actress against IAFD (fetches profile + headshot) |
| `av resolve all` | Resolve all unresolved actresses against IAFD |
| `av curate <name>` | Set curation fields (favorite, bookmark, rejected, grade, notes) |
| `av migrate <old> <new>` | Migrate curation data when an actress folder is renamed on disk |
| `av parse` | Parse metadata from AV video filenames (studio, date, resolution, tags) |
| `av screenshots` | Generate screenshot frames for all videos that don't have them yet |
| `av tags <subcommand>` | Manage AV tag definitions and apply tags to videos |

### Backup and restore (no mount required)

| Command | Description |
|---------|-------------|
| `backup` | Write a manual backup snapshot |
| `restore [path]` | Restore user data from backup (newest snapshot if no path given) |

### Shell meta

| Command | Description |
|---------|-------------|
| `help` | List all available commands |
| `shutdown` | Exit the application |

---

## 4. Sync

### JAV Sync

`sync all` (full): clears `videos` and `title_locations` for the volume, walks the filesystem over SMB, creates/updates `Title`, `TitleLocation`, `Video`, and `Actress` records. After scan, deletes orphaned titles (no remaining locations). Reloads the in-memory `VolumeIndex` from DB.

`sync queue` (partition): same as full sync but restricted to the `queue` partition.

Title code parsing: `TitleCodeParser` extracts `<LABEL>-<NUMBER>[_SUFFIX]` from folder names. `baseCode` is normalized to 5-digit zero-padded, no suffix.

Actress resolution: folder names are resolved through `ActressRepository.resolveByName()`, checking canonical names and aliases. Creates a new actress if not found.

### AV Sync

`av sync` walks each `avstars` volume root. Each top-level folder is an actress (`AvActress`). Recursive treewalk under each actress folder finds all video files, which become `AvVideo` records. Ignores configured subfolder names (e.g., `trash`). After sync, updates `video_count` and `total_size_bytes` on each actress. Orphaned videos (not seen since sync start) are deleted.

`av parse` runs `AvFilenameParser` over all videos, extracting studio, release date, resolution, codec, and tag strings from the filename. Parsed fields are stored on `av_videos`.

`av screenshots` extracts frame captures from videos that have none yet. Uses ffmpeg to generate N equally-spaced frames, stores paths in `av_video_screenshots`. Shows a progress bar with remaining/total count.

### IAFD Enrichment

`av resolve <name>` / `av resolve all` fetch actress profiles from IAFD (iafd.com). `HttpIafdClient` uses `IafdSearchParser` to find the actress, then `IafdProfileParser` to extract profile fields. Downloads the headshot image to `data/av_headshots/`. Stores all IAFD fields on the `av_actresses` row.

---

## 5. Web UI

An embedded Javalin web server runs on port 8080. The web UI is the primary browsing interface. It is fully interactive for curation (favorites, bookmarks, grades) — it is NOT read-only.

### JAV Sections

- **Titles** — Dashboard, Favorites, Bookmarks, By Studio, By Tag, Unsorted, Archive, Collections
- **Actresses** — Dashboard, Favorites, Bookmarks, By Tier, By Studio, Exhibition, Archives
- **Title detail** — plays video, grade, bookmark, notes, actress link, watch history
- **Actress detail** — profile, title list, grade, tier, studio affiliations

### AV Stars Section

- **Browse** — all AV actresses sorted by video count, with headshot thumbnails
- **Actress detail** — profile (IAFD data), video grid with screenshot thumbnails, tag browser
- **Video modal** — inline video player, favorite/bookmark/watched controls, screenshot strip

### Federated Search

The landing page search bar queries JAV actresses, JAV titles, labels, studios, and AV actresses simultaneously. Results are grouped by category. Toggle buttons let users filter which categories show. `getEnabledCategories()` controls which result groups render. The `includeAv` flag on the backend call is derived from whether AV actresses are in the enabled set.

### Cover and Headshot Serving

- JAV cover images: served from `data/covers/<LABEL>/<baseCode>.<ext>`
- AV headshots: served from `data/av_headshots/<filename>` via `/api/av/headshots/{file}`
- AV screenshots: served from `data/av_screenshots/` via `/api/av/screenshots/{file}`
- JAV video thumbnails: served from `data/thumbnails/`

---

## 6. Backup and Restore

The backup system protects user-generated data — the only data that cannot be recovered by re-syncing volumes and reloading YAML profiles.

### What is backed up (v2 format)

**JAV actresses** (keyed by `canonicalName`): `favorite`, `bookmark`, `bookmarkedAt`, `grade`, `rejected`, `visitCount`, `lastVisitedAt`

**JAV titles** (keyed by `code`): same fields + `notes`

**Watch history**: `titleCode` + `watchedAt` pairs

**AV actresses** (keyed by `volumeId` + `folderName`): `favorite`, `bookmark`, `rejected`, `grade`, `notes`, `visitCount`, `lastVisitedAt`

**AV videos** (keyed by `volumeId` + `folderName` + `relativePath`): `favorite`, `bookmark`, `watched`, `watchCount`, `lastWatchedAt`

Only rows with at least one non-default user field are exported. Restore is an overlay — untouched rows are left unchanged. Entities not yet present in the DB are skipped and counted; re-running restore after syncing remaining volumes picks them up.

### Auto-backup

Runs automatically on a configurable schedule (default weekly). Writes a timestamped snapshot to `data/backups/`. Retains up to N snapshots (configurable; default 10). `restore` with no argument uses the newest snapshot.

### Format versioning

Backup files carry a `version` integer. v1 files have no AV fields (null on read). v2 adds AV data. A file with version higher than `CURRENT_BACKUP_VERSION` (currently 2) is rejected with an error.

---

## 7. Database

SQLite at `~/.organizer3/organizer.db`. Single-user, no concurrent access concerns. Schema managed by `SchemaInitializer` (fresh installs) + `SchemaUpgrader` (incremental `ALTER TABLE`/backfill migrations for existing databases).

### JAV tables

```
volumes             (id PK, structure_type, last_synced_at)
actresses           (id PK, canonical_name UNIQUE, stage_name, tier, favorite, bookmark,
                     grade, rejected, visit_count, last_visited_at, date_of_birth, etc.)
actress_aliases     (actress_id → actresses, alias_name; PK both)
titles              (id PK, code UNIQUE, base_code, label, actress_id → actresses,
                     favorite, bookmark, grade, rejected, notes, visit_count, etc.)
title_locations     (id PK, title_id → titles, volume_id → volumes, partition_id,
                     path, last_seen_at, added_date; UNIQUE(title_id, volume_id, path))
videos              (id PK, title_id → titles, volume_id, filename, path, last_seen_at)
title_actresses     (title_id, actress_id; many-to-many; PK both)
title_tags          (title_id, tag; PK both)
title_effective_tags (title_id, tag, source∈{direct,label}; PK title_id+tag)
labels              (code PK, label_name, company, description, company_*)
tags                (name PK, category, description)
label_tags          (label_code → labels, tag → tags; PK both)
actress_companies   (actress_id → actresses, company; PK both)
watch_history       (id PK, title_code, watched_at; UNIQUE title_code+watched_at)
```

### AV tables

```
av_actresses        (id PK, volume_id → volumes, folder_name, stage_name,
                     iafd_id, headshot_path, aka_names_json,
                     gender, date_of_birth, date_of_death, birthplace, nationality, ethnicity,
                     hair_color, eye_color, height_cm, weight_kg, measurements, cup,
                     shoe_size, tattoos, piercings,
                     active_from, active_to, director_from, director_to, iafd_title_count,
                     website_url, social_json, platforms_json, external_refs_json,
                     iafd_comments_json, awards_json,
                     favorite, bookmark, rejected, grade, notes,
                     first_seen_at, last_scanned_at, last_iafd_synced_at,
                     video_count, total_size_bytes, visit_count, last_visited_at;
                     UNIQUE(volume_id, folder_name))
av_videos           (id PK, av_actress_id → av_actresses, volume_id → volumes,
                     relative_path, filename, extension, size_bytes, mtime,
                     last_seen_at, added_date, bucket,
                     studio, release_date, parsed_title, resolution, codec, tags_json,
                     favorite, rejected, bookmark, watched, last_watched_at, watch_count;
                     UNIQUE(av_actress_id, relative_path))
av_tag_definitions  (slug PK, display_name, category, aliases_json)
av_video_tags       (av_video_id → av_videos, tag_slug → av_tag_definitions, source; PK both)
av_video_screenshots (id PK, av_video_id → av_videos, seq, path; UNIQUE av_video_id+seq)
```

---

## 8. Configuration

`organizer-config.yaml` (in `src/main/resources/`) is the bootstrap config. It is NOT gitignored in development but should be externalized before packaging.

```yaml
servers:          # SMB server credentials
volumes:          # Volume definitions (id, smbPath, structureType, server)
structures:       # Partition layouts per structure type
syncConfig:       # Command term → structure type + operation type bindings
backup:           # autoBackupIntervalMinutes, snapshotCount
dataDir:          # Root for covers, thumbnails, backups, av_headshots, av_screenshots
```

**Data ownership:**
- YAML owns: volume/server/structure/sync config
- DB owns: all actress, title, alias, video, AV records, watch history, tag data
- `aliases.yaml` is seed-only — imported once on fresh DB, then DB is authoritative
- `labels.csv` is auto-seeded on empty table; `LabelSeeder.reimport()` forces re-seed

---

## 9. Volumes Reference

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

## 10. Not Yet Implemented

- `arm` / `test` toggle commands (dry-run mode is set only at startup)
- File operations (move, rename, mkdir) and `DryRunFileSystem`
- Tab completion in the shell
- `collections` volume sync
- Organization workflow commands (`run <action>`)
