# Proposal: Sync × Metadata Preservation

**Status:** Phases 1-4 SHIPPED (Wave 4A orphan cascade + 4B safety rail + identity-stable rename tools + soft-match heuristics + path history schema/hook/matcher fallback). Phase 5 (sync diff preview) remains deferred indefinitely per spec §6 — likely overkill given 1-4.
**Date:** 2026-04-30
**Origin:** Concern raised after enrichment hardening (Waves 1A–3A-Full): "if I sync a volume now, what happens to my enrichment?"

---

## 1. Problem statement

Curation is intrinsic to this app. Titles and actresses move, get renamed, get re-coded (parser-bug fixes; user reorganizations; manual corrections); actresses get merged. The collection's structure changes constantly, by design.

Enrichment is now expensive — multi-week effort to build, with confidence tiering, audit history, drift detection, and human-confirmed picker decisions. That investment is keyed to `title_id` and `actress_id`. If sync's identity-matching logic loses track of an entity across a rename or recode, it silently treats it as **orphan-old + new** — which destroys enrichment even though the same logical entity is still in the collection.

This proposal addresses the second-order consequence of the hardening: now that we have rich, hard-to-reproduce metadata, sync's existing brittleness is no longer benign.

The companion proposal **Wave 4A** (drafted, ready to hand off) covers a narrower problem — cleanup and history-snapshotting when sync legitimately deletes an orphaned title. **This proposal (4B)** addresses the harder problem: *preventing legitimate-but-renamed titles from being treated as orphans in the first place.*

---

## 2. Current identity model (research findings, 2026-04-30)

### 2.1 Title identity = `code` (exact match, only)

`JdbiTitleRepository.findOrCreateByCode()` (`:299`) matches strictly on `code`. Sync's flow (`AbstractSyncOperation.java:166-185`):

1. Folder discovered → `TitleCodeParser` extracts `(code, baseCode, label, seqNum)` from the folder name.
2. `titleRepo.findOrCreateByCode(template)` — exact-match query on `code`.
3. If no match: INSERT a new title with new `id`.

**Failure scenarios:**

- Folder renamed `ABC-001/` → `ABC-002/` (parser bug fix, manual correction): sync sees a new title and creates a fresh row. The old row has no remaining `title_locations` and becomes an orphan-prune candidate. All enrichment for the old `id` is destroyed (or leaks, depending on FK enforcement — see §2.4).
- Title's parsed code changes due to a parser-logic update across releases: same outcome.

### 2.2 Actress identity = `canonical_name` / alias (case-insensitive)

`JdbiActressRepository.resolveByName()` (`:147-165`): match on `canonical_name COLLATE NOCASE`, then `actress_aliases.alias_name`. No fallback to folder path or `id`.

**Failure scenarios:**

- Actress folder renamed `Yua_Mikami/` → `Yua Mikarni/` (typo introduced by a manual rename): sync extracts the typo'd name, finds no canonical or alias match, **creates a new actress row**. Title is then linked to the wrong actress. The old actress row remains, untouched, with all her enrichment + filmography cache + staging — but no longer attached to the titles she actually owns in the new sync state.
- Actress canonical-name canonicalization changes (e.g. romanization standard updated): same effect.

### 2.3 `title_locations` lifecycle = blind INSERT

`JdbiTitleLocationRepository.save()` (`:32-50`) is INSERT-only. Every sync invocation inserts a fresh location row keyed `(title_id, volume_id, path)`. No path-update logic exists.

- Intra-volume folder move (`/stars/popular/ABC-001/` → `/stars/minor/ABC-001/`): old location row stays with stale path; new row inserted. Duplicate locations for the same title.
- Full sync mitigates by clearing all locations first (`FullSyncOperation.java:58`); partition sync does not.

### 2.4 Enrichment cascade = declared but unenforced

Schema declares `ON DELETE CASCADE` on enrichment tables (`SchemaInitializer.java:435-455`). However `PRAGMA foreign_keys` is OFF in this app — cascade does not fire. Concretely:

