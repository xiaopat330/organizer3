# Organizer3 - Functional Specification

## Overview

Organizer is a CLI-based media library automation tool for managing a large, distributed collection of Japanese adult video (JAV) content across multiple network-attached storage (NAS) volumes. It provides intelligent file organization, metadata extraction from filenames, performer-based categorization, and batch file operations with a safety-first dry-run mode.

---

## 1. Core Concepts

### 1.1 Volumes

A **volume** is a logical storage unit mapped to a physical path on a remote SMB share. Each volume has:

- A short **ID** (e.g., `a`, `bg`, `hj`, `unsorted`)
- An **SMB path** (e.g., `//pandora/jav_A`)
- A **server** reference (resolves to credentials and host)
- A **structure type** that determines its folder layout and available commands

The system manages ~14 volumes, most partitioned by actress name initial (A, BG, HJ, K, M, MA, N, OR, S, TZ) plus special-purpose volumes (unsorted, classic, collections, qnap).

### 1.2 Structure Types

Each volume follows one of four structure templates:

#### Conventional (main library volumes)
```
<volume root>/
  stars/              # Organized by actress
    library/          # Default tier (under thresholds)
    minor/            # 5-19 titles
    popular/          # 20-49 titles
    superstar/        # 50-99 titles
    goddess/          # 100+ titles
    favorites/        # User-curated
    archive/          # Archived content
  queue/              # Incoming titles awaiting sorting
  recent/             # Recently added
  archive/            # Volume-level archive
  attention/          # Titles needing manual review
  converted/          # Format-converted files staging
  duplicates/         # Detected duplicates
  favorites/          # Volume-level favorites
  minor/              # Minor content
```

#### Queue (intake/staging volumes)
```
<volume root>/
  fresh/              # Unorganized incoming titles (logical id: queue)
```

#### Stars-Flat (flat actress layout, no tier sub-folders)
```
<volume root>/
  stars/
    Actress Name/     # Actress folder directly under stars/
      ABP-123/        # Title folder
```
All actresses in a stars-flat volume are stored with tier `LIBRARY` in the DB.

#### Collections (curated sets)
```
<volume root>/
  archive/
  converted/
  duos/
  favorites/
  new/
  omnibus/
  recent/
```

### 1.3 Partitions

Partitions are the top-level folder groupings within a volume. They are divided into:

- **Structured partitions** - Under the `stars/` directory, organized by actress. Each subfolder is an actress folder containing her titles.
- **Unstructured partitions** - Top-level folders like `queue/`, `archive/`, `converted/`, etc. that hold titles directly without actress-level grouping.

### 1.4 Content Hierarchy

```
Volume
  -> Partition (folder grouping, e.g., "stars/popular", "queue")
       -> Actress (performer folder, only in structured partitions)
            -> Title (a release/series folder, identified by code)
                 -> Video files
       -> Title (directly in partition, for unstructured)
            -> Video files
  -> Orphaned Videos (files not matched to any title)
```

### 1.5 Titles

A **title** is a video release identified by a **code** (e.g., `ABP-123`, `SSIS-456`). Title codes follow standard JAV naming conventions:

- Format: `<LABEL>-<NUMBER>` (e.g., `PRED-456`)
- Some codes have suffixes for variants: `_U` (uncensored), `_4K` (4K resolution)
- Multi-part releases may have sub-codes (tracked as `seqNum`)
- The **base code** is the normalized form used for matching (label uppercased, number zero-padded to 5 digits, no suffix)

A title folder typically contains:
- One or more video files
- Optionally a `video/` subdirectory containing the actual files

### 1.6 Actresses

An **actress** (performer) is identified by name and can:

- Have **aliases** (alternate stage names that map to the same person)
- Appear in multiple titles across multiple volumes
- Be ranked into **tiers** based on title count:
  - **LIBRARY** - default (fewer than 5 titles)
  - **MINOR** - 5 to 19 titles
  - **POPULAR** - 20 to 49 titles
  - **SUPERSTAR** - 50 to 99 titles
  - **GODDESS** - 100+ titles
- Be marked as a **favorite** for user-curated tracking

### 1.7 Videos

Individual video files. Supported formats:
`mkv, mp4, avi, mov, wmv, mpg, mpeg, m4v, m2ts, ts, rmvb, divx, asf, wma, wm`

---

## 2. User Interaction

### 2.1 Interactive Shell

The application runs as an interactive CLI shell with history file persistence. The prompt displays the current context:

