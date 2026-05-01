# Proposal: Cross-Volume Moves

**Status:** Draft 2026-05-01 — for discussion, no implementation yet.
**Origin:** Pool→queue graduation is a common manual workflow today. Single-title cross-volume reassignment (misattributed-to-wrong-actress) is the same primitive at smaller scale. Both currently happen outside the app.

---

## 1. Problem statement

Three real workflows all need the same missing primitive — **move a title folder from one volume to another, with the DB kept consistent throughout**:

1. **Pool → queue graduation (bulk).** A `sort_pool` volume accumulates curated skeletons (code parsed, cover present, cast inferred). They need to be moved to a target letter-volume's `queue/` partition so the existing organize pipeline (Normalize → Restructure → Classify → Sort) can distribute them to actress folders. Done manually over SMB today. `FreshAuditService` already classifies which skeletons are "ready to graduate" — the graduate step itself is the gap.

2. **Title reassigned to a different actress on a different volume (single).** A title is misattributed; the correct actress lives on another volume. Currently a five-step manual sequence (copy → verify → sync dest → delete source → sync source → fix cast in UI), with a window where the title flickers as enriched-orphan if done in the wrong order.

3. **Future ad-hoc rebalancing.** Volume getting full; want to relocate a slice of titles to another volume without losing enrichment.

All three are blocked by the same missing piece: organize3's `fs.move` is intra-volume by invariant (`feedback_filesystem_scope`). Cross-volume = copy + verify + delete, with DB updates around it. No existing tool does this.

---

## 2. Design principles

1. **One primitive, multiple workflows.** Build `CrossVolumeFolderMove` once; layer `promote_to_queue` and `reassign_title` on top.
2. **Atomic from the user's perspective.** Mid-flight crash leaves either pre-state (source intact, no DB change) or post-state (destination registered, source gone). Never both. Never neither.
3. **Continuously located.** The title's `title_locations` row set is never empty during the operation. New row registered before old row dropped — no enriched-orphan flicker, no review queue noise.
4. **Verifiable copy.** Byte-count + file-count check before declaring the copy successful. SMB partial transfers are real.
5. **Respect the atomic-tasks invariant.** Runs as a `TaskRunner` task, holds the global lock for the duration. Slow but rock-solid; no concurrent sync can race.

---

## 3. The primitive: `CrossVolumeFolderMove`

A `Task` (not a synchronous MCP tool — copy times over SMB are minutes, not seconds; needs progress + cancel).

**Inputs:**
- `title_id`
- `source_location_id` (which `title_locations` row to move; titles can have multiple)
- `target_volume_id`
- `target_partition_id` (e.g. `"queue"`)
- `target_relative_path` (the folder name + any subpath under the partition)

**Steps:**

1. **Pre-flight checks.**
   - Source volume mounted; source folder exists.
   - Target volume mounted; target parent dir exists; target path does NOT exist (refuse on collision).
   - Title not currently in `enrichment_review_queue` with an open row (resolve first).
   - Source location's videos all present (no missing/.skip files mid-copy).

2. **Copy.** Recursive copy source → target. Stream progress events (per-file, with bytes copied). Cancellable.

3. **Verify.** For every file in source, target has same path-relative + same byte count + same file count overall. (Hash-check is too slow over SMB; size+count catches truncation, which is the realistic SMB failure mode.)

4. **Register destination.** `INSERT INTO title_locations` for the new (volume, partition, path). Now the title has two locations.

5. **Drop source location.** `DELETE FROM title_locations WHERE id = :source_location_id`.

6. **Delete source folder.** Last step — by design. If anything from 1–5 fails, source is still there and the operation is reversible by deleting the destination folder.

7. **History snapshot.** `enrichment_history` row with `reason='cross_volume_move'`, `detail` = `{from_volume, from_path, to_volume, to_path}`. Auditable, future-tense reversible.

**Failure semantics:**

| Stage fails | State |
|---|---|
| 1 (pre-flight) | No changes. Caller sees the specific reason. |
| 2 (copy) | Partial copy on destination — task cleans up by deleting partial target folder. Source intact. No DB change. |
| 3 (verify) | Same — clean up partial destination. Source intact. No DB change. |
| 4 (register dest) | Copy succeeded but DB insert failed (rare — would be SQLite-level). Rollback by deleting destination folder. |
| 5 (drop source loc) | Destination registered; title now has both locations. Next sync would prune the source location naturally. Recoverable but inelegant — log loudly. |
| 6 (delete source) | Both locations registered, both folders exist. Same recovery as 5. |
| 7 (history) | Move succeeded; history snapshot missing. Log; not blocking. |

The "register before drop" ordering means the title is *always* findable through `title_locations` — no orphan window.

---

## 4. Workflow: `promote_to_queue` (pool → queue, bulk)

A second task that calls `CrossVolumeFolderMove` once per eligible skeleton.

**Inputs:**
- `source_pool_volume_id`
- `target_volume_selector` — see §6 open question
- `eligibility_filter` — which `FreshAuditService` classifications count as graduate-ready (default: must have code + cover + at least one video)
- `dry_run` — preview without moving
- `limit` — cap per run (paginate large pools)

**Steps:**
1. Run `FreshAuditService` on the source pool. Filter to graduate-ready entries.
2. For each entry, resolve target volume per selector rule.
3. Compute target path = `<target_volume>/queue/<folder_name>`.
4. Call `CrossVolumeFolderMove`. Continue on per-title failure (log + count); don't abort the batch.
5. Emit summary event: moved count, skipped count (with reasons), failed count.