- Today: when sync deletes an orphan title, enrichment rows leak (Wave 4A fixes this).
- If `foreign_keys` ever gets turned on (or a future SQLite default change happens): the same delete instantly destroys enrichment with no snapshot — worse than today's leak.

The history table `title_javdb_enrichment_history` is intentionally not FK'd, so the audit trail survives either way — but only if `EnrichmentHistoryRepository.appendIfExists()` is called before the delete. Sync's path doesn't call it (4A's job).

### 2.5 Existing curation tooling does not bridge sync

- `delete_title` — explicit DB-only delete; properly snapshots history (`DeleteTitleTool.java:122-135`). Reference implementation for safe deletion.
- `merge_actresses` — reassigns `titles.actress_id`; preserves enrichment because title_id is stable. Good.
- `rename_actress_folders` — renames on-disk folders. Filesystem only. **Does not update `actresses.canonical_name` or aliases**. Next sync sees the new folder name as a new actress. **Confirmed leak path.**
- `move_actress_folder_to_attention` — updates `title_locations.path`. Doesn't retrigger sync. Safe.
- No tool exists for "rename a title's code while preserving enrichment."

### 2.6 Ghost evidence

The codebase already shows defensive awareness of orphaned enrichment:

- `EnrichmentReviewQueueRepository.findOpenById()` uses LEFT JOIN with explicit comment "rows whose title was deleted (orphan rows) are still returned."
- `EnrichmentReviewQueueCheck` (health check) flags review-queue rows whose `title_code` is null.

These are scars, not solutions. The architecture has been quietly tolerating orphan rows because there's no proactive safety logic.

---

## 3. Threat model

The scenarios that destroy enrichment, in order of likelihood:

| # | Scenario | Trigger | Damage |
|---|---|---|---|
| T1 | Title code recoded on disk | User fixes parser-bug folder, renames `Covers-XYZ/` → `XYZ-001/` | Old title's enrichment orphans/destroyed; new title has no enrichment |
| T2 | Actress folder typo-fixed | User renames `Yua_Mikami/` → `Yua Mikami/` (or vice versa) | New actress row created; titles link to wrong actress; old actress's enrichment orphans |
| T3 | Title moved between partitions | Folder reorg within volume | Duplicate `title_locations`; partition-scoped sync may misclassify |
| T4 | Actress merge done filesystem-first | Two folders manually combined before merge tool used | Sync creates one actress, leaves the other as ghost |
| T5 | Cross-source code-correction | Same physical title appears under a corrected code from a normalize tool | Same as T1 |
| T6 | Orphan-prune + re-add cycle | Volume offline during a sync, then online; title pruned then re-added | Title gets new id; enrichment lost (today: leaks; with FK enforcement: cascade-deleted) |

T1 and T2 are the realistic everyday cases. T3-T6 are edge cases.

---

## 4. Design principles

Working from "metadata preservation is one of the most important objectives of this app":

1. **Identity must be sticky.** Once a title or actress has accumulated enrichment, identity-changing events should reconcile (re-attach metadata to the renamed entity), not orphan-then-recreate.
2. **No silent data destruction.** Any operation that would drop a row with non-trivial enrichment should: (a) snapshot to the audit log first, (b) require explicit user confirmation if the drop is irreversible.
3. **Sync should never be the path that loses data.** Sync's job is to discover the filesystem state. Identity-resolution logic lives in dedicated curation tools that are auditable. If sync can't confidently match a folder to an existing entity, it should defer rather than orphan-and-recreate.
4. **The DB is source of truth for identity.** When the filesystem and DB disagree on a title's code or an actress's name, the DB's existing identity wins until a curation tool explicitly updates it.
5. **Curation tools, not sync, do renames.** `rename_actress_folders` should also update `canonical_name` and add an alias for the prior name (so any other path still resolves). `delete_title` already shows the pattern; it's about closing the gaps.

---

## 5. Proposed solutions (menu of options)

These are not mutually exclusive. Pick a combination.

### Option A — Stronger matching heuristics in sync

Add fallback matching layers when exact-code/exact-name lookup misses:

- **Title:** if `findByCode` misses but `findByBaseCode` hits a single row whose `title_locations.path` overlaps the new folder's path, treat as match (path continuity = identity continuity).
- **Title:** if `findByCode` misses but the parsed `(label, seq_num)` exactly matches one row whose path overlapped historically, surface as **soft match** to a review queue rather than auto-merge.
- **Actress:** if name lookup misses but case-folded normalized name (strip whitespace/punctuation) hits a single existing actress, surface as soft match.
- **Actress:** if a folder name's Levenshtein distance ≤ 1 to a single existing actress, soft match.

**Pros:** Catches T1 and T2 without user intervention in obvious cases.
**Cons:** Risk of false positives. Soft-match-to-review-queue is the safest route but adds friction.

### Option B — Enrichment-bearing safety rail

Refuse to orphan-prune any title with enrichment data without a per-title confirmation:

- `JdbiTitleRepository.deleteOrphaned()` (Wave 4A scope): split into "prune unenriched orphans automatically" + "report enriched orphans to a review queue for explicit confirmation."
- New table or queue category: `enrichment_orphan_review_queue` listing titles whose folder vanished but who carry enrichment. Sync surfaces these in a Tools UI; user decides per-row: "this title is gone (commit delete + snapshot history)" vs "this title was renamed (here's the new folder, merge)."

**Pros:** Hard guarantee against silent data loss. Aligns with the "no destruction without snapshot" principle.
**Cons:** New surface to maintain. User has to actually visit the queue.

### Option C — Identity-stable rename tools

Augment the existing curation tools so they handle the identity-update side properly:

- **`rename_actress_folders`** also updates `canonical_name` (or adds an alias for the old name) so the next sync resolves cleanly.
- **New tool `recode_title`**: takes `(old_code, new_code)` — updates `titles.code`, optionally renames the folder, snapshots enrichment history with `reason='recode'`. Atomic.
- **New tool `reconcile_orphan_titles`**: surfaces enriched orphan titles + new-but-similar titles, lets the user pair them and merge enrichment to the new id.

**Pros:** Aligns with how curation already happens — explicit operations, not implicit sync.
**Cons:** Doesn't help users who don't know about the tools and rename folders directly.

### Option D — Path-as-secondary-identity

Add a `title_locations.previous_paths` JSON array (or a separate `title_path_history` table) that records every path a title has ever been at. Sync's matcher consults it: a folder reappearing at a known historical path matches its prior `title_id` even if the code parsed differently this time.

**Pros:** Strong recovery for T3 and the orphan-then-re-add cycle (T6).
**Cons:** Doesn't help T1/T2 (the path also changes on a code rename). Best as a complement to A.

### Option E — Explicit "sync diff" preview

Before executing a sync, compute and display:
- New titles to be created
- Titles to be orphaned (and whether they have enrichment)
- Locations to be added/removed

Require user confirmation before destructive ops. Like a Terraform plan for sync.

**Pros:** Highest user agency. Great for T6.
**Cons:** Adds significant friction. Not all syncs warrant a confirmation step.

---

## 6. Recommended approach

A staged combination of **B + C + A**, in that order:

1. **Phase 1 — Safety rail (Option B).**
   Stop the bleeding first: ensure no enriched title can be silently destroyed. Builds on Wave 4A's history snapshot. Adds an `enrichment_orphan_review_queue` surfaced in the existing Review Queue UI. Sync prunes unenriched orphans automatically (current behavior, made explicit); enriched orphans require user action.

2. **Phase 2 — Identity-stable rename tools (Option C).**
   Fix the existing leaks in `rename_actress_folders`. Add `recode_title` MCP tool and a `reconcile_orphan_titles` reconciliation UI. Now the user has a *correct* path for the operations they're already doing.

3. **Phase 3 — Stronger matching (Option A).**
   Once the safety rail is in place, soft-match heuristics become safe to add — false positives become "this got into the review queue" instead of "this destroyed enrichment." Layer in path-continuity matching for titles and Levenshtein/normalization for actresses.

4. **Phase 4 (optional) — Path history (Option D).**
   Defer until needed. Phase 1-3 cover the everyday cases.

