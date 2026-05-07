# Proposal: Sync Reconciliation — Grace-Period Orphans, Multi-Volume Coherent Sync, Reconcile-Only Pass

**Status:** Draft 2026-05-07 — for discussion, no implementation yet.
**Origin:** Today's sync model assumes "sync the whole world" for a coherent state. In reality, titles flow across volumes (unsorted pile → global pool → volume pools → actress folders), often crossing volume boundaries. A single-volume sync run after a cross-volume movement either (a) prunes the title as orphaned because the destination volume hasn't been synced yet, or (b) leaves a stale `title_locations` row on the source volume because only the destination was synced. The cross-volume move primitive (`PROPOSAL_CROSS_VOLUME_MOVES.md`) is the long-term answer for app-driven moves, but it cannot be the *only* way to manage this — manual moves over SMB will keep happening, and sync needs to tolerate them.

This proposal adds three non-overlapping capabilities to the sync subsystem so manual cross-volume movement is non-destructive between sync passes:

1. **Grace-period orphans (90 days).** A `title_locations` row that disappears in a sync is not deleted immediately — it is marked stale and only swept after 90 days.
2. **Multi-volume coherent sync.** Admin-initiated `sync all` (or an explicit volume list) defers orphan evaluation until *every* requested volume has been scanned, so a title that moved A → B is observed at B before it's judged absent at A.
3. **Reconcile-only pass.** A no-filesystem command that examines `title_locations` globally and reports inconsistencies (duplicate live locations after a move, zero-location titles still in grace, stale rows past grace).

---

## 1. Design principles

1. **Sync is non-destructive within the grace window.** A row that disappears does not delete its title. It earns deletion only by remaining unseen for 90 days across *every* sync that scoped it.
2. **Orphan judgement is a global property, not a per-volume one.** "This title has no live locations anywhere" is the only condition that retires it. Per-volume sync may *contribute* to that judgement but never makes it alone.
3. **Soft-state, not parallel state.** No tombstone table, no shadow rows. The existing `title_locations` row gains a `stale_since` column; the sweep job runs against the same table everyone reads.
4. **Backwards-compatible with single-volume sync.** Admins can still `sync <vol>` and get useful behavior. The grace period is what makes this safe.
5. **Atomic-tasks invariant respected.** Multi-volume sync runs as a single `TaskRunner` task, holds the global lock for the duration. Reconcile-only is read-mostly but also a task (it produces a report).

---

## 2. Capability 1 — Grace-period orphans

### 2.1 Schema change

Add to `title_locations`:

```sql
ALTER TABLE title_locations ADD COLUMN stale_since TEXT;  -- ISO-8601 timestamp; NULL = live
CREATE INDEX idx_title_locations_stale_since ON title_locations(stale_since);
```

`stale_since IS NULL` means the row was observed during the most recent sync of its scope. Any non-null value is the timestamp at which it was first marked absent.

### 2.2 Sync behavior change

Today (`AbstractSyncOperation`):

- Full sync: `videoRepo.deleteByVolume(...)` + `titleLocationRepo.deleteByVolume(...)` clears everything for the volume, then re-scan re-inserts what's still present.
- Partition sync: same with `deleteByVolumeAndPartition`.

After this change:

- **Mark, don't clear.** Replace `deleteByVolume` / `deleteByVolumeAndPartition` (in the sync path) with `markStaleByVolume` / `markStaleByVolumeAndPartition`, which sets `stale_since = NOW()` for rows that don't already have a value. (Idempotent — a row already stale stays at its original `stale_since`.)
- **Clear stale on re-observation.** When a scanned title folder maps to an existing `title_locations` row (matched by `(title_id, volume_id, path)` or by the soft-matcher recovering a renamed folder), the upsert clears `stale_since` back to `NULL` and refreshes `last_seen_at`. Rows that *aren't* re-observed keep their `stale_since` — they are this sync's casualties.

