# PROPOSAL: Relocation & Curation Tooling Hardening

**Status:** Draft 2026-06-05 — for discussion, no implementation yet.
**Origin:** A full session of volume Sorts + cross-volume attention triage (bg/a/hj/k/ma/m/n/r/s, ~1,400 titles filed). The sort engine performed flawlessly (0 video strandings across 8 volumes), but the **post-sort relocation/triage workflow** was slow and repeatedly required manual `sqlite` surgery and multi-minute full syncs to move a *single* folder. One operation also left the DB transiently inconsistent (data-integrity bug). This proposal captures the concrete friction and the targeted fixes/tools that would remove it.

**Relationship to [`PROPOSAL_CROSS_VOLUME_MOVES.md`]:** That proposal builds a `CrossVolumeFolderMove` primitive in which **the tool performs the SMB copy**. In practice we use the [[feedback_cross_volume_attention_staging]] workflow instead: **the user drags the folder** into the destination volume's `/attention/`, and the assistant does the DB-side placement. This proposal targets that user-staged path — the orchestration *after* the drag — plus a set of bugs that affect both workflows. The two proposals are complementary; several items here (videos.path rebase, sync-cancel safety, credit-inference guard) are prerequisites for *either* to be reliable.

---

## 1. Goal & Scope

Make the **user-staged cross-volume relocation** and **single-title curation** workflows fast and safe by:

1. Adding a **targeted single-folder register** primitive so relocations no longer require a full-volume sync.
2. Fixing three **bugs** that forced manual workarounds or risked data integrity: (a) `videos.path` desync on move/rename, (b) unsafe sync cancellation, (c) `trash_title_location` leaving nested noise.
3. Closing two **tool gaps**: a sanctioned `add_title_credit`, and verifying the already-built `reassign_title_credit` is actually deployed.
4. Removing a **registration footgun**: folder-name credit re-inference on re-sync.

**In scope:** MCP tools + the sync/registration internals they depend on. **Out of scope:** the full SMB-copy primitive (covered by `PROPOSAL_CROSS_VOLUME_MOVES`), UI surfaces, the large "location desync recovery" workflow (see §7 / future).

---

## 2. Motivation — observed friction (quantified)

A single cross-volume relocation today is a ~7-step dance, repeated ~10× this session:

```
user drags folder → dest:/attention/        (manual)
→ start full volume.sync on dest            (2–4 min)
→ poll DB until the folder registers
→ cancel_task_run  +  wait for "ended"      (race-prone; see bug §3c)
→ rename_title_folder (canonical)
→ move_title_folder (→ stars/<tier>/<actress>)
→ sqlite UPDATE videos.path                 (manual — bug §3b)
→ sqlite DELETE dangling source rows         (manual)
```

Costs measured this session:
- **~10 full volume syncs** (2–4 min each) purely to register **one** moved folder → ~30+ min of pure wait.
- **~10+ hand-written `sqlite UPDATE videos.path`** statements — the single most repeated manual step, each a chance to fat-finger a path.
- **1 data-integrity incident** (Kokomi Sakura / SOE-372): a cancel-after-register staled an existing location and deleted its video row (recovered via full re-sync).

Each of these maps to a specific fix below.

---

## 3. Design

### (a) `register_folder` — targeted single-folder register *(keystone)*

**Problem.** Registration is full-volume only. `FullSyncOperation` / `PartitionSyncOperation` scan an entire volume (or partition); there is no way to register one manually-placed folder. So every staged relocation pays a full-volume sync just to obtain a `title_locations` row that `move_title_folder` can then act on.

**Proposal.** A synchronous MCP tool `register_folder(volumeId, path)` (and a reusable `SyncOperations.registerFolder(volume, relPath)` service method) that:
1. Verifies the folder exists on the mounted volume and parses its code (reuse `extractActressName` / code parser).
2. Matches the code to an existing `titles` row (or creates one, mirroring scan behavior).
3. Upserts the single `title_locations` row (volume, partition derived from path, path) **without** touching any other location.
4. Registers the folder's videos into `videos` **for that title only**.
5. Returns the title id, the new location, and the videos registered.

**Crucially:** it must NOT call `videoRepo.deleteByVolume` or `markStaleByVolume` (the volume-wide operations that make full sync destructive on cancel — see §3c). It is a scoped, additive operation.

