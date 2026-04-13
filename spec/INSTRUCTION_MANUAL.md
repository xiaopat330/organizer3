# Organizer3 — Instruction Manual

---

## Quick Start

### Viewer (Web UI)

1. Ensure the app is running (someone with admin access started it)
2. Open a browser and go to `http://<host>:8080`
3. Browse titles and actresses, play videos, mark favorites

That's it. No login, no setup.

---

### Admin

1. Launch the app from the project directory:
   ```
   ./gradlew run
   ```
2. The shell opens in **dry-run mode** (safe — no file operations execute):
   ```
   organizer [*DRYRUN*] >
   ```
3. Connect to a volume and sync it:
   ```
   organizer > mount a
   organizer:vol-a [*DRYRUN*] > sync all
   ```
4. The web UI at `http://localhost:8080` is now browsable.

Type `help` at any time to list available commands.

---

## Part I — Viewer Guide

The viewer role is entirely web-based. No shell access or configuration is needed.

### Navigating the UI

The top navigation bar has two sections: **Titles** and **Actresses**. Click either to enter that section. The app name in the top-left returns you to the titles home.

#### Titles

The Titles section offers several browsing modes, accessible from the left sidebar:

| Mode | Description |
|------|-------------|
| Dashboard | Featured content, recently added, and spotlights |
| Favorites | Titles you have marked as favorite |
| Bookmarks | Titles you have bookmarked for later |
| By Studio | Browse by production company or label |
| By Tag | Browse by content tag |
| Unsorted | Titles not yet organized into the main library |
| Archive | Archived titles |
| Collections | Curated multi-actress sets |

#### Actresses

The Actresses section also has multiple modes:

| Mode | Description |
|------|-------------|
| Dashboard | Top performers, recent additions, spotlights |
| Favorites | Actresses you have marked as favorite |
| Bookmarks | Actresses you have bookmarked |
| By Tier | Browse by tier (Goddess, Superstar, Popular, Minor, Library) |
| By Studio | Browse actresses by the studios they appear in |
| Exhibition | Overflow/exhibition volume content |
| Archives | Archived actress content |

#### Search

The search bar at the top of the home screen searches across actresses, titles, labels, and studios simultaneously. Results are grouped by category. Use the toggle filters to narrow results to specific categories.

---

### Title Detail

Click any title card to open its detail view. From here you can:

- **Play the video** — streams directly from the NAS over SMB (no download)
- **Mark as favorite** — heart icon
- **Bookmark** — bookmark icon
- **Grade** — rate the title (SSS / SS / S / A+ / A / B+ / B / C+ / C)
- **Add notes** — free-text field for personal notes
- **View actress** — click the actress name to go to her profile

Watch history is recorded automatically when you play a video.

---

### Actress Detail

Click any actress card to open her profile. From here you can:

- **Mark as favorite**
- **Bookmark**
- **Grade** the actress
- Browse her full title list with covers
- See her studio affiliations and tier

---

### Actress Tiers

Actresses are automatically classified by how many titles they have in the library:

| Tier | Title count |
|------|-------------|
| Library | Fewer than 5 |
| Minor | 5 – 19 |
| Popular | 20 – 49 |
| Superstar | 50 – 99 |
| Goddess | 100+ |

Tiers update automatically when the library is synced.

---

## Part II — Admin Guide

The admin role manages the library through the interactive shell (terminal or web terminal). This includes connecting to volumes, syncing the index, managing backups, and running maintenance tasks.

---

### Starting the Shell

```
./gradlew run
```

The prompt reflects current state at all times:

```
organizer [*DRYRUN*] >          # No volume mounted, dry-run mode
organizer:vol-a [*DRYRUN*] >   # Volume "a" mounted, dry-run mode
organizer:vol-a >               # Volume "a" mounted, armed mode
```

The shell also runs embedded in the web UI. Open the terminal panel at the bottom of any page to issue commands from the browser without SSH access.

---

### Volumes