```
organizer [*DRYRUN*] >          # No volume mounted, dry-run mode
organizer:vol-a [*DRYRUN*] >   # Volume "a" mounted, dry-run mode
organizer:vol-a >               # Volume "a" mounted, armed (real mode)
```

### 2.2 Commands

Some commands require an active mounted volume (filesystem access). Others work from the local database alone.

| Command | Requires Mount | Status | Description |
|---------|---------------|--------|-------------|
| `help` | No | ✅ | List available commands |
| `volumes` | No | ✅ | List all configured volumes with last-sync timestamps |
| `mount <id>` | — | ✅ | Authenticate and activate a volume as the current context |
| `unmount` | No | ✅ | Disconnect from the current volume |
| `sync` | Yes | ✅ | Full sync (queue volumes) |
| `sync all` | Yes | ✅ | Full sync for conventional and queue volumes |
| `sync queue` | Yes | ✅ | Partition-scoped sync (queue partition only, conventional volumes) |
| `actresses <tier>` | No | ✅ | List actresses in a tier with title counts, sorted by most to least |
| `favorites` | No | ✅ | List all favorited actresses with title counts |
| `arm` | No | ⬜ | Switch to live/real mode |
| `test` | No | ⬜ | Switch to dry-run mode |
| `actress <name>` | No | ⬜ | Show details for a specific actress |
| `list` | Yes | ⬜ | Display full inventory of current volume |
| `partitions` | Yes | ⬜ | List partitions on current volume |
| `run <action>` | Yes | ⬜ | Execute an organization action |
| `hardReset` | No | ⬜ | Clear all session/cached data |
| `shutdown` | No | ✅ | Exit the application |

### 2.3 Mount Behavior

`mount <id>` authenticates and connects to a volume:

- Opens an SMB2/3 connection to the volume's share using the smbj library
- Credentials are resolved from the `servers` block in `organizer-config.yaml`
- Displays an animated spinner with phase feedback (connecting → authenticating → opening share)
- Sets the volume as the active session context and updates the prompt
- Loads the volume's index from the local database into memory — **assumes the database is current**
- If no database records exist for this volume (first use), the user is informed and prompted to run a sync command

Mount is idempotent — calling it on an already-connected volume simply acknowledges it is already active. Switching to a different volume closes the previous SMB connection first.

`unmount` closes the SMB connection and clears the active volume, connection, and index from the session.

### 2.4 Sync Behavior

Sync explicitly refreshes the database index for the currently mounted volume by walking the filesystem over SMB. It is the only way to update the database from the filesystem.

- Must be run manually when the filesystem has changed outside of the tool
- Operates on the currently mounted volume only
- Available sync commands and their scope are entirely config-driven (see §7)
- Shows a progress bar per partition during scanning
- Prints a summary of actresses and titles discovered on completion
- After completing, reloads the in-memory index from the DB

### 2.5 Safety: Test vs Armed Mode

- The application starts in **test/dry-run mode** by default
- In test mode, all file operations are simulated and logged but not executed
- The user must explicitly run `arm` to enable real file operations *(not yet implemented)*
- The prompt clearly indicates the current mode

---

## 3. Organization Actions *(not yet implemented)*

Actions are the core automation workflows. Available actions depend on the volume's structure type.

### 3.1 Conventional Volume Actions

#### `organize` (full library organization)
Runs the complete organization pipeline in sequence:
1. **Distribute queued titles** - Move titles from `queue/`, `archive/`, and `recent/` into the correct actress folder under `stars/`
2. **Flag problem titles** - Move titles that can't be matched to `attention/`
3. **Sort actress folders by tier** - Promote/demote actress folders between tier partitions based on current title count
4. **Normalize title folders** - Ensure videos are in correct subfolder structure
5. **Generate thumbnails** - Create folder thumbnails for actress directories
6. **Handle attention items** - Process previously flagged titles
7. **Reset modified dates** - Update filesystem timestamps
8. **Reindex** - Refresh all indices

#### `moveConverted`
Restore format-converted video files from the `converted/` staging folder back to their original title locations.

#### `sortActresses`
Promote or demote actress folders between tier partitions based on current title count thresholds.

#### `sortTitles`
Distribute titles from queue folders into the correct actress folders.

#### `normalizeTitles`
Normalize title folder structures (ensure proper subfolder layout).

### 3.2 Queue Volume Actions

#### `organize`
Basic folder normalization for raw/incoming content.

#### `normalize`
Clean up and normalize video filenames (remove junk, standardize format).

