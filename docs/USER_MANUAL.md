# Organizer3 User Manual

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Overview](#overview)
3. [The Shell](#the-shell)
4. [Volumes](#volumes)
5. [Mounting a Volume](#mounting-a-volume)
6. [Syncing a Volume](#syncing-a-volume)
7. [Test Mode and Armed Mode](#test-mode-and-armed-mode)
8. [Appendix: Command Reference](#appendix-command-reference)

---

## Quick Start

**Start the application:**
```
./run.sh
```

The shell starts in **test (dry-run) mode** by default. No file operations will be executed until you explicitly arm it.

**Typical first session:**
```
organizer [*DRYRUN*] > volumes
organizer [*DRYRUN*] > mount a
organizer:vol-a [*DRYRUN*] > sync-all
organizer:vol-a [*DRYRUN*] > list
```

**When you're ready to make real changes:**
```
organizer:vol-a [*DRYRUN*] > arm
organizer:vol-a > run organize
```

**Always return to safe mode when done:**
```
organizer:vol-a > test
organizer:vol-a [*DRYRUN*] > shutdown
```

---

## Overview

Organizer3 is a CLI tool for managing a media library distributed across multiple network-attached storage (NAS) volumes. It:

- Mounts SMB shares at the OS level and indexes their contents in a local database
- Organizes titles into actress folders, sorted by tier (popularity)
- Normalizes filenames, resolves actress aliases, and handles incoming queues
- Maintains a full audit log of all file operations

The application maintains a local SQLite database that persists across sessions. The database is a cache of the filesystem — it is populated by `sync` and queried by all other commands.

---

## The Shell

The shell is an interactive REPL with modern editing features:

- **Fish-style autosuggestions** — ghost text from your command history appears as you type
- **Tab completion** — completes command names, volume IDs, and actress names
- **Ctrl+R** — reverse history search
- **Arrow keys** — navigate history

### The Prompt

The prompt shows your current context at a glance:

| Prompt | Meaning |
|--------|---------|
| `organizer [*DRYRUN*] >` | No volume mounted, test mode |
| `organizer:vol-a [*DRYRUN*] >` | Volume `a` mounted, test mode |
| `organizer:vol-a >` | Volume `a` mounted, armed (live mode) |

---

## Volumes

A **volume** is a network share mapped to a local mount point. Each volume has:

- A short **ID** (e.g., `a`, `bg`, `hj`, `unsorted`)
- An **SMB path** (e.g., `//pandora/jav_A`)
- A **mount point** (e.g., `/Volumes/jav_A`)
- A **structure type** that determines its folder layout and available commands

### Structure Types

| Type | Description |
|------|-------------|
| `conventional` | Main library volumes — organized by actress under `stars/`, with queue, archive, and other folders |
| `queue` | Intake/staging volumes — a single `fresh/` folder for incoming titles |
| `collections` | Curated sets — multiple flat folders, no actress tree |

### Actress Tiers (Conventional Volumes)

Actress folders under `stars/` are sorted into sub-folders by title count:

| Folder | Tier | Title Count |
|--------|------|-------------|
| `stars/library/` | Library | fewer than 5 |
| `stars/minor/` | Minor | 5 – 19 |
| `stars/popular/` | Popular | 20 – 49 |
| `stars/superstar/` | Superstar | 50 – 99 |
| `stars/goddess/` | Goddess | 100+ |
| `stars/favorites/` | Favorites | user-curated |
| `stars/archive/` | Archive | archived content |

---

## Mounting a Volume

Before you can run most commands, you need to mount a volume:

```
mount <volume-id>
```

**What this does:**
1. Looks up the volume's SMB path and credentials (from macOS Keychain)
2. Mounts the share at the OS level via `mount_smbfs` if not already mounted
3. Loads the volume's index from the local database into memory
4. Sets the volume as your active session context

**Notes:**
- `mount` is idempotent — calling it on an already-mounted volume simply reactivates it as the session context
- Only one volume is active at a time, but multiple volumes may remain OS-mounted
- OS mounts are never unmounted by the application
- If no database record exists for the volume yet, you will be prompted to run `sync-all`

**Credentials:** Credentials are stored in the macOS Keychain, not in any config file. If credentials are missing, the mount command will print the `security` command you need to run to add them.

---

## Syncing a Volume

The database is a cache of the filesystem. After any changes are made to a volume outside of Organizer (downloads, manual moves, etc.), you need to sync to update the database.

Sync commands available depend on the volume's structure type:

| Command | Valid For | Scope |
|---------|-----------|-------|
| `sync-all` | `conventional`, `queue` | Entire volume — clears and rebuilds all records |
| `sync-queue` | `conventional` | Queue partition only — faster partial update |
| `sync` | `queue` | Entire volume (same as `sync-all` for queue volumes) |

**What sync does:**
- Walks the filesystem and finds all title folders and video files
- Creates or updates database records for titles, videos, and actresses
- Stamps `last_synced_at` on the volume record
- Rebuilds the in-memory index

Sync always reads the real filesystem regardless of dry-run mode — it is read-only from the filesystem's perspective.

---

## Test Mode and Armed Mode

Organizer starts in **test (dry-run) mode** every time. In this mode, all file operations are simulated and logged but never executed. This lets you preview the effect of any action before committing.

```
test    # switch to dry-run mode (default on startup)
arm     # switch to live mode — file operations will execute
```

The prompt shows `[*DRYRUN*]` whenever test mode is active. When armed, no indicator appears — the absence of `[*DRYRUN*]` means operations are live.

> **Best practice:** Always run commands in test mode first to review the planned operations. Only `arm` when you are satisfied with what will happen.

---

## Appendix: Command Reference

### Session Commands

| Command | Requires Mount | Description |
|---------|---------------|-------------|
| `help` | No | List available commands |
| `test` | No | Switch to dry-run/test mode (default on startup) |
| `arm` | No | Switch to armed/live mode — file operations will execute |
| `shutdown` | No | Exit the application |

### Volume Commands

| Command | Requires Mount | Description |
|---------|---------------|-------------|
| `volumes` | No | List all configured volumes with last-sync timestamps |
| `mount <id>` | — | Mount an SMB share and activate it as the current volume |
| `currentVolume` | No | Show the currently mounted volume |
| `list` | Yes | Display the full inventory of the current volume |
| `partitions` | Yes | List the partitions on the current volume |

### Sync Commands

| Command | Requires Mount | Description |
|---------|---------------|-------------|
| `sync-all` | Yes | Full sync — rebuilds the entire volume index from the filesystem |
| `sync-queue` | Yes | Partial sync — refreshes the queue partition only (conventional volumes) |
| `sync` | Yes | Full sync for queue volumes |

### Actress Commands

| Command | Requires Mount | Description |
|---------|---------------|-------------|
| `actresses` | No | List all known actresses with title counts (from database) |
| `actress <name>` | No | Show details for a specific actress (from database) |

### Organization Commands

Run organization actions with `run <action>`. Available actions depend on the volume's structure type.

```
run <action>
```

#### Conventional Volume Actions

| Action | Description |
|--------|-------------|
| `organize` | Full organization pipeline: distribute queued titles, flag problem titles, sort actress tiers, normalize folders, generate thumbnails, handle attention items, reset dates, reindex |
| `sortActresses` | Promote/demote actress folders between tier partitions based on current title count |
| `sortTitles` | Distribute titles from queue folders into the correct actress folders |
| `normalizeTitles` | Normalize title folder structures (ensure proper subfolder layout) |
| `moveConverted` | Restore format-converted files from `converted/` back to their original title locations |

#### Queue Volume Actions

| Action | Description |
|--------|-------------|
| `organize` | Basic folder normalization for raw incoming content |
| `normalize` | Clean up and normalize video filenames (remove junk, standardize format) |
| `restructure` | Organize orphaned video files into title folders based on code matching |

#### Collections Volume Actions

| Action | Description |
|--------|-------------|
| `organize` | Clean up converted folder and reorganize curated collection sets |
| `moveConverted` | Restore converted files to original locations |

---

*This manual is updated as new features are added. For design and implementation details, see [spec/FUNCTIONAL_SPEC.md](../spec/FUNCTIONAL_SPEC.md) and [spec/IMPLEMENTATION_NOTES.md](../spec/IMPLEMENTATION_NOTES.md).*
