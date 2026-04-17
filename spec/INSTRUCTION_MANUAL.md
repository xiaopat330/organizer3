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

## Part III — MCP Server (Technical Reference)

Organizer3 exposes an **MCP (Model Context Protocol) server** at `http://localhost:8080/mcp`. This is a JSON-RPC endpoint that AI agents can call to drive the library. For a practical how-to guide on working this way, see **Part IV — Agentic Use**. This section is the technical reference.

### What MCP is

MCP is an open protocol for giving LLMs structured tool access to external systems. The Organizer3 MCP server exposes 47 tools covering everything from read-only diagnostics (run SQL, look up actresses) to full file-system mutations (run the organize pipeline). Any MCP client — Claude Desktop, Claude CLI, third-party clients — can connect.

### Enabling the server

The server is on by default but mutation and file-op tools are gated. Opt in per class in `organizer-config.yaml`:

```yaml
mcp:
  enabled: true           # MCP server on/off
  allowMutations: true    # DB-write tools (merge_actresses, delete_title, etc.)
  allowNetworkOps: true   # mount_volume / unmount_volume
  allowFileOps: true      # file-moving tools (trash_*, move_cover_to_base, sort_title, organize_volume, …)
```

When a gate is `false`, the corresponding tools disappear from the tool list entirely — the agent can't call them. This is your safety switch: flip a gate off and restart to block a whole class of operations.

### Tool categories

With all gates on, the following 47 tools are exposed:

**Read-only (always available):**
- **Lookup**: `list_volumes`, `lookup_actress`, `lookup_title`, `list_titles_for_actress`, `get_stats`, `describe_schema`
- **Search / diagnostics**: `find_similar_actresses`, `find_name_order_variants`, `find_suspect_credits`, `find_alias_conflicts`, `find_lone_titles`, `find_orphan_titles`, `find_duplicate_base_codes`, `find_label_mismatches`, `find_stale_locations`, `find_misnamed_folders_for_actress`, `list_actresses_with_misnamed_folders`
- **Video diagnostics**: `list_multi_video_titles`, `analyze_title_videos`, `find_duplicate_candidates`
- **SQL + FS**: `sql_query`, `sql_tables`, `sql_schema`, `list_directory`, `read_text_file`

**Mount (`allowNetworkOps`):**
- `mount_volume`, `unmount_volume`, `mount_status`

**Mounted-volume scans (require a mounted volume):**
- Folder anomaly: `find_multi_cover_titles`, `find_misfiled_covers`, `scan_title_folder_anomalies`
- Video backfill: `probe_videos_batch`, `start_probe_job`, `probe_job_status`, `cancel_probe_job`

**DB mutation (`allowMutations`):**
- `merge_actresses`, `delete_title`

**File ops (`allowMutations` + `allowFileOps`):**
- Cover cleanup: `trash_duplicate_cover`, `move_cover_to_base`
- Organize pipeline: `normalize_title`, `restructure_title`, `sort_title`, `classify_actress`, `organize_volume`
- Timestamp correction: `fix_title_timestamps`, `audit_volume_timestamps`
- Diagnostic: `sandbox_write_test`

Every mutation tool defaults to `dryRun: true` per call. The agent must pass `dryRun: false` explicitly to commit changes.

### Wire format

Tools are invoked via JSON-RPC 2.0 `tools/call`:

```bash
curl -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "lookup_title",
      "arguments": {"code": "MIDE-123"}
    }
  }'
```

You usually won't invoke tools directly — an MCP client does it for you.

---

## Part IV — Agentic Use

This is the practical guide to **using Organizer3 through Claude Desktop** (or another MCP client). Everything in this section is about how to work with the app conversationally — asking an agent to run pipelines, clean up data, investigate problems, and apply mutations on your behalf.

---

### Why work this way

The shell and web UI are excellent for routine operations. Agentic mode adds three things on top:

1. **Multi-step orchestration.** "Find duplicate covers, pick a canonical for each, trash the others, then audit timestamps across the whole volume" is a single prompt. The agent chains the individual tools.
2. **Judgment-driven tasks.** Deciding whether `Yua Mikarni` is a typo for `Yua Mikami`, or which of two covers is canonical, is the agent's strong suit. Shell commands can't make that call; the agent can.
3. **Natural-language reporting.** The agent synthesizes raw tool output into human-readable summaries. You don't have to read 300 rows of SQL — you get "8 actresses have name-order variants; here are the three most likely duplicates."

When you should stay in the shell instead:

- **Bulk routine work with no decisions** (e.g. `sync all` on ten volumes in a row) — the shell is faster.
- **Browsing** — the web UI is purpose-built for this.
- **When the operation is well-defined and you know exactly what you want** — `organize` at the shell is a one-liner.

---

### First-time setup

#### 1. Install Claude Desktop

Download from `claude.ai/download`. Install and sign in.

#### 2. Install the MCP bridge

Organizer3's MCP server is HTTP; Claude Desktop speaks MCP over stdio. The `mcp-remote` bridge handles translation. It runs from `npx`, so all you need is Node.js (any recent version).

```bash
which npx   # should show /usr/local/bin/npx or similar
```

If missing, install Node.js from `nodejs.org` or via Homebrew (`brew install node`).

#### 3. Register the server with Claude Desktop

Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS). Create the file if it doesn't exist:

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

Set the `command` path to match your `which npx` output. Restart Claude Desktop after editing.

#### 4. Start the Organizer3 app

```
./gradlew run
```

With the app running, Claude Desktop auto-connects to the MCP server. You'll see "organizer3" in Claude's tools dropdown (click the tools icon near the message input). Tools come and go based on your MCP gate config.

#### 5. Smoke test

In Claude Desktop, open a new conversation and try:

> "What Organizer3 tools do you have access to? List the top-level categories."

If Claude lists tools like `list_volumes`, `lookup_actress`, `organize_volume`, setup is complete. If not, check:
- Is the Organizer3 app running? (`curl -s http://localhost:8080/mcp -X POST -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'`)
- Is the `command` path in the JSON correct?
- Did you restart Claude Desktop after editing the config?

---

### Your first session

Here's what a typical first session looks like. Say your goal is "check the health of the library, no changes yet."

**You:**
> "Give me a quick health check on the Organizer3 library. I want to see what's there, any data-quality issues, and anything unusual. Don't change anything."

**Agent (paraphrased):**
- Calls `get_stats` → 53,561 titles, 6,832 actresses, 57,208 title locations across 17 volumes.
- Calls `find_alias_conflicts`, `find_name_order_variants`, `find_orphan_titles`, `find_lone_titles`, `find_duplicate_base_codes` → picks out 8 name-order pairs, 3 orphan titles, 11 duplicate base codes, etc.
- Synthesizes: "Library looks healthy overall. Three things worth flagging: (1) 8 likely-duplicate actress pairs from name-order swaps, (2) 11 titles with duplicate base codes, (3) one title with no filing actress."

**You:**
> "Show me the three most likely duplicate actress pairs. Don't merge them yet."

The agent ranks candidates, shows you names + title counts for each side, and waits for your call on which to merge.

**You:**
> "Merge `Aino Nami` into `Nami Aino`. Dry-run first."

Agent calls `merge_actresses` with `dryRun: true`, shows the plan (titles reassigned, aliases migrated, flag merges), and waits. You say "OK, execute" — agent re-calls with `dryRun: false`.

The shape: **describe → agent investigates → you direct → agent plans → you approve → agent executes.** Don't try to orchestrate every tool call yourself; just describe the outcome you want and let the agent pick the tools.

---

### Core patterns

#### Pattern: "Show me, then I'll decide"

Ask the agent to enumerate candidates first. Don't ask it to mutate anything. Once it's listed what's there, you can point at specific ones.

> "Which actresses have multiple title folders under different names on volume `tz`?"
> "Show me all titles where the filing actress has fewer than 3 titles but the folder is already under `/stars/library/`."

#### Pattern: "Dry-run, then approve"

Even when you know what you want, start with a dry-run. This is free (no side effects) and shows you the exact plan.

> "Move the misfiled cover on `IPZ-463` to the title's base — dry-run only."

After the agent shows the plan:

> "Good, execute that."

#### Pattern: "Chain by volume"

Most file operations require a mounted volume. Let the agent manage the mount lifecycle:

> "For each conventional volume, mount it, scan for multi-cover titles, record the findings, then unmount. Compile the results into a cross-volume summary."

The agent will loop through volumes, managing mounts — you don't have to mount/unmount manually.

#### Pattern: "Background and check back"

For long-running work (probe videos across a whole volume is 15-30 min), start a background job and poll:

> "Start a probe-videos background job on volume `a`. Give me the job id."
> _...later..._
> "Check the probe job status — is it done?"

Tools for this: `start_probe_job`, `probe_job_status`, `cancel_probe_job`.

#### Pattern: "Narrow before wide"