This single primitive eliminates the multi-minute sync from every relocation and removes the need for the dangerous cancel-after-register pattern entirely.

### (b) Auto-rebase `videos.path` in `move_title_folder` / `rename_title_folder` *(bug — near-free fix)*

**Finding.** Both tools call `JdbiTitleLocationRepository.updatePathAndPartition()` (location-only). A dual-rewrite method **already exists** in the same repository — `updatePathPartitionAndVideos()` (lines ~301–362) — but the tools don't call it.
- `MoveTitleFolderTool.java:223` → `locationRepo.updatePathAndPartition(...)`
- `RenameTitleFolderTool.java:191` → `locationRepo.updatePathAndPartition(...)`

**Proposal.** Switch both call sites to `updatePathPartitionAndVideos()`, which rebases the matching `videos.path` rows (old folder prefix → new) in the same transaction. This deletes the most-repeated manual step in the workflow and closes the long-standing desync logged in [[reference_videos_path_desync]]. Guard: rebase only the videos whose `path` begins with the old location path (so keep-both multi-video titles aren't over-rewritten).

### (c) Sync cancellation safety *(data-integrity bug)*

**Finding.** `FullSyncOperation.execute()` runs two volume-wide destructive operations **up front, before scanning**:
- line ~140: `videoRepo.deleteByVolume(volume.id())` — hard-deletes **all** videos for the volume.
- line ~142: `titleLocationRepo.markStaleByVolume(volume.id(), now)` — marks **all** live locations stale.

The scan then re-inserts videos and un-stales locations folder-by-folder. A **cooperative cancel mid-scan** therefore leaves every *not-yet-rescanned* title with its **videos deleted** and its **location stale** (= invisible to the index loader) until the next full sync. This is exactly what corrupted SOE-372's already-filed demosaiced copy this session.

**Proposal (pick one, in order of preference):**
1. **Make `register_folder` (§3a) the standard path** for staged relocations, so cancel-after-register is never needed. (Highest leverage; the other items become belt-and-suspenders.)
2. **Make stale/delete incremental:** instead of delete-all-then-reinsert, diff observed-vs-known per folder and only stale/delete what's confirmed absent at scan completion. Larger change.
3. **At minimum, guard cancellation:** if a sync is cancelled mid-scan, do **not** leave the volume half-deleted — either run the stale/prune step only at successful completion, or auto-restore (re-sync) on cancel. Document that `cancel_task_run` on a sync is unsafe until then.

**Companion remediation (this session) — checked, came back clean.** Volumes `a`, `n`, `r`, `tz` were each cancel-after-register'd; a post-hoc check found only **0–1 locations staled today per volume** (not the hundreds expected if a mid-scan cancel had stranded the un-rescanned remainder). Conclusion: in normal operation the cancel is honored **after** the title-scan phase completes (it only skips the covers phase, per `SyncVolumeTask`'s between-phase cancel checks), so no collateral. The lone genuine casualty was the SOE-372 case, where `FullSyncOperation`'s *internal* mid-scan cancel check fired — already healed by a full re-sync. **Net: the hazard is real but narrow** (only when the internal scan-loop cancel fires before a still-unscanned, already-located title is re-seen). `register_folder` (§3a) removes the need to cancel syncs at all; that's the durable fix. Detection query for future audits: `title_locations` live with `stale_since` = a recent sync timestamp, or live stars locations with 0 `videos`.

### (d) `trash_title_location` — recurse noise cleanup into subfolders *(bug)*

**Finding.** `TrashTitleLocationTool` *does* recursively purge empty descendant dirs (`purgeEmptyDescendantDirs`, lines ~364–376), but noise-file deletion (Thumbs.db/.DS_Store/REASON.txt/images) runs only on the **top-level** children (lines ~300–307). So a `video/` subdir containing its own nested `Thumbs.db` is never emptied → never purged → counted as a leftover non-noise child → the tool returns `status:"partial"`. This forced 3–4 extra manual calls per cleanup (MIDD-669, Noa Kosaka).

**Proposal.** Strip noise files at **every** level during the recursive descent (apply the noise filter inside `purgeEmptyDescendantDirs` before the emptiness check), so a folder whose only remaining contents are nested sidecars collapses fully in one call.

