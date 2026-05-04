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

SQLite at `~/.organizer3/organizer.db`. Single-user, no concurrent access concerns. Schema managed by `SchemaInitializer` (fresh installs) + `SchemaUpgrader` (incremental `ALTER TABLE`/backfill migrations for existing databases). Current schema version: **50** (`PRAGMA user_version`).

### JAV core tables

```
volumes             (id PK, structure_type, last_synced_at)
actresses           (id PK, canonical_name UNIQUE, stage_name, name_reading, tier,
                     favorite, bookmark, bookmarked_at, grade, rejected,
                     first_seen_at, date_of_birth, birthplace, blood_type,
                     height_cm, bust, waist, hip, cup,
                     active_from, active_to, retirement_announced,
                     biography, legacy, alternate_names_json, primary_studios_json, awards_json,
                     visit_count, last_visited_at, needs_profiling,
                     favorite_cleared_at, is_sentinel, custom_avatar_path,
                     created_via, created_at)
actress_aliases     (actress_id → actresses, alias_name; PK both)
actress_companies   (actress_id → actresses, company; PK both)
titles              (id PK, code UNIQUE, base_code, label, seq_num,
                     actress_id → actresses,
                     favorite, bookmark, bookmarked_at, grade, grade_source, rejected,
                     title_original, title_english, release_date,
                     notes, visit_count, last_visited_at, favorite_cleared_at)
title_locations     (id PK, title_id → titles, volume_id → volumes, partition_id,
                     path, last_seen_at, added_date; UNIQUE(title_id, volume_id, path))
videos              (id PK, title_id → titles, volume_id, filename, path, last_seen_at,
                     duration_sec, width, height, video_codec, audio_codec, container, size_bytes)
title_actresses     (title_id, actress_id; many-to-many; PK both)
title_tags          (title_id, tag; PK both)
title_effective_tags (title_id, tag, source∈{direct,label,enrichment}; PK title_id+tag)
labels              (code PK, label_name, company, description, company_description,
                     company_specialty, company_founded, company_status, company_parent)
tags                (name PK, category, description)
label_tags          (label_code → labels, tag → tags; PK both)
watch_history       (id PK, title_code, watched_at; UNIQUE title_code+watched_at)
```

Four triggers automatically maintain `favorite_cleared_at` on `titles` and `actresses`: stamp `now()` when `favorite` transitions 1→0, clear it on 0→1.

### JAV sync / forensics

```
title_path_history  (id PK, title_id, volume_id, partition_id, path,
                     first_seen_at, last_seen_at;
                     UNIQUE(volume_id, partition_id, path))
```

Forensic path log for sync-matcher fallback. No FK on `title_id` — rows survive title deletion and are used for re-add recovery.

### JAV duplicate / merge

```
duplicate_decisions (title_code, volume_id, nas_path,
                     decision∈{KEEP,TRASH,VARIANT},
                     created_at, executed_at; PK all three key cols)
merge_candidates    (id PK, title_code_a, title_code_b,
                     confidence∈{code-normalization,variant-suffix},
                     detected_at, decision∈{MERGE,DISMISS}, decided_at,
                     winner_code, executed_at; UNIQUE(title_code_a, title_code_b))
```

### JAV enrichment