After the batch completes, the user runs `OrganizeAllTask` on the target volume(s) to distribute graduated titles to actress folders. Two-step intentionally — the user can review what landed in queue before the auto-distribution runs.

---

## 5. Workflow: `reassign_title` (single, possibly cross-volume)

For the misattributed-actress case.

**Inputs:**
- `title_id`
- `target_actress_slug` (canonical, case-sensitive — caller resolves ambiguity)
- `target_volume_id` (optional — defaults to source volume if omitted)

**Steps:**
1. Resolve target path: read target actress's filing tier, build `<target_volume>/stars/<tier>/<slug>/<folder_name>`.
2. If `target_volume == source_volume`: intra-volume move via existing `fs.move` + DB update in one transaction. Fast path. Skip the rest of this section.
3. If cross-volume: call `CrossVolumeFolderMove`.
4. Update `title_actresses`: remove the wrong actress; add the right one. (Sync's `linkAll` is `INSERT OR IGNORE` and can't do this.)
5. History snapshot with `reason='reassigned'`, including before/after cast.

**Refuse safely if:**
- Title has multiple locations (ambiguous which to move).
- Destination folder already exists.
- Target volume not mounted.
- Open row in `enrichment_review_queue` for this title.

Splits naturally into a synchronous MCP tool for the same-volume case (50 lines, like `recode_title`) and a `TaskRunner` task for the cross-volume case (reuses `CrossVolumeFolderMove`).

---

## 6. Open questions

**Q1. Target-volume selection rule for `promote_to_queue`.**

Three options:
- **Manual per-title** — UI lists graduate-ready skeletons; user picks target volume per row. Most control, slowest.
- **Letter-prefix routing** — code prefix maps to volume (e.g. `S* → qnap_s`). Configured in `organizer-config.yaml`. Fastest, encodes the rule the user already follows by hand. Probably the right v1 default.
- **Capacity-aware** — pick least-full eligible volume. Easy to mis-target (no awareness of label-locality conventions). Skip unless you actively want this.

Recommendation: letter-prefix routing as default with manual per-title override. Decide config schema for the routing map.

**Q2. What classifies a skeleton as "graduate-ready"?**

`FreshAuditService` already buckets these. Need to pin down which buckets are eligible:
- Has code? (must)
- Has cover? (must, or allow without?)
- Has cast inferred from filename or folder? (must, or allow unknown?)
- All videos present and probed? (must, or allow unprobed?)

Conservative default: code + cover + at least one video, no further requirements. Cast assignment can happen via the organize pipeline after graduation.

**Q3. Verification strictness — size+count, or also hash a sample?**

Size+count catches the realistic SMB failure mode (truncation). Hashing every file is prohibitively slow over SMB. Sampling (hash first + last 1MB of each video) is a middle ground. Probably overkill for v1; add if a real corruption case appears.

**Q4. Concurrency with sync.**

The atomic-tasks lock means `promote_to_queue` runs serially with sync — fine. But: the *target* volume may need a sync afterwards to register the new locations from organize/sort. Should `promote_to_queue` enqueue a follow-up sync task automatically, or leave that to the user? Leaning user-driven; they may want to inspect what landed before syncing.

**Q5. Rollback / undo.**

History snapshots exist (step 7). But "undo" of a cross-volume move means another cross-volume move in reverse — slow, requires source volume still mounted, and may collide with subsequent changes. Probably not worth a dedicated `undo_move` tool; the history record is for audit, not one-click undo.

**Q6. Multi-location titles.**

A title with two `title_locations` (e.g. duplicate on volumes A and B): which one moves? `reassign_title` refuses on multi-location (§5). `promote_to_queue` shouldn't encounter this (pool titles are single-location by definition). Worth being explicit so the refusal is clear.

**Q7. Trash sidecars and other folder-attached metadata.**

If the source folder has a `.dup` sidecar, trash sidecar, or any other file the trash subsystem / dup-triage tracks: the recursive copy carries them along automatically (good), but the *registration* in those subsystems' state may have been keyed to the old path. Need to audit:
- Trash sidecar contract — is the path embedded in the sidecar or only in the parent folder name? (Guessing the latter, in which case we're fine.)
- Dup decisions (`duplicate_decisions` table) — do they store paths or title_ids? If title_ids, fine. If paths, they need updating.

---

## 7. Build order (if/when greenlit)

1. **`CrossVolumeFolderMove` primitive** — task, with the full failure-handling matrix (§3). Rigorous tests on each failure stage. This is ~70% of the work.
2. **Same-volume `reassign_title` MCP tool** — synchronous, ~50 lines. Quick win, doesn't touch the primitive. Can ship before the primitive is done.
3. **Cross-volume `reassign_title`** — wraps the primitive + cast replacement. Small.
4. **`promote_to_queue` task** — wraps the primitive in a per-title loop with the eligibility filter and target-volume selector.
5. **UI surfaces** — Discovery / Pool view button for "Promote selected to queue"; per-title "Reassign" action for the misattribution case.

Stages 2 and 3 are the natural follow-ups; stage 4 is the bigger workflow win. Stage 1 unblocks both.

---

## 8. Non-goals

- **Concurrent moves.** One at a time, under the global task lock. Parallelism would require relaxing the atomic-tasks invariant — separate, much larger discussion.
- **Cross-server moves.** All current volumes live on the same NAS server (different shares); SMB-to-SMB copy works fine. Cross-server (qnap → synology, say) is not in scope.
- **Live moves.** Title can't be open in a video player or being enriched during the move. Pre-flight catches the enrichment-review case; player conflicts are rare enough to ignore for v1.