### (e) Registration credit-inference guard *(footgun)*

**Finding.** During scan/registration, `AbstractSyncOperation` (lines ~165–174) infers the actress from the folder name (`extractActressName`) and calls `titleActressRepo.linkAll(titleId, castIds)` → `INSERT OR IGNORE INTO title_actresses`. On **re-registration of an existing title**, this silently re-adds the folder-name actress — even if that credit was deliberately removed/corrected. This re-added a wrong `Kaoru Kira` credit to SMR-034 after we'd reassigned it to Rui Hazuki.

**Proposal.** Only infer-and-link credit when the title is **newly created** during this scan (no existing `title_actresses` rows). For an already-credited title, registration should update location/videos but leave cast untouched. (Workaround until then, now documented: rename a folder to canonical *before* the registration sync.) `register_folder` (§3a) should expose this as default behavior.

### (f) `add_title_credit` MCP tool *(gap)*

**Finding.** There is no sanctioned single-credit **add**. Only `reassign_title_credit` (swap) and `remove_title_credit` exist; `UnsortedEditorService.replaceActresses` is web-scoped to the unsorted volume. Adding a cast member (Suzuka Ishikawa de-actressless; completing the AMA-065 4-cast collection) required raw `sqlite INSERT`.

**Proposal.** `add_title_credit(title_id|title_code, actress_id|actress_name, set_filing?: bool, dry_run)` — INSERT OR IGNORE the `title_actresses` row; optionally set `titles.actress_id` (filing) when the title was actressless or `set_filing` is true; reuse the validation/curation-log scaffolding from `RemoveTitleCreditTool`. Mirrors the existing remove/reassign tools.

### (g) `relocate_staged_title` orchestrator *(composition — optional v2)*

Once (a), (b), (e) land, the whole post-drag dance collapses to one tool:
`relocate_staged_title(title_code, dest_volume_id, dest_actress_id | dest_path, dry_run)` →
`register_folder` → rename canonical → `move_title_folder` (videos.path auto-rebased) → drop dangling source location/video rows → return final single-location state. Turns 7 calls into 1. Build last; it's pure composition.

### (h) Verify `reassign_title_credit` is deployed

`ReassignTitleCreditTool` **is** registered (`Application.java:1281`, gated on `mcpConfig.mutationsAllowed()`), and other mutation tools were live this session — yet tool-search could not find `reassign_title_credit` in the running instance. Conclusion: the running binary predates merge `2c3c6010`. **Action:** rebuild `installDist` + restart so the merged tool is actually exposed; add a smoke check (assert the tool is in the registry when mutations are enabled).

---

## 4. File-by-file change list

**Bug fixes (small, high-value):**
- `mcp/tools/MoveTitleFolderTool.java` — call `updatePathPartitionAndVideos` instead of `updatePathAndPartition` (§3b).
- `mcp/tools/RenameTitleFolderTool.java` — same (§3b).
- `mcp/tools/TrashTitleLocationTool.java` — recurse noise deletion in `purgeEmptyDescendantDirs` (§3d).
- `sync/AbstractSyncOperation.java` — only infer/link cast for newly-created titles (§3e).
- `sync/FullSyncOperation.java` — make stale/video-delete incremental or completion-gated; or document cancel-unsafety (§3c).

**New capability:**
- `sync/SyncOperations.java` (or a new `FolderRegistrar`) — `registerFolder(volume, relPath)` (§3a).
- `mcp/tools/RegisterFolderTool.java` — new tool + registration in `Application.java` (§3a).
- `mcp/tools/AddTitleCreditTool.java` — new tool + registration (§3f).
- `mcp/tools/RelocateStagedTitleTool.java` — new composition tool (§3g, v2).

**Ops:**
- Rebuild + restart to expose `reassign_title_credit` (§3h).

---

## 5. Testing plan