When running the organize pipeline, start with a single title to validate the pipeline behaves as expected:

> "Dry-run `organize_volume` on `a` with `limit=1`. Show me the plan for that one title."

If the plan looks right, scale up:

> "Looks good. Run organize on the first 20 titles in `a`'s queue, armed."

---

### Extended example sessions

These are realistic multi-turn flows showing how a full task runs. Prompts in quotes; what the agent does is briefly summarized in between.

#### Session A — Weekly cleanup after sync

After your weekly `sync all` across several volumes, run through post-sync maintenance agentically.

> **You:** "I just finished syncing the library. Walk through post-sync maintenance with me. Start by identifying any data-quality issues that came up during sync."

_Agent calls `get_stats`, then the suite of diagnostic tools. Reports: 4 new name-order candidates; 2 orphan titles; 1 actress with misnamed folders on volume `bg`._

> **You:** "Handle the misnamed folders first. Show me what needs to be done."

_Agent calls `list_actresses_with_misnamed_folders`, mounts `bg`, calls `find_misnamed_folders_for_actress` for the flagged actress. Reports: "3 title folders use the old spelling `Chinatsu`; canonical is `Chinatsu Hashimoto`. I can rename each folder."_

> **You:** "Do those renames, armed."

_Agent renames each folder via `rename` primitive, reports done._

> **You:** "Now the 4 name-order candidates. Who are they, and how confident is each match?"

_Agent lists them with title counts on each side. 2 are obvious (high overlap in label distribution, same debut year), 2 are ambiguous._

> **You:** "Merge the 2 obvious ones. Skip the ambiguous ones for now — I'll look at those manually later."

_Agent dry-runs each `merge_actresses`, shows plans, then executes._

> **You:** "Finally, run `audit_volume_timestamps` on each volume I synced today. Don't dry-run — just fix them."

_Agent mounts each volume in turn, runs `audit_volume_timestamps` with `dryRun: false`, unmounts, reports totals per volume._

#### Session B — New content arrival

You manually moved 45 new titles from the global pool to volume `n`'s queue. Now you want to run them through the pipeline.

> **You:** "I just dropped 45 new titles into volume `n`'s queue. Run the full organize pipeline on them. Do a dry-run first — I want to see if anything would fail or route to attention before we commit."

_Agent mounts `n`, calls `organize_volume` with `dryRun: true` and all 4 phases. Reports: "45 titles processed. 43 would sort cleanly to tier folders, 1 would route to attention (letter mismatch — actress is `Natsume Iroha` → `N` ✓, but the folder is misnamed and doesn't contain her code), 1 has a multi-cover situation and normalize would skip."_

> **You:** "Before executing, let's fix the one with multi-cover. Show me the covers."

_Agent calls `find_multi_cover_titles` for the specific title, lists the candidates._

> **You:** "The `xyz456pl.jpg` is the canonical one. Trash the other."

_Agent dry-runs `trash_duplicate_cover`, shows plan, you approve, executes._

> **You:** "Great. Now run organize for real on the full 45 titles."

_Agent calls `organize_volume` with `dryRun: false`. Reports: "44 sorted to tier folders, 1 routed to /attention/ with REASON.txt. 38 actresses affected; 6 promoted to a higher tier."_

> **You:** "Show me the one that went to attention. What does the REASON.txt say?"

_Agent calls `read_text_file` on `/attention/<folder>/REASON.txt`, explains._

#### Session C — Investigating data quality

You noticed something weird in the web UI — a specific actress seems to have two entries. Track down what's going on.

> **You:** "In the UI I see both `Rika Minami` and `Minami Rika` in the actress list. Are these the same person?"

_Agent calls `lookup_actress` for each. Reports: two separate records, each with their own title counts. Runs `find_name_order_variants` focused on these names. Shows label overlap (both have titles under labels MIDD, IDBD) and debut date proximity._

> **You:** "Yeah, looks like the same person. Which one has more titles?"

_Agent: "Rika Minami has 14 titles, Minami Rika has 2."_

> **You:** "OK, merge `Minami Rika` into `Rika Minami`. Dry-run first."

_Agent dry-runs merge, shows plan: 2 titles reassigned, 1 alias added. You approve. Agent executes._

> **You:** "Did the merge create any letter-volume mismatches on disk?"

_Agent calls `list_actresses_with_misnamed_folders`. Reports: "Yes — on volume `m`, there's a folder `Minami Rika` that contains the 2 migrated titles. Since her canonical name is now `Rika Minami`, those should be under `/stars/{tier}/Rika Minami/` on volume `r`."_