Volumes are the individual NAS shares that make up the library. Each has an ID, a structure type, and a server it lives on.

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
| classic | //qnap2/JAV/classic | exhibition | Classic content |
| pool | //pandora/jav_unsorted/_done | sort_pool | Sorted, awaiting placement |
| classic_pool | //qnap2/JAV/classic/new | sort_pool | Classic intake |
| collections | //pandora/jav_collections | collections | Curated sets |

List all volumes with sync status:

```
organizer > volumes
ID          STRUCTURE       CONNECTED   LAST SYNC
------------------------------------------------------------
a           conventional    -           2026-04-01 14:22
bg          conventional    -           2026-03-28 09:00
unsorted    queue           -           never
...
```

---

### Connecting to a Volume

```
mount <id>
```

Opens an SMB connection, loads the volume's index from the local database, and sets it as the active context. A spinner shows connection progress.

```
organizer > mount a
Loaded index: 3,241 title(s), 187 actress(es).
Connected. Volume 'a' is now active.
organizer:vol-a [*DRYRUN*] >
```

If the volume has never been synced:

```
organizer > mount unsorted
No index found for volume 'unsorted' — run 'sync all' to build it.
Connected. Volume 'unsorted' is now active.
```

Switching volumes automatically closes the previous connection:

```
organizer:vol-a > mount bg
Connected. Volume 'bg' is now active.
organizer:vol-bg [*DRYRUN*] >
```

Disconnect with:

```
organizer:vol-a > unmount
```

---

### Syncing a Volume

Sync walks the SMB filesystem and updates the local database index. It must be run after any changes to the filesystem — new files, moved content, or a fresh database.

**Full sync** (re-indexes the entire volume):

```
organizer:vol-a > sync all
```

Available on `conventional`, `exhibition`, `queue`, `sort_pool`, and `collections` volumes.

**Queue-only sync** (faster — rescans only the intake partition):

```
organizer:vol-a > sync queue
```

Available on `conventional` volumes. Use this when you've only added new titles to the queue and don't need to rescan the full stars tree.

**`sync`** (for queue-type volumes):

```
organizer:vol-unsorted > sync
```

Sync output shows progress per partition and a summary on completion:

```
Syncing a (full) ...
  Scanning stars/library/ ...
  Scanning stars/popular/ ...
  ...
Sync complete.
  Actresses:  187
  Queue:      12
  Total:      3,241
```

**Rebuild** (full sync + cover scan in one step):

```
organizer:vol-a > rebuild
```

Runs `sync all` followed by `sync covers`. Use this after major library changes.

---

### Cover Images

Cover images are collected from the NAS and stored locally for the web UI. They are not required — titles without covers show a placeholder.

**Collect covers for the mounted volume:**

```
organizer:vol-a > sync covers
```

Only visits the `stars/` partitions. Covers are stored under `data/covers/`.

**Remove orphaned covers** (covers whose title no longer exists in the database):

```
organizer > prune-covers
```

Does not require a mounted volume.

---

### Querying the Database

These commands work without a mounted volume — they query the local database directly.

**List actresses by tier:**

```
organizer > actresses goddess
GODDESS  (5 actresses)
  NAME                                      TITLES
  ------------------------------------------------
  Yua Mikami                                127
  Aya Sazanami                               98
  ...
```

Valid tiers: `library`, `minor`, `popular`, `superstar`, `goddess`

**List favorited actresses:**

```
organizer > favorites
```

---

### Backup and Restore

User data — favorites, bookmarks, grades, visit counts, notes, and watch history — is stored in the local database at `~/.organizer3/organizer.db`. This data is not recovered by a sync after a database drop. The backup system protects it.

#### Automatic backups

Backups run automatically once a week in the background. No action needed. Up to 10 timestamped snapshots are kept; the oldest is pruned when a new one is written.

Snapshots are stored under `data/backups/`:

```
data/backups/
  user-data-backup-2026-04-06T14-00-00.json
  user-data-backup-2026-04-07T14-00-00.json
  ...
  user-data-backup-2026-04-13T14-00-00.json   ← newest
```