- **`updatePathPartitionAndVideos` wiring** (repository tests, in-memory SQLite): move/rename rebases both `title_locations.path` and the matching `videos.path`; keep-both multi-video titles only rewrite the moved video, not siblings.
- **`register_folder`** (SQLite + a `DryRunFileSystem`/stub volume): registers one folder's location + videos; does NOT touch other titles; does NOT call `deleteByVolume`/`markStaleByVolume`; matches code→existing title; creates title when absent.
- **`TrashTitleLocationTool`** : a folder with `video/` containing a nested `Thumbs.db` + top-level cover/REASON collapses fully to `status:"ok"` in one call (regression for the partial-status bug).
- **Credit-inference guard**: registering an already-credited title via scan does not re-add the folder-name actress; a newly-created title still gets it.
- **Sync cancel safety**: a cancelled sync does not leave previously-live, on-disk titles with deleted videos / stale locations (regression for the SOE-372 incident).
- **`add_title_credit`**: adds a credit; sets filing when actressless; idempotent (INSERT OR IGNORE); dry-run plan matches commit.
- Run targeted only, with `--rerun`, per [[feedback_test_runs]].

---

## 6. Edge-case decisions table

| # | Edge case | Recommended default |
|---|---|---|
| 1 | `register_folder` on a path whose code matches an existing title already located elsewhere | Add a 2nd location (keep-both); never stale/move the existing one |
| 2 | `register_folder` on a folder with no parseable code | Refuse with a clear error (don't invent a name-as-code phantom) |
| 3 | move/rename videos.path rebase when title has multiple videos across folders (keep-both) | Rewrite only videos whose path starts with the moved location's old path |
| 4 | Credit inference on a re-registered title that legitimately *should* gain a cast member | Out of scope for auto-infer; use `add_title_credit` explicitly |
| 5 | `add_title_credit` when title already credits that actress | INSERT OR IGNORE → no-op, report "already credited" |
| 6 | `add_title_credit` `set_filing` on a title that already has a (different) filing actress | Only overwrite filing when actressless or `set_filing:true` explicitly |
| 7 | `trash_title_location` recursive noise strip encounters a non-noise file deep in a subdir | Still return `partial` — never delete unknown files; report the path |

## 7. Open questions

- **Q1.** Should `register_folder` also handle the **location-desync recovery** at scale (the s-volume wrappers: Sakura Yoda 83 titles / 73 unlocated, etc.)? A batch `register_folder` over a wrapper's children would re-link a whole filmography — but the wrapper-nesting (title-folder-inside-actress-folder-inside-/attention) is exactly what the normal scan skips. Possibly a dedicated `recover_unlocated_actress` workflow rather than overloading `register_folder`. (Deferred; the user is reviewing those cases.)
- **Q2.** For §3c, is the incremental-diff rewrite of `FullSyncOperation` worth it, or is "never cancel a sync; use `register_folder` instead" a sufficient operational rule? Leaning: ship `register_folder` first, then decide if the sync rewrite is still needed.
- **Q3.** Should `relocate_staged_title` (§3g) also drop the dangling **source-volume** location/video rows automatically (it knows the source from the title's other locations), or leave that to a follow-up sync of the source volume? Leaning: drop them (that's what we did by hand every time).

## 8. Risks & sequencing

**Sequencing (by value-to-effort):**
1. **§3b videos.path rebase** — ~2 line-change each, existing method, removes the #1 manual step. Ship first.
2. **§3h reassign deploy** — rebuild/restart, no code. Trivial.
3. **§3a `register_folder`** — the keystone; unblocks fast, safe relocations and obviates cancel-after-register.
4. **§3d trash recursion** + **§3e credit-inference guard** + **§3f add_title_credit** — independent small fixes.
5. **§3c sync-cancel safety** — do the operational mitigation (heal a/n/r/tz; document) immediately; decide on the larger incremental-sync rewrite after §3a lands.
6. **§3g `relocate_staged_title`** — composition, last.

**Risks:**
- `updatePathPartitionAndVideos` may over-rewrite if its prefix match is loose — must scope to the exact moved location path (test #3).
- `register_folder` must be airtight about NOT invoking volume-wide stale/delete; a copy-paste from `FullSyncOperation` would reintroduce §3c. Build it scoped from the start.
- The §3c sync rewrite touches the core sync path — highest-risk item; gate behind §3a so it's optional.

## 9. Non-goals

- The SMB-copy primitive and bulk `promote_to_queue` — owned by [`PROPOSAL_CROSS_VOLUME_MOVES.md`].
- UI surfaces for relocation (this is MCP/CLI-side).
- The large location-desync recovery workflow (Q1) — separate proposal once the s-volume wrappers are triaged.
