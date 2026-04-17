# Organizer3 — Instruction Manual

---

## Quick Start

### Viewer (Web UI)

1. Ensure the app is running (someone with admin access started it).
2. Open a browser and go to `http://<host>:8080`.
3. Browse titles, actresses, and AV stars content; play videos; mark favorites.

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

### Working with an AI agent

Organizer3 exposes an **MCP (Model Context Protocol) server** at `http://localhost:8080/mcp`. This lets tools like Claude Desktop or the Claude CLI drive the library — find duplicates, clean up folder anomalies, attribute actresses, run the full organize pipeline — using natural-language prompts. See **Part III** below.

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

Actresses are automatically classified by how many titles they have in the library. Thresholds are defined in `organizer-config.yaml` under `library:`; defaults:

| Tier | Title count |
|------|-------------|
| Pool | Fewer than 3 (no folder — titles stay in the volume's queue) |
| Library | 3 – 4 |
| Minor | 5 – 19 |
| Popular | 20 – 49 |
| Superstar | 50 – 99 |
| Goddess | 100+ |

Tiers progress upward only. Once an actress crosses a threshold, she stays in the higher tier — title count dropping later doesn't demote her.

---

## Part II — Admin Guide

The admin role manages the library through the interactive shell. This includes connecting to volumes, syncing the index, running the organize pipeline, managing backups, and AV enrichment.

---

### Starting the Shell

```
./gradlew run
```

The prompt reflects current state:

```
organizer [*DRYRUN*] >          # no volume mounted, dry-run mode
organizer:vol-a [*DRYRUN*] >    # volume "a" mounted, dry-run mode
organizer:vol-a >                # volume "a" mounted, armed mode
```

The shell also runs embedded in the web UI. Open the terminal panel at the bottom of any page to issue commands from the browser.

---

### Volumes

Volumes are conventional (letter-mapped), queue (intake), sort_pool (staging), exhibition (overflow), collections (multi-actress sets), or avstars (Western performers).

| ID | SMB Path | Structure | Letters / Content |
|----|----------|-----------|-------------------|
| a | //pandora/jav_A | conventional | A |
| bg | //pandora/jav_BG | conventional | B–G |
| hj | //pandora/jav_HJ | conventional | H–J |
| k | //pandora/jav_K | conventional | K |
| m | //pandora/jav_M | conventional | M (except Ma-) |
| ma | //pandora/jav_MA | conventional | Ma- prefix only |
| n | //pandora/jav_N | conventional | N |
| r | //pandora/jav_OR | conventional | O–R |
| s | //pandora/jav_S | conventional | S |
| tz | //pandora/jav_TZ | conventional | T–Z |
| unsorted | //pandora/jav_unsorted | queue | Raw ingestion |
| pool | //pandora/jav_unsorted/_done | sort_pool | Post-normalization staging |
| qnap | //qnap2/jav | exhibition | Overflow |
| qnap_archive | //pandora/qnap_archive | exhibition | Archive overflow |
| classic | //qnap2/JAV/classic | exhibition | Classic |
| classic_pool | //qnap2/JAV/classic/new | sort_pool | Classic intake |
| collections | //pandora/jav_collections | collections | Multi-actress sets |
| qnap_av | //qnap2/AV/stars | avstars | Western performers |
| athena_av | //athena/AV/stars | avstars | Western performers (secondary) |

Letter ranges are configured per-volume in `organizer-config.yaml` under each volume's `letters:` field. The sort phase uses these to detect actress/volume mismatches.

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

### The Organize Pipeline

After new content arrives in a letter-volume's `/queue/` partition (moved there manually by you from the global pool), the organize pipeline takes over. Four phases run per title, plus one per affected actress:

| Phase | What it does |
|-------|--------------|
| 1. normalize | Rename the title's sole cover + sole video to canonical `{CODE}.{ext}` |
| 2. restructure | Move videos from base into `video/` / `h265/` / `4K/` by filename hint |
| 3. sort | Move the title folder from `/queue/` → `/stars/{tier}/{actress}/`, or route to `/attention/` with a `REASON.txt` sidecar if it can't be filed |
| 4. classify | Re-tier the actress upward if her title count crossed a threshold |

#### Full pipeline run (recommended)

```
organizer > mount a
organizer:vol-a [*DRYRUN*] > organize            # dry-run — shows what would happen
organizer:vol-a > organize                         # armed — actually does it
```

The composite `organize` command walks every title in the mounted volume's queue partition, runs phases 1–3 per title, then phase 4 over each actress whose titles got filed. Output includes a summary and per-title error lines where anything failed.

Subset a pipeline to just a phase or two:
```
organizer:vol-a > organize normalize          # only normalize; leave layout alone
organizer:vol-a > organize normalize,sort    # normalize + sort, skip restructure/classify
```

#### Per-title phase commands

When you want to inspect or fix one title at a time:

```
organizer:vol-a > normalize-title MIDE-123      # rename cover + single video to canonical
organizer:vol-a > restructure-title MIDE-123    # move videos into video/h265/4K subfolder
organizer:vol-a > sort-title MIDE-123            # /queue/ → /stars/{tier}/{actress}/ or attention
organizer:vol-a > classify-actress 4506         # re-tier actress 4506 if she crossed a threshold
```

#### Attention partition

When sort can't file a title, it moves it to `/attention/<title-folder>/` on the volume and drops a `REASON.txt` inside. Reasons include:

- `actressless-title` — no filing actress in the DB
- `actress-letter-mismatch` — the actress's canonical name doesn't start with a letter covered by this volume (typically after a rename)
- `collision` — a folder with that name already exists at the target path

The sidecar has a machine-readable header block (reason / volume / originalPath / moved-at) plus a human-readable explanation paragraph. The tag file travels with the folder if you move it via the NAS UI, so context is never lost.

---

### Timestamp correction

Title folders on the NAS often have meaningless timestamps — they reflect the most recent copy/move, not when the title joined your catalog. Sorting by date in Finder or QNAP UI becomes useless.

The timestamp-correction tool scans the folder's contents and sets the folder's `created` + `lastWrite` time to the earliest timestamp found among children (across both created and modified fields — the min catches original authoring time even when the NAS stamped a recent copy date).

**One title at a time:**
```
organizer:vol-a > fix-title-timestamps MIDE-123
```

**Whole volume (armed mode):**
```
organizer:vol-a > audit-timestamps
Auditing 5099 title folders on 'a' (ARMED)...
Done. scanned=5099  needsChange=4732  changed=4732  skipped=0  errors=0  in 185.2s
```

Timestamp correction is also automatically applied by the `sort` phase as its final step, so freshly-sorted titles always have correct timestamps.

---

### Cleanup tools

These tools are also invokable via the AI agent (see Part III); the shell versions respect session dry-run. Destructive operations move to the per-volume `_trash/` folder (never deleted).

**Remove a DB title row + its dependents** (used for ghost/parser-bug rows — does not touch files):
```
# via MCP only: delete_title { id, dryRun } — gated on allowMutations
```

**Cover cleanup** — both of these require the volume's server to have `trash: _trash` configured and `mcp.allowFileOps: true`:
- `trash_duplicate_cover { titleCode, keep }` — trashes all covers at a title's base except the named `keep` file
- `move_cover_to_base { titleCode }` — moves misfiled covers from a subfolder (`video/`, `photo/`, etc.) back to the title base

**Write-permission sanity check for a volume** (diagnostic):
- `sandbox_write_test` — runs create / write / move / rename / read-back inside the volume's `_sandbox/` area. Useful when SMB permissions differ between shares.

---

### AV Stars Management

These commands do not require a mounted volume.

**Sync AV library:**
```
organizer > av sync
```

**Browse:**
```
organizer > av actresses                # list all, sorted by video count
organizer > av actress Anissa Kate
organizer > av favorites
```

**Enrich with IAFD data:**
```
organizer > av resolve Anissa Kate     # resolve one actress
organizer > av resolve all               # resolve all unresolved
```

Note: VPN to Japan is NOT needed for IAFD (US site). VPN is only needed for DMM/FANZA.

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
organizer > av parse                    # extract studio/date/tags from filenames
```

**Generate screenshots:**
```
organizer > av screenshots              # frame previews for videos without them
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

Independent of shell mode, **every organize-pipeline command and MCP mutation tool defaults to `dryRun: true` per-call**. You must explicitly request execution to make changes. See each tool's signature.

- **Sync** — always read-only from the filesystem (writes only to the local DB)
- **AV sync** — same, read-only
- **Backup** — suppressed in dry-run (reports counts, does not write)
- **Restore** — suppressed in dry-run (reports counts, does not touch DB)
- **Organize pipeline** — dry-run default per-tool (normalize, restructure, sort, classify, organize)
- **Timestamp correction** — dry-run default per-tool
- **Cover cleanup / delete_title** — dry-run default per-tool

---

### All Shell Commands

| Command | Mount? | Description |
|---------|--------|-------------|
| `help` | No | List all available commands |
| `volumes` | No | List volumes with sync status |
| `mount <id>` | — | Connect to a volume |
| `unmount` | No | Disconnect from the current volume |
| **Sync** | | |
| `sync all` | Yes | Full sync |
| `sync queue` | Yes | Queue-partition-only sync for conventional volumes |
| `sync` | Yes | Full sync for queue-type volumes |
| `sync covers` | Yes | Collect cover images |
| `prune-covers` | No | Remove orphaned local cover images |
| `prune-thumbnails` | No | Remove orphaned local thumbnails |
| `clear-thumbnails` | No | Delete all local thumbnails |
| `rebuild` | Yes | sync all + sync covers |
| `probe videos [limit]` | Yes | Backfill video metadata (duration, codec, resolution) |
| **Organize pipeline** | | |
| `organize [phases]` | Yes | Composite: walk queue + run phases. Phases CSV subset optional |
| `normalize-title <CODE>` | Yes | Phase 1 — rename cover + single video to canonical |
| `restructure-title <CODE>` | Yes | Phase 2 — move base videos into subfolder by hint |
| `sort-title <CODE>` | Yes | Phase 3 — queue → /stars/{tier}/{actress}/ or attention |
| `classify-actress <id>` | Yes | Phase 4 — re-tier actress upward by title count |
| `fix-title-timestamps <CODE>` | Yes | Set folder create + lastWrite to earliest child time |
| `audit-timestamps` | Yes | Walk the volume and fix all mis-stamped title folders |
| **Actresses (JAV)** | | |
| `actresses <tier>` | No | List JAV actresses in a tier with title counts |
| `favorites` | No | List favorited JAV actresses |
| `actress search <name>` | No | Search for a JAV actress by name |
| `check-names` | No | Validate actress name formatting |
| `scan-errors` | No | Report data integrity issues |
| `load actress <slug>` | No | Load actress metadata from a YAML profile |
| `load actresses` | No | Load all actress YAML profiles |
| **AV Stars** | | |
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
| **Backup / shutdown** | | |
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
| `servers` | SMB server credentials (host, username, password, optional `trash:` + `sandbox:` folder names) |
| `volumes` | Volume definitions (id, SMB path, structure type, server, optional `letters:` prefix list) |
| `structures` | Partition layouts and ignored subfolders per structure type |
| `syncConfig` | Which sync commands apply to which structure types |
| `backup` | `autoBackupIntervalMinutes` and `snapshotCount` |
| `library` | Tier thresholds for actress classification (star / minor / popular / superstar / goddess) |
| `normalize` | Filename strip/replace patterns for the normalize phase |
| `media` | Video and cover file extensions |
| `mcp` | MCP server config: `enabled`, `allowMutations`, `allowNetworkOps`, `allowFileOps` |
| `dataDir` | Root for local data: covers, thumbnails, backups, AV headshots/screenshots |

The database lives at `~/.organizer3/organizer.db` and is managed automatically.

**Trash / Sandbox.** When a server defines `trash: _trash` and `sandbox: _sandbox`, the app uses those folders at each share's root: `_trash/` holds items removed by cleanup tools (nothing is ever permanently deleted; you clean it up manually via the NAS UI at your own pace); `_sandbox/` is a scratch area for the app's own testing. Omit these fields to disable — tools that need them will refuse.

**MCP gates.** The MCP server is enabled by default but mutation and file-op tools are locked down. Opt in per class:

```yaml
mcp:
  enabled: true           # MCP server on/off
  allowMutations: true    # DB-write tools (merge_actresses, delete_title, etc.)
  allowNetworkOps: true   # mount_volume / unmount_volume
  allowFileOps: true      # file-moving tools (trash_*, move_cover_to_base, sort_title, organize_volume, …)
```

With all four enabled, the full organize pipeline is agent-driveable.

---

## Part III — AI Agent Integration (MCP)

Organizer3 exposes an **MCP server** at `http://localhost:8080/mcp`. This is a JSON-RPC endpoint that AI agents (Claude Desktop, Claude CLI, custom clients) can call to drive the library using natural-language prompts.

### What you can do with it

- **Diagnose**: find duplicate actresses, actressless titles, mislabeled folders, multi-cover titles, orphan titles, name-order variants, alias conflicts.
- **Investigate**: SQL the database, list directory contents on a mounted volume, read text files (useful for REASON.txt sidecars).
- **Mutate DB**: merge actresses, delete ghost title rows.
- **Mutate files**: trash duplicate covers, move misfiled covers, rename via the organize pipeline, sort titles from queue to tier folders.
- **Background work**: kick off probe-videos backfill jobs and poll their status.

### Setup: Claude Desktop

Add the MCP server to Claude Desktop's config (on macOS, `~/Library/Application Support/Claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "organizer3": {
      "command": "/usr/local/bin/npx",
      "args": ["-y", "mcp-remote", "http://localhost:8080/mcp"]
    }
  }
}
```

Restart Claude Desktop. While the Organizer3 app is running, Claude auto-connects to the MCP server. Tools show up in Claude's tool list automatically.

### Tool categories

Tools are grouped by what they touch. With all MCP gates enabled, 47 tools are available.

**Read-only (always available):**
- **Lookup**: `list_volumes`, `lookup_actress`, `lookup_title`, `list_titles_for_actress`, `get_stats`
- **Search**: `find_similar_actresses`, `find_name_order_variants`, `find_suspect_credits`, `find_alias_conflicts`, `find_lone_titles`, `find_orphan_titles`, `find_duplicate_base_codes`, `find_label_mismatches`, `find_stale_locations`, `find_misnamed_folders_for_actress`, `list_actresses_with_misnamed_folders`
- **Video diagnostics**: `list_multi_video_titles`, `analyze_title_videos`, `find_duplicate_candidates`
- **SQL + FS**: `sql_query`, `sql_tables`, `sql_schema`, `list_directory`, `read_text_file`, `describe_schema`

**Mount (`allowNetworkOps`):**
- `mount_volume`, `unmount_volume`, `mount_status`

**Mounted-volume scans (need a volume mounted):**
- Folder anomaly: `find_multi_cover_titles`, `find_misfiled_covers`, `scan_title_folder_anomalies`
- Video backfill: `probe_videos_batch`, `start_probe_job`, `probe_job_status`, `cancel_probe_job`

**DB mutation (`allowMutations`):**
- `merge_actresses`, `delete_title`

**File ops (`allowMutations` + `allowFileOps`):**
- Cover cleanup: `trash_duplicate_cover`, `move_cover_to_base`
- Organize pipeline: `normalize_title`, `restructure_title`, `sort_title`, `classify_actress`, `organize_volume`
- Timestamp correction: `fix_title_timestamps`, `audit_volume_timestamps`
- Diagnostic: `sandbox_write_test`

Every mutation tool defaults to `dryRun: true` — the agent must pass `dryRun: false` explicitly to commit changes.

---

### Example prompts

The following prompts are meant for a session with Claude Desktop (or another MCP client). Each assumes the Organizer3 app is running.

#### Diagnose: find problems

> "What's the current state of the library? Show me counts by volume and any obvious data-quality issues."

> "Are there actresses that might be duplicate records from name-order swaps (like `Aino Nami` / `Nami Aino`)? List the candidates — don't merge anything."

> "Find titles whose label in the DB doesn't match their product code's prefix."

> "Any title folders with more than one cover image at the base on volume `classic`?"

#### Investigate: read-only drill-down

> "Tell me everything about title `MIDE-123` — its filing actress, all the other credited actresses, all on-disk locations, all video files."

> "Show me actress 4506's full profile including aliases, title count by label, tier."

> "Run SQL to list the top 20 labels by title count."

> "Mount volume `a`, look inside `/attention/` — what's there and why?"

#### Clean up: multi-cover titles

> "Volume `classic` has a bunch of titles with duplicate covers. For each one, pick the canonical cover (prefer `{code}pl.jpg` naming) and trash the others. Do a dry-run first; show me the plan before executing."

> "Title `IPTD-849` has two covers. Keep `iptd849pl.jpg`, trash the other."

#### Clean up: ghost titles / actressless titles

> "Ghost 'covers' titles from the parser bug are still in the DB. Find them via SQL and delete them with `delete_title`."

> "There are titles with no filing actress in the DB. List them; for the ones that are amateur codes I'll tell you which actress to attribute each to."

#### Organize pipeline

> "Mount volume `a`, run `organize` in dry-run, and summarize what would happen. Don't execute."

> "Run the full organize pipeline on volume `bg` — normalize, restructure, sort, classify. Process 50 titles at a time, and stop at the first batch that has any errors."

> "Just normalize (don't move) the titles in `a`'s queue so the filenames are canonical."

> "Sort only `ACHJ-059` — don't touch any other titles."

> "Actress 51 just crossed 50 titles. Re-classify her so her folder moves to the superstar tier."

#### Timestamp correction

> "Volume `tz` has creation times that reflect NAS copy dates, not true authoring. Run `audit_volume_timestamps` in dry-run across the whole volume; report how many need correction. If it's more than 100, execute it."

> "Fix the timestamps on `IPZ-463` so Finder sorts it correctly."

#### Actress rename / spelling (future; judgment-only)

> "I want to rename actress 4506 `Aino Nami` to `Nami Aino` (name-order fix). Do a dry-run first to see every title folder that would get renamed and whether she ends up on the wrong volume letter-wise."

#### Probe backfill (long-running)

> "Start a probe job on volume `a`. Tell me the job id, then check back in 10 minutes with `probe_job_status`."

> "While the probe job is running, look up what else I should clean up."

#### Sanity checks / permission audits

> "Before I do any file operations on volume `qnap_archive`, run `sandbox_write_test` to confirm the SMB user has full write permission there."

---

### Tips for working with the agent

- **Always dry-run first.** Every mutation tool defaults to `dryRun: true`. When you ask for a change, the agent will usually show you the plan; you then say "OK, execute" to commit.
- **Scope by volume.** Most file-op tools need a volume mounted. Tell the agent which volume to work on, or let it pick based on the task.
- **Chain operations.** It's fine to ask for multi-step flows: "find duplicate covers, pick a canonical for each, trash the others, then fix folder timestamps." The agent will orchestrate each step.
- **Pagination.** For `organize_volume`, `audit_volume_timestamps`, `probe_videos_batch`, and scan tools, the agent handles `limit`/`offset` automatically — you don't need to think about pagination.
- **Config flags are your safety.** If you want to block a class of operation, flip the gate in `organizer-config.yaml` and restart. The tool disappears from the agent's toolset entirely.