> **You:** "That's a cross-volume move, so it needs manual intervention. Route it to attention on `m` with a clear explanation."

_Agent mounts `m`, moves the Minami Rika folder to `/attention/Minami Rika/`, writes a REASON.txt explaining the post-merge letter change and pointing to the correct target volume. You'll handle the cross-volume move in the NAS UI at your leisure._

#### Session D — The mass cleanup

You have the folder-anomaly audit report from `find_multi_cover_titles` — 329 titles across the library need cleanup. You want to tackle them agentically instead of one-by-one.

> **You:** "I have 329 multi-cover titles to resolve across the library. For each one, pick the canonical cover using this rule: if there's a file named `{code}pl.jpg`, keep that; otherwise keep the lexicographically-first one. Trash the rest. Process one volume at a time; report progress per volume."

_Agent mounts volume `a` first, iterates through its multi-cover hits. For each, calls `list_directory` to see current state, applies the rule to pick `keep`, calls `trash_duplicate_cover` with `dryRun: false`. When done, reports: "volume `a`: 9 titles processed, 9 resolved, 0 failures." Unmounts, moves to next volume._

> **You** _(mid-run):_ "Actually pause. Before we continue to `bg`, show me one of the decisions you already made on `a` — I want to sanity-check the canonical-picker logic."

_Agent shows one case: input was `[ipz111pl.jpg, ipz111pl - Copy.jpg]`, kept `ipz111pl.jpg` per rule 1, trashed the "Copy" file._

> **You:** "Looks right. Continue."

_Agent resumes. At the end, produces a consolidated summary: "329 titles processed across 14 volumes. 317 resolved cleanly. 12 failed (listed below) — 8 because the keep-rule produced no match (no `{code}pl.jpg` and multiple non-suffixed candidates — ambiguous), 4 because the cover filenames contained non-ASCII characters that broke the rule."_

---

### Prompt patterns that work well

A few concrete phrasings that consistently produce good agent behavior:

- **"Dry-run first"** — at the end of any mutation request. Forces the plan-then-approve flow.
- **"Show me before executing"** — same effect, slightly different phrasing. Both work.
- **"Process one [unit] at a time, report after each"** — gives you a chance to catch issues early.
- **"Don't commit until I confirm"** — sets an explicit pause point.
- **"Why did you make that choice?"** — ask after a judgment call (which cover to keep, which merge direction). Good for building trust in the agent's picks.
- **"What would happen if..."** — hypothetical mode; agent thinks through the tool call without making it.
- **"Redo that, but..."** — iterate on a plan you didn't like before approving execution.

---

### Gotchas and anti-patterns

**Don't rush the agent past dry-run.** Saying "just do it" in the first prompt works for small ops but can skip the check that a bulk operation would have caught. For anything >3-4 items, dry-run first.

**Don't over-specify the tool to use.** Asking "call `organize_volume` with `phases=[sort]`" is more brittle than "sort the titles in `a`'s queue." The agent knows the tool catalog; let it pick.

**Don't forget mounts.** Some tools don't care (DB-only work, SQL queries). File-op tools need a volume mounted. The agent will usually mount if asked to operate on a specific volume, but if you're chaining complex work, tell it which volume upfront.

**Don't assume the agent remembers across conversations.** Each Claude Desktop conversation is fresh. If you did a merge in session A and later start session B, the agent won't remember — it'll read the current DB state, which already reflects your earlier merge.

**Don't commit to a long autonomous run without checkpoints.** "Clean up everything on the library, go" is too vague. Break into smaller prompts: "clean up duplicate covers on `a`", then check back, then next volume. The agent is good but long autonomous loops on real data still deserve human check-ins.

**Don't expect the agent to know your house rules** _(until you tell it)_. "Always prefer `{code}pl.jpg` for canonical covers" is a rule you'd teach the agent in-session. It won't guess. Once stated, the rule applies for the rest of that conversation.

---

### Safety recap

Three layers of protection for agentic mode:

1. **Config gates** (`organizer-config.yaml`): if `allowFileOps: false`, the agent literally can't invoke file-moving tools. They're not in its catalog.
2. **Per-tool dry-run defaults**: every mutation tool starts in dry-run. The agent must pass `dryRun: false` explicitly.
3. **Trash, not delete**: nothing is ever permanently deleted by the app. Trashed items go to each volume's `_trash/` folder where you can review them at leisure via the NAS UI.

If any one of these three fails, the other two cover you.