#### Manual backup

```
organizer > backup
```

Writes a new timestamped snapshot immediately and prunes the oldest if needed.

In dry-run mode, reports what would be exported without writing.

#### Restore

```
organizer > restore
```

Restores from the newest snapshot automatically. To restore from a specific file:

```
organizer > restore data/backups/user-data-backup-2026-04-06T14-00-00.json
```

Restore is an **overlay** — it only fills in fields from the backup. Rows not in the backup are untouched. Actress and title entries not yet present in the database are skipped with a count reported; sync those volumes first and run `restore` again to pick them up.

In dry-run mode, parses the backup and reports counts without touching the database.

#### Post-drop recovery workflow

After dropping and recreating the database:

1. `sync all` on each volume (rebuilds titles and actresses)
2. `restore` (overlays favorites, grades, bookmarks, watch history)

---

### Actress Name Lookup

The app can look up actress kanji/full names via the Claude API when `ANTHROPIC_API_KEY` is set in the environment. This is used by the `actress search` command.

```
organizer > actress search <name>
```

If the API key is not set, the lookup is silently disabled and the command falls back to database-only results.

---

### Safety: Dry-Run vs Armed Mode

The shell starts in **dry-run mode** by default. In this mode, commands that would modify files report what they would do without making any changes. The prompt shows `[*DRYRUN*]` as a reminder.

File operation commands (`arm` / `test` toggle) are not yet implemented — the mode is currently set at startup only. Sync commands and backup/restore are unaffected by dry-run mode:

- **Sync** is always read-only from the filesystem's perspective (writes only to the local database)
- **Backup** is suppressed in dry-run (reports counts only, does not write the file)
- **Restore** is suppressed in dry-run (reads and reports counts, does not touch the database)

---

### All Shell Commands

| Command | Requires mount | Description |
|---------|---------------|-------------|
| `help` | No | List all available commands |
| `volumes` | No | List volumes with sync status |
| `mount <id>` | — | Connect to a volume |
| `unmount` | No | Disconnect from the current volume |
| `sync all` | Yes | Full sync for conventional, exhibition, queue, and pool volumes |
| `sync queue` | Yes | Queue-partition-only sync for conventional volumes |
| `sync` | Yes | Full sync for queue-type volumes |
| `sync covers` | Yes | Collect cover images from the mounted volume |
| `prune-covers` | No | Remove orphaned local cover images |
| `prune-thumbnails` | No | Remove orphaned local thumbnails |
| `clear-thumbnails` | No | Delete all local thumbnails |
| `rebuild` | Yes | sync all + sync covers in one step |
| `actresses <tier>` | No | List actresses in a tier with title counts |
| `favorites` | No | List favorited actresses |
| `actress search <name>` | No | Search for an actress by name |
| `check-names` | No | Validate actress name formatting |
| `scan-errors` | No | Report data integrity issues |
| `load actress <slug>` | No | Load actress metadata from a YAML profile |
| `load actresses` | No | Load all actress YAML profiles |
| `backup` | No | Write a manual backup snapshot |
| `restore [path]` | No | Restore user data from backup |
| `shutdown` | No | Exit the application |

---

### Web Terminal

The web terminal embeds the full admin shell in the browser. Open it from the panel at the bottom of any page at `http://<host>:8080`. All shell commands work identically. This is useful for quick admin tasks without opening a terminal session.

---

### Configuration

The app is configured via `organizer-config.yaml`. Key sections:

| Section | Purpose |
|---------|---------|
| `servers` | SMB server credentials (host, username, password) |
| `volumes` | Volume definitions (id, SMB path, structure type, server) |
| `structures` | Partition layouts for each structure type |
| `syncConfig` | Which sync commands apply to which structure types |
| `backup` | Auto-backup interval and snapshot retention count |
| `dataDir` | Root directory for local data (covers, thumbnails, backups) |

The database lives at `~/.organizer3/organizer.db` and is managed automatically.
