# Organizer3 — Instruction Manual

---

## Quick Start

### Viewer (Web UI)

1. Ensure the app is running (someone with admin access started it)
2. Open a browser and go to `http://<host>:8080`
3. Browse titles, actresses, and AV stars content; play videos; mark favorites

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

### Navigating the UI

The top navigation bar has three sections: **Titles**, **Actresses**, and **AV Stars**. Click any to enter that section. The app name in the top-left returns you to the home screen.

#### Titles

| Mode | Description |
|------|-------------|
| Dashboard | Featured content, recently added, spotlights |
| Favorites | Titles you have marked as favorite |
| Bookmarks | Titles you have bookmarked for later |
| By Studio | Browse by production company or label |
| By Tag | Browse by content tag |
| Unsorted | Titles not yet organized into the main library |
| Archive | Archived titles |
| Collections | Curated multi-actress sets |

#### Actresses (JAV)

| Mode | Description |
|------|-------------|
| Dashboard | Top performers, recent additions, spotlights |
| Favorites | Actresses you have marked as favorite |
| Bookmarks | Actresses you have bookmarked |
| By Tier | Browse by tier (Goddess, Superstar, Popular, Minor, Library) |
| By Studio | Browse actresses by the studios they appear in |
| Exhibition | Overflow/exhibition volume content |
| Archives | Archived actress content |

#### AV Stars

| Mode | Description |
|------|-------------|
| Browse | All Western performers sorted by video count, with headshot thumbnails |
| Actress detail | Profile, video grid with screenshot thumbnails, tags |
| Video modal | Inline video player, favorite/bookmark/watched controls |

#### Search

The search bar on the home screen searches across JAV actresses, JAV titles, labels, studios, and AV actresses simultaneously. Results are grouped by category. Use the toggle filters to narrow by category.

---

### Title Detail (JAV)

Click any title card to open its detail view. From here you can:

- **Play the video** — streams directly from the NAS over SMB (no download)
- **Mark as favorite** — heart icon
- **Bookmark** — bookmark icon
- **Grade** — rate the title (SSS / SS / S / A+ / A / B+ / B / C+ / C)
- **Add notes** — free-text field for personal notes
- **View actress** — click the actress name to go to her profile

Watch history is recorded automatically when you play a video.

---

### Actress Detail (JAV)

Click any actress card to open her profile. From here you can:

- **Mark as favorite** and **bookmark**
- **Grade** the actress (same scale as titles)
- Browse her full title list with covers
- See her studio affiliations, tier, and biography

---

### AV Actress Detail

Click any AV actress card to open her profile. From here you can:

- **Mark as favorite**, **bookmark**, **reject**, or **grade** her
- See IAFD-sourced profile data (nationality, measurements, career span, etc.)
- Browse her video grid with screenshot thumbnails
- Play any video inline with the video modal
- Mark individual videos as **favorite**, **bookmark**, or **watched**

---

### JAV Actress Tiers

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

The admin role manages the library through the interactive shell. This includes connecting to volumes, syncing the index, managing backups, and running AV enrichment commands.

---

### Starting the Shell

```
./gradlew run
```

The prompt reflects current state:

```
organizer [*DRYRUN*] >          # no volume mounted, dry-run mode
organizer:vol-a [*DRYRUN*] >   # volume "a" mounted, dry-run mode
organizer:vol-a >               # volume "a" mounted, armed mode
```

The shell also runs embedded in the web UI. Open the terminal panel at the bottom of any page to issue commands from the browser.

---

### Volumes

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

### Syncing a Volume

**Full sync** (re-indexes the entire volume):
```
organizer:vol-a > sync all
```

**Queue-only sync** (faster — rescans only the intake partition):
```
organizer:vol-a > sync queue
```

**Rebuild** (sync all + cover collection):
```
organizer:vol-a > rebuild
```

**AV sync** (no mount required — syncs all AV volumes):
```
organizer > av sync
```

---

### Cover Images

```
organizer:vol-a > sync covers     # collect covers from mounted volume
organizer > prune-covers          # remove orphaned covers
```

---

### AV Stars Management

These commands do not require a mounted volume.

**Sync AV library:**
```
organizer > av sync
```

**Browse:**
```
organizer > av actresses        # list all, sorted by video count
organizer > av actress Anissa Kate
organizer > av favorites
```

**Enrich with IAFD data:**
```
organizer > av resolve Anissa Kate    # resolve one actress
organizer > av resolve all            # resolve all unresolved
```

