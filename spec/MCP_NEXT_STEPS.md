# MCP — Where We Left Off (2026-04-16 end of day)

Working notes for resuming the MCP workstream. Supersedes no other spec; read
`PROPOSAL_MCP_SERVER.md` and `PROPOSAL_MCP_PHASE2.md` for the full design context.

## Current state

**Shipped on `main` through `79822ac`:**

| Group | Count | Tools |
|---|---|---|
| Phase 1 lookup/diagnostic | 15 | list_volumes, get_stats, lookup_actress, lookup_title, list_titles_for_actress, find_similar_actresses, find_name_order_variants, find_suspect_credits, find_alias_conflicts, find_lone_titles, find_orphan_titles, find_duplicate_base_codes, find_label_mismatches, find_stale_locations, list_actresses_with_misnamed_folders, find_misnamed_folders_for_actress |
| Phase 1 SQL + FS | 5 | sql_query, sql_tables, sql_schema, list_directory, read_text_file |
| Phase 2 mutation (merge) | 1 | merge_actresses (dry-run/plan pattern — gated on `allowMutations`) |
| Phase 1b video diagnostics | 3 | list_multi_video_titles, analyze_title_videos, find_duplicate_candidates |
| Phase 1b folder anomalies | 3 | find_multi_cover_titles, find_misfiled_covers, scan_title_folder_anomalies |
| Session — mount | 3 | mount_volume, unmount_volume, mount_status (network tools gated on `allowNetworkOps`) |
| Probe backfill — inline | 1 | probe_videos_batch (cursor-paginated) |
| Probe backfill — background | 3 | start_probe_job, probe_job_status, cancel_probe_job |

**Supporting infrastructure shipped:**
- Schema v18: video metadata columns (`duration_sec`, `width`, `height`, `video_codec`, `audio_codec`, `container`)
- Shell command: `probe videos [volumeId]` — resumable via id-cursor
- `VideoProbe` 60s socket timeout (`timeout` + `rw_timeout` options) — prevents hung backfills
- `ProbeJobRunner` service with single-threaded executor, graceful shutdown
- 1042 tests passing

**Claude Desktop integration:** Working. Config at `~/Library/Application Support/Claude/claude_desktop_config.json` uses `mcp-remote` stdio bridge to `http://localhost:8080/mcp`. Auto-connects when the app is running.

## Local dev state

- Working tree has **uncommitted** local dev tweaks in `src/main/resources/organizer-config.yaml`:
  - `mcp.allowMutations: true`
  - `mcp.allowNetworkOps: true`
  - These stay local — not shipped to main. Remove or flip to `false` to reset to default posture.

- Current branch: `mcp-phase-1b` (matches `origin/mcp-phase-1b` which matches `origin/main`).

## Three likely next moves

Ranked by expected payoff.

### 1. Autonomous full-library folder-anomaly audit

The fast win. Rotate all 19 volumes through the existing folder-scan tools and produce a consolidated cleanup manifest.

- Tools in play: `mount_volume` → `find_multi_cover_titles` + `find_misfiled_covers` (+ `scan_title_folder_anomalies` for targeted drill-down) → `unmount_volume` → next.
- Estimated wall-clock: 30-60 min.
- Output: list of titles needing physical cleanup (per-volume), ranked by anomaly severity.
- Builds on pattern proven on `a`, `qnap`, `tz`, `collections` today.
- Zero-risk (read-only FS + DB).

Proven partial findings so far:
- `a`: 9 multi-cover + 1 sync bug (ghost title `covers`)
- `qnap`: ~120 multi-cover estimated
- `tz`: ~11 multi-cover across 3k samples
- `collections`: 1 multi-cover, 0 misfiled — clean

### 2. Full-library probe videos backfill

Heavier but unlocks the duration-based duplicate-detection signal across the entire library.

- Tools in play: `mount_volume` → `start_probe_job { maxVideos: 0 }` → walk away → `probe_job_status` → `unmount_volume` → next volume.
- Estimated wall-clock: 15-30 hours total, but resumable per-volume.
- Expect: 2-5% failure rate on main volumes (stale DB rows — probe failures are a free "orphaned video row" detector, complementary to `find_stale_locations`).
- After completion: `find_duplicate_candidates` produces library-wide hits with real duration/codec data.
- Split the probe over multiple sessions — can do one volume per day.

### 3. Phase 2 mutation tools

From `PROPOSAL_MCP_PHASE2.md` §5. Each follows the `merge_actresses` dry-run/plan template we already validated end-to-end.

Priority sub-order within Phase 2:
- `add_alias` / `remove_alias` / `reassign_alias` — fixes the alias-conflict cases `find_alias_conflicts` already surfaces
- `set_actress_flags { actressId, favorite?, bookmark?, rejected? }` — small, useful
- `merge_titles { into, from, dryRun }` — fixes duplicate `base_code` clusters (`BDD-002` + `BDD-02` etc.)
- `tag_title` / `untag_title`
- `rename_video { video_id, new_filename, dryRun }` — intra-folder atomic rename
- `mark_duplicate_video { video_id, note? }` — flag without deleting (feeds Phase 3)
- `attribute_title { code, actressIds[] }` — fix actressless titles

Each: ~100-150 LoC + tests.

## Known data quality issues surfaced today (action items independent of MCP work)

These are real DB-state problems that came up in testing:

- **Ghost "covers" title** — `titles.id = 17056`, `code = 'covers'`, points at `/stars/goddess/Ayumi Shinoda/covers` on volume `a` (134 images in that folder). Sync-parser bug; needs a one-off cleanup.
- **10 actressless amateur-code titles** (e.g. `(011020-001-CARIB)`) — parser couldn't attribute. Manual attribution via a future `attribute_title` tool or direct SQL.
- **10 surname-swap dupe pairs** from `find_name_order_variants` — Aino Nami/Nami Aino already merged today (id 4506 → 51). 9 remaining candidates for `merge_actresses`.
- **`unsorted` volume is 1 week stale** — last synced 2026-04-09; 95 of its 445 indexed video rows point at files that no longer exist. Run `sync` on it to refresh.

## Open design questions for later

- **`probe_video { volumeId, path }`** — on-demand single-file probe as MCP tool. Punted because loading VideoProbe in the MCP tool module would pull FFmpeg natives into any test classpath. Workaround: the `probe videos` shell command + backfill tools cover the common case. Revisit if per-file diagnosis becomes frequent.
- **`size_bytes` column on videos** — deliberately deferred from schema v18 because `VolumeFileSystem` has no `size()` method. Needs adding that method across `SmbFileSystem` + `LocalFileSystem` + `DryRunFileSystem`. Not blocking any current tool.
- **Trash proposal (`PROPOSAL_TRASH.md`)** — still the gating dependency for Phase 3 file ops. Nothing started.

## One-line recap

Phase 1b + mount + background probe shipped end-to-end. Claude Desktop wired up. Next session: autonomous folder-anomaly audit is the fast win; probe backfill is the deep-signal unlock.