5. **Phase 5 (optional) — Sync diff preview (Option E).**
   Defer indefinitely. Likely overkill given Phases 1-3.

Reasons for this ordering:
- **B before C/A** because the safety rail makes everything else additive instead of risky. Without B, a bug in C or A could destroy data; with B, the worst case is "user gets a review-queue entry."
- **C before A** because honest tools beat clever heuristics. Users who know there's a `recode_title` tool will use it; no heuristic replaces explicit intent.
- **A last** because heuristics are the trickiest to get right and the safety rail makes them low-stakes.

---

## 7. Resolved design decisions (2026-04-30)

1. **Enrichment threshold.** Any non-null row in `title_javdb_enrichment` regardless of confidence. Confidence is for re-validation policy, not preservation policy — even an UNKNOWN row represents fetch + parse + cast-match work we don't want to redo.
2. **Queue surface.** Reuse the existing `enrichment_review_queue` table with a new `reason='orphan_enriched'` (and consider `reason='recode_candidate'` for Phase 3). One UI surface (Tools → Review Queue), one mental model — same picker layout as 3A-Full's ambiguous bucket.
3. **Actress identity tool.** New `rename_actress(actress_id, new_canonical_name, rename_disk=true)` that atomically (a) adds the old canonical_name as an alias, (b) updates `actresses.canonical_name`, (c) optionally renames the on-disk folder. The alias-add is the critical defense — it survives manual disk renames done outside the app. Filesystem renames done outside the app are normal operations and must be tolerated.
4. **`recode_title` semantics.** Updates DB + disk together by default — DB-only would diverge from disk and the next sync would undo it. Signature: `recode_title(title_id, new_code, dry_run=true)`. Validations: refuse if `new_code` collides with another title row; refuse if the destination folder already exists; refuse if any affected volume is unmounted (no partial recodes). Snapshots enrichment history with `reason='recode'`. DB updates wrapped in a transaction; disk renames wrapped in a try-rollback (rename back if DB update fails after disk succeeded). Re-parses `base_code`/`label`/`seq_num` from the new code.
5. **Path-continuity matching (Phase 3).** Never auto-match on path alone if parsed code differs. Auto-merge only when path identical AND base_code identical AND seq_num identical (purely cosmetic rename — case change, whitespace fix). All other code-mismatch + path-match candidates surface as soft matches in the orphan-review queue.
6. **Actress merge × sync.** Audit during Phase 2 implementation: confirm whether `merge_actresses` already adds the deprecated actress's canonical_name as an alias on the survivor. If yes, no work; if no, fix it during Phase 2.
7. **Catastrophic-flagging guard.** Per `feedback_testing_consistency.md` rule 4. Threshold: if a single sync flags more than `max(50, 10% of titles)` as enriched orphans, refuse the safety-rail step and surface a loud warning — likely indicates volume mount issue or sync bug, not real curation.
8. **Performance.** Phase 1 adds one enrichment-presence query per orphan candidate — trivial. Phase 3's path-continuity matcher adds one query per new title candidate; sanity-check during implementation, batch lookups if too slow.

---

## 8. Out of scope

- Changing the underlying identity model (e.g. moving to UUIDs or content-hash IDs) — too invasive, not the right level for the actual problem.
- Filesystem watcher / live-sync — separate effort.
- Cross-volume identity (multi-volume titles) — current memory says "all file operations are intra-volume", so out of scope.

---

## 9. Sequencing relative to other work

- **Wave 4A (drafted)** — orphan-prune cleanup + history snapshot. Lands before this proposal's Phase 1 because Phase 1 builds on its history-snapshot wiring.
- **Priority 4 — Draft Mode / Bulk Enrich** (Queue "Enrich" button, spec §452). The Draft Mode pattern likely reuses the same orphan-review queue infrastructure as Phase 1, so building the queue first means Priority 4 has less new surface.

---

## 10. Decision checkpoint

- [x] Design conversation on §7 questions (resolved 2026-04-30).
- [ ] Wave 4A merged and verified.
- [ ] Phase 1 prompt drafted, handed off after 4A merges.
- [ ] Phases 2-3 each get their own prompt after Phase 1 lands.