#### `restructure`
Organize orphaned video files into proper title folders based on code matching.

### 3.3 Collections Volume Actions *(sync not yet implemented)*

#### `organize`
Clean up converted folder and reorganize curated collection sets.

#### `moveConverted`
Restore converted files to original locations.

---

## 4. Filename Normalization *(not yet implemented)*

The system performs extensive filename cleanup by:

### 4.1 Code Normalization (Replacements)
Standardize variant markers in title codes:
- `FC2-PPV` → `FC2PPV`
- `-4K` → `_4K`
- `-UC-`, `-U-`, various uncensored markers → `_U-`

### 4.2 Junk Removal
Remove 150+ known patterns from filenames, including:
- Watermark/source site markers
- Resolution tags (`-1080P`, `-720p`, `[FHD]`, `[HD]`)
- Distribution group markers

### 4.3 Files to Ignore
- `Thumbs.db` and similar system files

---

## 5. Actress Alias System

Performers frequently work under multiple stage names. The system maintains an alias mapping so that different names resolve to the same person:

```
Aya Sazanami → also known as: Haruka Suzumiya, Aya Konami
Hibiki Otsuki → also known as: Eri Ando
```

This enables correct title-to-actress matching regardless of which name appears in the folder/file structure.

During sync, actress folders are resolved through `resolveByName` — which checks both canonical names and aliases — before creating a new actress record. This ensures that a folder named with an alias is correctly attributed to the canonical actress rather than creating a duplicate.

---

## 6. Local Database

The application maintains a local SQLite database at `~/.organizer3/organizer.db` that persists information across sessions. The database is always available for querying and updating regardless of which volume (if any) is currently mounted.

The database stores:
- **Volume records** - Known volumes and their last-sync timestamps
- **Actress records** - Canonical names, tiers, favorite status, first-seen dates
- **Alias mappings** - Alternate names resolving to canonical actresses
- **Title records** - Codes, labels, locations, volume, partition, associated actress
- **Video records** - Individual files matched to titles
- **Operation history** - Audit log of all file operations

The database is a persistent cache of the filesystem. It is populated by sync commands and queried by commands. The in-memory index built at `mount` time is a session-level cache of the database.

---

## 7. Sync Configuration

Available sync commands and their scope are defined in the `syncConfig` section of `organizer-config.yaml`, not hardcoded. Each entry binds a user-facing command term to a structure type and operation type.

Current bindings:

| Structure type | Term | Operation | Scope |
|----------------|------|-----------|-------|
| conventional | `sync queue` | PARTITION | `queue` partition only |
| conventional | `sync all` | FULL | entire volume |
| stars-flat | `sync all` | FULL | entire volume |
| queue | `sync` | FULL | entire volume |
| queue | `sync all` | FULL | same as `sync` (alias) |
| collections | *(none)* | — | not yet implemented |

To add a new sync term, add an entry under `syncConfig` in the YAML. No Java changes needed.

---

## 8. Indexing

When a volume is mounted, the application loads its index from the local database into memory. No filesystem scan occurs at mount time — the database is assumed to be current.

The in-memory `VolumeIndex` contains:
- All `Title` records for the active volume
- All `Actress` records referenced by those titles (via non-null `actress_id`)

The index is refreshed by running a sync command. All operational commands work against the in-memory index for the active volume; cross-volume queries (actresses, favorites) go directly to the DB.

---

## 9. Known Actress Database

A pre-configured list of known actresses is provided as seed data via `aliases.yaml`. On first run, this data is imported into the local database, which then becomes the authoritative source.

---

## 10. File Operations *(not yet implemented)*

All file operations go through a batch operation builder that:
1. Collects all intended operations (moves, renames, creates)
2. Presents them for review (in test mode, this is the final step)
3. Executes them atomically when armed
4. Logs all operations for audit

Operations include:
- **Move** - Relocate a file or folder
- **Rename** - Change a file/folder name
- **Create directory** - Make new folders as needed

---

## 11. Logging

The system provides comprehensive operation logging:
- Session-based log files
- Console echo of important events
- Log rotation (configurable max files, default 3)
- All file operations logged regardless of test/armed mode

---

## 12. Configuration

Structural configuration is externalized in YAML files. Mutable data (actresses, titles, aliases) lives in the local database and is not stored in config files.

| File | Purpose |
|------|---------|
| `organizer-config.yaml` | Volume definitions, server credentials, SMB paths, structure types, sync commands |
| `aliases.yaml` | Seed data for actress alias mappings (imported into DB on first run) |