Videos still cascade-clear hard. (No grace period for videos; if a title's location is back, the video records are rewritten from scratch on rescan as today.)

### 2.3 Orphan prune sweeps two pools

`pruneOrphanedTitlesAndCovers` changes from "titles with zero `title_locations` rows" to:

```
A title is orphaned when:
  - It has zero live locations (stale_since IS NULL count = 0), AND
  - All of its stale locations are past grace (stale_since < NOW - 90 days)
```

Two pools, each evaluated:

- **Stale-row sweep.** Separately, drop `title_locations` rows whose `stale_since < NOW - 90 days` regardless of whether the title is otherwise live. (Cleans up the per-volume stragglers from a successful cross-volume move that the user never re-synced the source volume after.)
- **Title prune.** Same guard rails as today (catastrophic-delete threshold, enriched-orphan flagging-guard) — but now the count of "would-be-deleted" is much smaller in practice because cross-volume moves no longer manufacture false orphans.

### 2.4 Visibility

- `title_locations` rows with `stale_since != NULL` should be **excluded from default browse / actress page / dashboard queries** — they're not real locations anymore. The repository's existing accessors (`findByTitle`, `findByVolume`, etc.) gain an `includeStale` flag (default `false`).
- A new dashboard panel / Library Health row — "Stale locations awaiting confirmation: N (oldest: D days)" — surfaces the pool so the admin can see what's pending.
- Logs: every sync prints a `stale-marked: X, stale-cleared: Y, swept: Z` line.

### 2.5 Why 90 days

- Long enough that a user can ignore single-volume syncs for ~3 months without losing data to a cross-volume move that hasn't been re-confirmed on the source side.
- Short enough that genuine deletions don't linger for years.
- Configurable via `organizer-config.yaml` (`sync.staleGraceDays: 90`) but not exposed in the UI — the admin sets it once.

---

## 3. Capability 2 — Multi-volume coherent sync

### 3.1 What it does

A single task that:
1. Marks-stale and re-scans each requested volume in turn (using existing `FullSyncOperation` machinery, but with orphan prune *suppressed*).
2. After all volumes are scanned, runs **one** orphan evaluation pass over the whole library.
3. Runs `finalizeSync` once at the end (volume `last_synced_at` updated per-volume during their scan; the recompute steps run once at the end across all touched titles/actresses).

### 3.2 Why this matters

Today's `sync all` runs each volume's full pipeline including its own prune. If volume A is scanned first and a title moved A → B, the title appears orphaned at the end of A's scan. Pre-grace-period that means deletion; post-grace-period it means a stale row. **Either way it's a write that didn't need to happen** — the sync of B 30 seconds later would have shown the title is fine. Coherent sync collapses both syncs into a single judgement.

### 3.3 Surface

- Shell / web command: `sync coherent [<volA> <volB> ...]` (no args = all mounted volumes).
- Web UI button: "Coherent sync (all volumes)" on Tools / Sync page, with a confirmation modal noting it can take hours and holds the task lock.
- Designed for overnight admin-triggered runs. Not a default cron.

### 3.4 Implementation sketch

```java
public class CoherentMultiVolumeSyncTask {
    void execute(List<VolumeConfig> volumes, ...) {
        SyncStats agg = new SyncStats();
        for (VolumeConfig v : volumes) {
            // Same scan flow as FullSyncOperation, but skip pruneOrphanedTitlesAndCovers.
            new FullSyncOperation(..., suppressPrune = true).execute(v, ...);
            agg.merge(volumeStats);
        }
        // Single global prune now that every volume has been observed.
        pruneOrphanedTitlesAndCovers(io);
        // Single global stale-row sweep (rows past grace).
        sweepStaleLocations(graceDays);
        // Single recompute / index reload at the end.
        finalizeAggregate(agg);
    }
}
```

`FullSyncOperation` grows a `suppressPrune` constructor flag; `AbstractSyncOperation` exposes `pruneOrphanedTitlesAndCovers` as already-package-private.

### 3.5 Failure handling

If a volume mid-run fails to mount or read, the task **continues with the remaining volumes** but **skips the global prune** at the end (we don't have a complete picture). It logs loudly and reports the partial outcome. The admin re-runs after fixing the mount.

---

## 4. Capability 3 — Reconcile-only pass

### 4.1 What it does

A read-only (one writeable side-effect: stale-sweep) command that doesn't touch the filesystem. It examines current DB state and reports:

- **Duplicate live locations.** Same `title_id` with `stale_since IS NULL` rows on >1 volume. Likely a successful cross-volume move where the source volume hasn't been synced since, so the source row is still live. Recommend syncing the source volume.
- **Pending-grace titles.** Titles with zero live locations but at least one stale row inside the grace window. Lists them with days-remaining-until-prune. The admin can use this to spot "this title should not be orphaned — let me re-sync the volume it actually lives on."
- **Past-grace stragglers.** Stale rows past 90 days that the sweep would drop on next run. (Run the sweep optionally as part of reconcile, or just report.)
- **Title-vs-location actress mismatch.** Title's `actressId` (filing) and the actress folder its location currently sits under no longer match — happens when a title is manually moved between actress folders without re-sync. Already partly covered by `find_misnamed_folders_for_actress`; reconcile reports it from the location side.

### 4.2 Surface

- MCP tool: `reconcile_locations(dryRun: true)` returns a structured report.
- Shell command: `reconcile`.
- Web: a "Run reconcile" button on the dashboard, output rendered as a table.
- No filesystem I/O — runs in seconds even on a full library.

### 4.3 Output shape

```
Reconcile report (2026-05-07T03:14:00Z)
  Duplicate live locations:    7   (likely unsynced source volumes after cross-volume moves)
  Pending-grace titles:       12   (oldest: 14 days, newest: 1 day)
  Past-grace stragglers:       3   (would be swept on next sync)
  Actress-folder mismatches:  21   (location path's parent != title.filing actress)

Run with --verbose to list each.
Run with --sweep to drop past-grace stragglers immediately.
```

---

## 5. Schema migration

`SchemaUpgrader` gets one new migration (next free version `applyVN`):

```sql
ALTER TABLE title_locations ADD COLUMN stale_since TEXT;
CREATE INDEX IF NOT EXISTS idx_title_locations_stale_since ON title_locations(stale_since);
-- No backfill needed: NULL is the correct initial value (every existing row is "live").
```

Idempotent (per `feedback_drop_db` convention).

---

## 6. Repository changes

`TitleLocationRepository`:

- New: `markStaleByVolume(volumeId, nowIso)`, `markStaleByVolumeAndPartition(volumeId, partitionId, nowIso)`.
  - Replaces the `deleteByVolume` / `deleteByVolumeAndPartition` calls in the sync path. The originals stay (used by tests and possibly future paths) but are deprecated for the sync surface.
- New: `clearStaleOnUpsert(...)` — when saving a re-observed location, set `stale_since = NULL`.
- New: `findStaleOlderThan(graceDays)` — returns rows the sweep should drop.
- New: `findPendingGrace()` — for the reconcile report.
- New: `findDuplicateLiveLocations()` — for the reconcile report.
- Default reads add a `WHERE stale_since IS NULL` predicate.

`TitleRepository`:

- `findOrphanedTitles()` predicate updated to "no live location AND no stale-within-grace location."
- New: `findTitlesPendingGrace()` — for the reconcile report (titles with zero live but ≥1 stale-within-grace).

---

## 7. Tests

Per `feedback_testing_consistency`:

1. **Grace-period sweep regression test.** A title with one location, sync that volume, mark stale; advance clock <90d → still present; advance >90d → swept.
2. **Cross-volume move tolerated by sync.** Two volumes, title on A; manually move folder A → B in test fs; sync only A → row stale, title still live (because it's not yet "all stale past grace"); sync B → row clears stale; title intact, no enrichment loss.
3. **Single-volume sync no longer false-orphans cross-volume moves.** Same as above without the sync of B; assert the title is *not* in the orphaned set within grace.
4. **Coherent multi-volume sync collapses prune.** Two volumes with a moved title, single coherent sync run; no stale rows generated, no enriched-orphan flag.
5. **Reconcile detects duplicate live locations.** Insert a synthetic duplicate; reconcile reports it; sync of the stale side resolves it.
6. **Catastrophic-delete guard still trips.** With grace in effect, a buggy mass-stale-mark followed by 90-day clock advance should still hit the threshold guards before deleting half the library.
7. **Browse queries hide stale rows.** `findByVolume`, `findByTitle` default to live; `includeStale=true` returns both.

---

## 8. Resolved design decisions

1. **Grace clock is per-row.** Each `title_locations` row carries its own `stale_since`. A title is orphaned when *all* of its rows are either gone or past grace. Per-title was considered but doesn't actually save data in the realistic scenarios — it just leaves dead rows on never-re-synced volumes indefinitely.
2. **Reconcile auto-runs at the end of `sync coherent`.** Coherent sync just observed every volume — the report it produces is the most accurate it'll ever be. Output is persisted (dashboard widget + report row), not buried inline in stdout, so an overnight run's findings are visible the next morning. The standalone `reconcile` command stays for ad-hoc use.
3. **Single global grace value.** `sync.staleGraceDays: 90` lives in `organizer-config.yaml`. No per-volume overrides; no UI surface. Every realistic scenario (unmounted volumes, partition-only syncs) is already handled by per-row grace mechanics — per-volume config would add surface for no concrete benefit. Add later if a need actually appears.
4. **Catastrophic-delete guard applies to the stale-row sweep.** Same threshold as the title-prune guard: refuse to drop more than `max(50, 10%)` of live `title_locations` rows in a single sweep. Cost of being wrong is an admin sees a refusal and investigates; cost of not having it is a single bad migration silently truncating location data overnight.

---

## 9. Implementation plan

Three sequential PRs, no flag-day. Each phase ships value alone. **No volumes need to be mounted for any phase** — implementation and tests run entirely offline against in-memory SQLite + fake `VolumeFileSystem` implementations. Post-merge smoke-test against real volumes is a 10-minute manual check, not part of dev work.

### Phase 1 — Grace-period orphans (~2–3 dev days, 1 PR)

The heavy phase — schema change ripples across every read site that touches `title_locations`.

1. **Schema migration** — `SchemaUpgrader.applyVN`:
   - `ALTER TABLE title_locations ADD COLUMN stale_since TEXT`
   - `CREATE INDEX idx_title_locations_stale_since`
   - Idempotent; no backfill (NULL = live, correct for existing rows).

2. **Config wiring** — `AppConfig` + YAML model record reads `sync.staleGraceDays` (default 90, value already present in `organizer-config.yaml`).

3. **Repository changes** — `TitleLocationRepository` + `JdbiTitleLocationRepository`:
   - New: `markStaleByVolume(volumeId, nowIso)`, `markStaleByVolumeAndPartition(...)`.
   - Modify `save(...)` upsert to clear `stale_since` on re-observation.
   - New: `findStaleOlderThan(graceDays)`, `sweepStaleOlderThan(graceDays)`.
   - Add `includeStale` flag (default false) to `findByTitle`, `findByVolume`, etc.
   - **Centralize the `WHERE stale_since IS NULL` predicate in the JDBI base SQL** rather than scattering it across call sites. Single point of truth, single point of audit.

4. **Repository changes** — `TitleRepository`:
   - Update `findOrphanedTitles` predicate: zero live AND no stale-within-grace.
   - Update `countOrphansWithEnrichment` similarly.

5. **Sync path** — `AbstractSyncOperation`, `FullSyncOperation`, `PartitionSyncOperation`:
   - Swap `deleteByVolume*` calls to `markStaleByVolume*` in the sync path.
   - `pruneOrphanedTitlesAndCovers` calls `sweepStaleOlderThan` after the title prune.
   - Catastrophic-delete guard extended to the stale-sweep with the same `max(50, 10%)` threshold.
   - Stats: `stale-marked / stale-cleared / swept` counters in `SyncStats`.

6. **Surfacing**
   - Logs viewer: every sync logs the new counters at INFO.
   - Library Health: new row "Stale locations awaiting confirmation: N (oldest: D days)".

7. **Tests** (cases 1, 2, 3, 6, 7 from §7) — real in-memory SQLite, fake `VolumeFileSystem`.

**Pre-flight before coding (recommended for Sonnet):** grep for all `title_locations` references and produce a checklist of read sites that need `includeStale` consideration. Audit-first, code-second — this is the only place where a missed call site shows up as a quiet UI bug later.

**Sonnet feasibility:** within range. Proposal acts as the spec. The call-site audit checklist is the safety mechanism.

### Phase 2 — Coherent multi-volume sync (~½–1 dev day, 1 PR)

Smallest phase — wraps Phase 1 machinery. Depends on Phase 1 (single-volume sync must be non-destructive first).

1. **`FullSyncOperation` flag** — `suppressPrune` boolean constructor arg; when true, skip `pruneOrphanedTitlesAndCovers` and `sweepStaleOlderThan`.

2. **New task** `CoherentMultiVolumeSyncTask`:
   - Iterates volumes calling `FullSyncOperation` with `suppressPrune=true`.
   - Single global prune + sweep + recompute at the end.
   - On any volume failure: continue, but skip the global prune (incomplete picture); log loudly.
   - Holds the `TaskRunner` global lock for the duration.

3. **Surfaces**
   - Shell: `sync coherent [<vol> ...]`.
   - Web: button on Tools → Sync with confirmation modal noting overnight runtime.
   - MCP: `sync_coherent` tool.

4. **Tests** (case 4 from §7).

**Sonnet feasibility:** squarely mechanical. Straightforward.

### Phase 3 — Reconcile-only pass (~1–2 dev days, 1 PR)

Independent of Phase 2 but more useful once both are in.

1. **Repository finder methods** — `TitleLocationRepository`, `TitleRepository`:
   - `findDuplicateLiveLocations()` — same `title_id` with >1 live row across volumes.
   - `findPendingGrace()` — stale rows still inside the grace window.
   - `findActressFolderMismatches()` — location path's parent vs `title.actress_id`. **Reuse logic from existing `find_misnamed_folders_for_actress`; do not duplicate.**

2. **`ReconcileService`** — composes the four signals into a `ReconcileReport` record. Optional `--sweep` flag to drop past-grace stragglers immediately.

3. **Surfaces**
   - MCP tool: `reconcile_locations(dryRun, sweep)`.
   - Shell: `reconcile`.
   - Web: dashboard widget + "Run reconcile" button; persisted report row so overnight coherent-sync results are visible next morning.

4. **Coherent-sync integration** — `CoherentMultiVolumeSyncTask` calls `ReconcileService.run()` at the end and persists the report.

5. **Tests** (case 5 from §7).

**Sonnet feasibility:** within range. Watch for duplication with existing misnamed-folder logic.

### Total

**~4–6 dev days across 3 sequential PRs.** Phase 1 → Phase 2 → Phase 3. Don't pipeline; each phase's behavior is load-bearing for the next.

---

## 10. Memory pointers (post-implementation)

When shipped, add memory entries:

- `project_sync_grace_period.md` — grace days, sweep cadence, where stale rows surface.
- `project_sync_coherent.md` — when to use coherent sync, expected runtime, lock semantics.
- `project_sync_reconcile.md` — what reconcile detects, common false positives.