```
javdb_enrichment_queue      (id PK, job_type, target_id, actress_id, source,
                              priority∈{LOW,NORMAL,HIGH,URGENT}, status,
                              attempts, next_attempt_at, last_error,
                              created_at, updated_at, sort_order)
javdb_title_staging         (title_id PK → titles, status, javdb_slug, raw_path,
                              raw_fetched_at, title_original, release_date,
                              duration_minutes, maker, publisher, series,
                              rating_avg, rating_count, tags_json, cast_json,
                              cover_url, thumbnail_urls_json)
javdb_actress_staging       (actress_id PK → actresses, javdb_slug UNIQUE,
                              source_title_code, status, raw_path, raw_fetched_at,
                              name_variants_json, avatar_url, twitter_handle,
                              instagram_handle, title_count, local_avatar_path)
title_javdb_enrichment      (title_id PK → titles ON DELETE CASCADE,
                              javdb_slug, fetched_at, release_date,
                              rating_avg, rating_count, maker, publisher, series,
                              title_original, duration_minutes, cover_url,
                              thumbnail_urls_json, cast_json, raw_path,
                              resolver_source, confidence, cast_validated,
                              last_revalidated_at,
                              title_original_en, series_en, maker_en, publisher_en)
title_javdb_enrichment_history (id PK, title_id, title_code, changed_at,
                                reason, prior_slug, prior_payload, new_payload,
                                promotion_metadata)
enrichment_tag_definitions  (id PK, name UNIQUE, curated_alias → tags(name),
                              title_count, surface)
title_enrichment_tags       (title_id → title_javdb_enrichment ON DELETE CASCADE,
                              tag_id → enrichment_tag_definitions; PK both)
enrichment_review_queue     (id PK, title_id → titles ON DELETE CASCADE,
                              slug, reason, resolver_source, created_at,
                              last_seen_at, detail, resolved_at, resolution;
                              UNIQUE(title_id, reason) WHERE resolved_at IS NULL)
revalidation_pending        (title_id PK → titles ON DELETE CASCADE,
                              enqueued_at, reason)
rating_curve                (id PK CHECK(id=1), global_mean, global_count,
                              min_credible_votes, cutoffs_json, computed_at)
javdb_actress_filmography   (actress_slug PK, fetched_at, page_count,
                              last_release_date, source, last_drift_count,
                              last_fetch_status)
javdb_actress_filmography_entry (actress_slug → javdb_actress_filmography ON DELETE CASCADE,
                                 product_code, title_slug, stale;
                                 PK(actress_slug, product_code))
```

### Draft Mode

Staging layer for bulk enrichment + human review before committing enrichment data to canonical tables.

```
draft_titles                (id PK, title_id → titles ON DELETE CASCADE,
                              code, title_original, title_english, release_date,
                              notes, grade, grade_source,
                              upstream_changed, last_validation_error,
                              created_at, updated_at; UNIQUE title_id)
draft_actresses             (javdb_slug PK, stage_name, english_first_name,
                              english_last_name, link_to_existing_id → actresses,
                              created_at, updated_at, last_validation_error)
draft_title_actresses       (draft_title_id → draft_titles ON DELETE CASCADE,
                              javdb_slug → draft_actresses, resolution; PK both)
draft_title_javdb_enrichment (draft_title_id PK → draft_titles ON DELETE CASCADE,
                               javdb_slug, cast_json, maker, series, cover_url,
                               tags_json, rating_avg, rating_count,
                               resolver_source, updated_at)
```

### Translation

Local LLM translation service tables. `translation_strategy` defines named model+prompt configurations. `translation_cache` stores results keyed by `(source_hash, strategy_id)`. `translation_queue` is the async work queue drained by the translation worker.

```
translation_strategy    (id PK, name UNIQUE, model_id, prompt_template,
                          options_json, is_active,
                          tier2_strategy_id → translation_strategy)
translation_cache       (id PK, source_hash, source_text, strategy_id → translation_strategy,
                          english_text, human_corrected_text, human_corrected_at,
                          failure_reason, retry_after,
                          latency_ms, prompt_tokens, eval_tokens, eval_duration_ns,
                          cached_at; UNIQUE(source_hash, strategy_id))
translation_queue       (id PK, source_text, strategy_id, submitted_at,
                          started_at, completed_at, status,
                          callback_kind, callback_id, attempt_count, last_error)
stage_name_lookup       (id PK, kanji_form UNIQUE, romanized_form,
                          actress_slug, source, seeded_at)
stage_name_suggestion   (id PK, kanji_form, suggested_romaji, suggested_at,
                          reviewed_at, review_decision, final_romaji;
                          UNIQUE(kanji_form, suggested_romaji))
```

`stage_name_lookup` is the curated kanji→romaji seed table; `stage_name_suggestion` holds LLM-produced suggestions pending human review.

### AV core tables

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

### AV operations

```
av_screenshot_queue (id PK, av_video_id UNIQUE → av_videos ON DELETE CASCADE,
                     av_actress_id → av_actresses, enqueued_at,
                     started_at, completed_at,
                     status∈{PENDING,…}, error)
```

Persistent FIFO queue for background screenshot generation. `UNIQUE` on `av_video_id` makes enqueue idempotent.

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