Note: VPN to Japan is NOT needed for IAFD (it's a US site). VPN is only needed for DMM/FANZA.

**Curation:**
```
organizer > av curate Anissa Kate     # set favorite/grade/notes interactively
```

**Migrate after folder rename on disk:**
```
organizer > av migrate "Old Name" "New Name"
```

**Parse filenames:**
```
organizer > av parse              # extract studio/date/tags from all video filenames
```

**Generate screenshots:**
```
organizer > av screenshots        # extract frame previews for videos without them
```

---

### Backup and Restore

User data — favorites, bookmarks, grades, visit counts, notes, watch history for both JAV and AV content — is stored in the local database. It cannot be recovered by re-syncing volumes. The backup system protects it.

#### Automatic backups

Backups run automatically in the background (weekly by default). Timestamped snapshots are kept in `data/backups/`. Up to 10 snapshots are retained; the oldest is pruned when a new one is written.

#### Manual backup

```
organizer > backup
Exported 1,204 actress, 18,432 title, 847 watch history, 62 av-actress, 1,841 av-video records.
Backup written to: data/backups/user-data-backup-2026-04-14T22-30-00.json
```

In dry-run mode: reports counts but does not write.

#### Restore

```
organizer > restore
```

Restores from the newest snapshot automatically. To restore from a specific file:

```
organizer > restore data/backups/user-data-backup-2026-04-06T14-00-00.json
```

Restore is an **overlay** — only fills in fields from the backup; untouched rows are left as-is. Entities not yet in the DB are skipped; sync remaining volumes and run `restore` again.

In dry-run mode: parses and reports counts without touching the database.

#### Full recovery workflow after a database drop

1. `load actresses` — reseeds JAV actress profiles from YAML
2. Mount each JAV volume → `sync all`
3. `av sync` — rebuilds AV records
4. `restore` — overlays all user-altered fields

---

### Safety: Dry-Run vs Armed Mode

The shell starts in **dry-run mode** (`[*DRYRUN*]` in prompt). In this mode, commands that would modify files report what they would do without making changes. `arm`/`test` toggle commands are not yet implemented — the mode is set only at startup.

- **Sync** — always read-only from the filesystem (writes only to the local DB)
- **AV sync** — same, read-only
- **Backup** — suppressed in dry-run (reports counts, does not write)
- **Restore** — suppressed in dry-run (reports counts, does not touch DB)

---

### All Shell Commands

| Command | Mount? | Description |
|---------|--------|-------------|
| `help` | No | List all available commands |
| `volumes` | No | List volumes with sync status |
| `mount <id>` | — | Connect to a volume |
| `unmount` | No | Disconnect from the current volume |
| `sync all` | Yes | Full sync (conventional/exhibition/queue/sort\_pool/avstars) |
| `sync queue` | Yes | Queue-partition-only sync for conventional volumes |
| `sync` | Yes | Full sync for queue-type volumes |
| `sync covers` | Yes | Collect cover images from mounted volume |
| `prune-covers` | No | Remove orphaned local cover images |
| `prune-thumbnails` | No | Remove orphaned local thumbnails |
| `clear-thumbnails` | No | Delete all local thumbnails |
| `rebuild` | Yes | sync all + sync covers |
| `actresses <tier>` | No | List JAV actresses in a tier with title counts |
| `favorites` | No | List favorited JAV actresses |
| `actress search <name>` | No | Search for a JAV actress by name |
| `check-names` | No | Validate actress name formatting |
| `scan-errors` | No | Report data integrity issues |
| `load actress <slug>` | No | Load actress metadata from a YAML profile |
| `load actresses` | No | Load all actress YAML profiles |
| `av sync` | No | Sync all AV volumes |
| `av actresses` | No | List AV actresses by video count |
| `av actress <name>` | No | Show AV actress detail |
| `av favorites` | No | List favorited AV actresses |
| `av resolve <name>` | No | Resolve one AV actress against IAFD |
| `av resolve all` | No | Resolve all unresolved AV actresses against IAFD |
| `av curate <name>` | No | Set curation fields for an AV actress |
| `av migrate <old> <new>` | No | Migrate curation when actress folder is renamed |
| `av parse` | No | Parse metadata from AV video filenames |
| `av screenshots` | No | Generate screenshot frames for AV videos |
| `av tags <subcommand>` | No | Manage AV tag definitions and video tags |
| `backup` | No | Write a manual backup snapshot |
| `restore [path]` | No | Restore user data from backup |
| `shutdown` | No | Exit the application |

---

### Web Terminal

The web terminal embeds the full admin shell in the browser. Open it from the panel at the bottom of any page at `http://<host>:8080`. All shell commands work identically. Useful for quick admin tasks without opening a separate terminal session.

---

### Configuration

The app is configured via `organizer-config.yaml`. Key sections:

| Section | Purpose |
|---------|---------|
| `servers` | SMB server credentials (host, username, password) |
| `volumes` | Volume definitions (id, SMB path, structure type, server) |
| `structures` | Partition layouts and ignored subfolders per structure type |
| `syncConfig` | Which sync commands apply to which structure types |
| `backup` | `autoBackupIntervalMinutes` and `snapshotCount` |
| `dataDir` | Root for local data: covers, thumbnails, backups, AV headshots/screenshots |

The database lives at `~/.organizer3/organizer.db` and is managed automatically.
