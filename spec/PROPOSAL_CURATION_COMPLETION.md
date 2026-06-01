# PROPOSAL: Curation / Unprocessed Workflow Completion — Option A

Status: PLANNING (for review). Generated 2026-06-01.

---

## 1. Goal & Scope

Promote of an enriched draft is half-implemented relative to its own spec (`spec/completed/PROPOSAL_V2_UNPROCESSED.md §1.2`, `PROPOSAL_DRAFT_MODE.md §3/§4.2`): it writes all metadata but does **not** rename the title folder and does **not** mark the title done, so after promote the queue still shows Skip / "Enrich via javdb" as active with no terminal state.

This plan completes the workflow under **Option A only**: **promote renames the folder in place (intra-volume) and marks the title curated/done in the UI**. The title **stays physically in the staging volume** (`unsortedVolumeId = "unsorted"`); a human later moves curated folders cross-volume into pools. The app never does cross-volume moves (impossible by design — `CLAUDE.md`: "File operations are always intra-volume"). Therefore **"done" means "curated + renamed in place, ready for the human to move out"**, NOT "removed from the volume." The same "done" state must be set by **both** curate paths — draft promote (`DraftPromotionService.promote`) and no-draft save (`UnsortedEditorService.replaceActresses`) — so the badge is path-symmetric.

---

## 2. Design

### Core design decision: "curated" and "renamed" are independent

The two durable products are separated deliberately:

- **`curated_at` is set inside the DB transaction** (durable, atomic with the metadata).
- **The folder rename runs post-commit, best-effort** (it is an SMB op; we never hold a SQLite write lock across a NAS network call — same rationale the no-draft path already documents at `UnsortedEditorService.java:350-352`).

Consequence (made explicit): a title can be **marked curated but not renamed** if the SMB rename fails (collision / timeout). This is acceptable and intended. The "done" badge keys off `curated_at`, never off the folder name. A rename failure is a `WARN` log + `folderRenamed: false` advisory in the API response — **not** a rollback of committed metadata. This is precisely why the rename does NOT follow the pre-commit cover-copy pattern (`DraftPromotionService.java:417-436`): rolling back correct cast/tag/audit writes for a cosmetic rename would be disproportionate.

### (a) Folder rename on promote — shared helper

**New class `TitleFolderRenamer`** in `com.organizer3.web`, manually wired in `Application.java`:

```
TitleFolderRenamer(SmbConnectionFactory smbFactory, Jdbi jdbi, String volumeId)
```

Owns: target-name construction, `sanitizeFolderName`, the SMB `rename`, and the DB path rewrite. Static helpers `sanitizeFolderName` / `basename` / `parentPath` are **extracted** from `UnsortedEditorService` (currently `UnsortedEditorService.java:402-424`) into this class (or a small shared util) so promote and no-draft produce **byte-identical** folder names.

Public method:
```
RenameOutcome renameIfNeeded(long titleId, String primaryActressName, String descriptor, String code)
// RenameOutcome { String newPath; boolean renamed; }   // renamed=false on no-op or skip
```

Internal sequence (mirrors `UnsortedEditorService.renameFolderIfNeeded` at lines 362-399):
1. Query current staging path: `SELECT path FROM title_locations WHERE title_id=:id AND volume_id=:volumeId AND stale_since IS NULL LIMIT 1`. **No row → no-op return** (this WHERE clause *is* the scope guard).
2. If `primaryActressName` is null/blank → no-op return.
3. Build target: `"{primary} - {descriptor} ({code})"` if descriptor non-blank, else `"{primary} ({code})"`, then `sanitizeFolderName`.
4. If target == current basename → no-op return.
5. Open `smbFactory.open(volumeId)`; collision check `fs.exists(newPath) && !newPath.equalsIgnoreCase(currentPath)` → on collision, do **not** throw out of promote; return `renamed=false` and let the caller log WARN.
6. `fs.rename(...)`, then **rewrite BOTH `title_locations.path` AND `videos.path`** (reuse the existing `renameFolderInDb` SQL, which already does both — `JdbiUnsortedEditorRepository.renameFolderInDb` lines 173-200). The dual rewrite is load-bearing: `videos.path` desync is a known logged bug (`reference_videos_path_desync`); the helper must keep it.

`renameFolderInDb` opens its own `useTransaction`, which is fine because the rename is post-commit (no enclosing handle). No handle-aware overload is needed.

**`UnsortedEditorService` is refactored** to delegate its rename to the injected `TitleFolderRenamer` (behavior-preserving; same name pattern, same collision guard, same dual path rewrite).

**Where the rename slots into promote:** as a **post-commit Step 10** in `DraftPromotionService.promote`, after the post-commit `effectiveTags.recomputeForTitle` (line 308) and around the `coverStore.delete` best-effort block (lines 313-318). Wrapped in try/catch — on any failure, log WARN with titleId / currentPath / target, set `folderRenamed=false`, do not rethrow. `promote()` returns `titleId` regardless.

**Sourcing the rename inputs:**
- **Primary actress** — promote does **not** currently set `titles.actress_id` (Step 3 UPDATE at lines 374-390 omits it; gap independent of this work since `actress_id` drives filing app-wide). Fix: add `ORDER BY rowid` to the slot query in `executePromotionTransaction` (line 357-361), pick the **first non-skip / non-unresolved resolved slot** as primary, and **extend Step 3's UPDATE to `SET actress_id = :primaryActressId`**. The post-commit rename then reads the canonical name via `findActressCanonicalName`. If every slot is a sentinel, use the sentinel's `canonical_name` (e.g. "Amateur"); if no name at all, skip rename.
- **Descriptor** — draft tables have no descriptor field. Parse it from the **current folder name** at promote time using the existing `extractDescriptor(folderName, code)` (`UnsortedEditorService.java:105-113`). Zero schema change; preserves whatever the human prepped (e.g. "Demosaiced").
- **Code** — `draft.getCode()`.
- **Volume** — new constructor param `unsortedVolumeId` on `DraftPromotionService` (it has none today), wired from `UNSORTED_VOLUME_ID` in `Application.java:1138-1145`.

### (b) Processed-state persistence — new column `title_locations.curated_at`

**Decision: add `title_locations.curated_at TEXT` (nullable ISO-8601), NOT derive from `title_javdb_enrichment` presence.** The derived approach is rejected on the merits:
- The **no-draft save path never writes `title_javdb_enrichment`** — a hand-curated title would never get the badge, violating the both-paths requirement.
- The **autonomous `EnrichmentRunner` writes that row** for titles no human touched — it over-counts.

`curated_at`, written explicitly by **both** curate flows, is the only signal that is reliable *and* path-symmetric. Scope: location-scoped (the staging copy was curated), which is why it lives on `title_locations`, not `titles`.

**Migration — `applyV65()`** (CURRENT_VERSION is 64; `SchemaUpgrader.java:27`):
```java
private void applyV65() {
    log.info("Applying migration v65: curated_at on title_locations");
    jdbi.useHandle(h -> addColumnIfMissing(h, "title_locations", "curated_at", "TEXT"));
}
```
Plus bump `CURRENT_VERSION = 65` and add the dispatch block after line 349:
```java
if (version < 65) { applyV65(); setVersion(65); }
```
Pure idempotent `ADD COLUMN`; existing rows get NULL = "not yet curated" (correct initial state).

**Writes (both keyed by the scope-guard WHERE clause):**
```sql
UPDATE title_locations SET curated_at = :now
 WHERE title_id = :titleId AND volume_id = :unsortedVolumeId AND stale_since IS NULL
```
- **Draft promote:** **inside** `executePromotionTransaction`, after Step 9 (draft rows deleted), before COMMIT — durable and atomic with metadata. Uses the new `unsortedVolumeId` param.
- **No-draft save:** after the `replaceActresses` commit in `UnsortedEditorService` (`unsortedVolumeId` already available, field at line 40).

The `volume_id = :unsortedVolumeId AND stale_since IS NULL` clause **is the scope guard** for both the rename and this UPDATE — a promoted *library* title (DraftPopulator is not staging-scoped) has no live staging row and both writes no-op naturally.

### (c) Terminal UI

**API field:** add `processed` (boolean) to both the list row and the detail view:
- `EligibleListRow` (`UnsortedEditorService.java:58-65`) + `listEligible` — select `curated_at IS NOT NULL`.
- `TitleDetailView` (lines 94-97) + `findEligibleById` — already joins `title_locations`; select `curated_at`; expose `processed = curatedAt != null`.

The list query keeps showing processed titles (they stay in the staging volume); the badge differentiates them. `complete` (`hasCover && actressCount>0`) and `processed` (curated_at set) are **distinct** signals.

**v2 (primary surface):**
- **draft.js `_onPromote`** — on 200, **permanently** disable Validate / Promote / Discard / Skip and show a terminal "Promoted — folder renamed" (or "…rename pending" when `folderRenamed:false`) banner. Re-enable only on failure paths.
- **editor.js `_renderNoDraft`** — when `state.detail.processed`, disable Skip and "Enrich via javdb" and show a "Processed via javdb" pill (Save may stay enabled for descriptor edits).
- **queue.js** — accept `processed`; render a distinct "✓ processed" pill alongside DRAFT. Optionally surface a processed count / filter.
- **index.js** — keep `advance=true`, fire terminal banner before navigation.

**v1 (legacy) — DEFER, but ship the backend `processed` field now.** Per `CLAUDE.md` legacy-protection, JS edits in `modules/` (outside `v2/`/`chrome/`) need explicit approval. The backend `processed` field is **not** legacy-protected and closes the real hazard (re-enriching a curated title creates a fresh draft over committed enrichment). v1 JS changes (disable "Enrich (draft)" + Skip when `processed`, badge in `title-editor-nodraft.js`) are a **deferred, approval-gated** follow-up.

---

## 3. File-by-File Change List

**Backend (rename):**
- `com/organizer3/web/TitleFolderRenamer.java` — **NEW.** Shared rename helper (SMB rename + dual `title_locations.path`/`videos.path` rewrite + collision check + sanitize/basename/parent statics).
- `com/organizer3/web/UnsortedEditorService.java` — inject `TitleFolderRenamer`; delegate `renameFolderIfNeeded`; after `replaceActresses` commit write `curated_at`; add `processed` to `EligibleListRow` + `TitleDetailView`.
- `com/organizer3/javdb/draft/DraftPromotionService.java` — add `unsortedVolumeId` + `TitleFolderRenamer` to constructor; `ORDER BY rowid` on slot query; pick primary = first non-skip resolved slot; extend Step 3 UPDATE to `SET actress_id`; inside txn after Step 9 write `curated_at`; post-commit Step 10 best-effort `renamer.renameIfNeeded(...)`.
- `com/organizer3/Application.java` (~1110-1145) — construct `TitleFolderRenamer`; pass to both services.

**Persistence:**
- `com/organizer3/db/SchemaUpgrader.java` — `CURRENT_VERSION = 65`; dispatch; `applyV65()`.
- `com/organizer3/repository/jdbi/JdbiUnsortedEditorRepository.java` — `listEligible` + `findEligibleById` select `curated_at`; optional `markCurated(...)`; confirm `renameFolderInDb` dual-rewrite reused.

**API/routes:**
- `DraftRoutes.java` — promote 200 includes `folderRenamed` (and optionally `processed`).
- `UnsortedEditorRoutes.java` — list/detail include `processed`.

**v2 UI:** `draft.js`, `editor.js`, `queue.js`, `index.js`.

**v1 UI (DEFERRED, approval-gated):** `title-editor-nodraft.js`, `title-editor.js`.

---

## 4. Testing Plan

**Repository / persistence (real in-memory SQLite):**
- `applyV65` idempotency; existing rows NULL.
- `curated_at` write hits exactly the staging row (volume + `stale_since IS NULL`); library-volume location untouched.
- `listEligible` / `findEligibleById` surface `processed`.
- **`TitleFolderRenamer` rewrites BOTH `title_locations.path` and `videos.path`** (regression for the desync bug).
- Collision returns `renamed=false`, no exception, paths unchanged.

**Promotion path:**
- Promote sets `titles.actress_id` to first non-skip resolved slot (`ORDER BY rowid`).
- Promote writes `curated_at` inside txn even when post-commit rename fails — metadata + `curated_at` durable, `folderRenamed=false` returned.
- Promote of a library title (no staging location) no-ops both.
- Sentinel-only → folder uses sentinel name; no-primary → rename skipped, WARN.
- Existing cover-copy compensation tests still pass.

**UI pin (v2):** promote → terminal banner + disabled actions; navigate-back to processed title → Skip/Enrich disabled, pill shown; queue row shows processed pill.

---

## 5. Edge-Case Decisions Table

| # | Edge case | Recommended default |
|---|-----------|---------------------|
| a | Multi-actress: which is primary? | First non-skip resolved slot under `ORDER BY rowid`; write `titles.actress_id` in Step 3. |
| b | Sentinel-only cast (e.g. "Amateur") | Use sentinel `canonical_name`: `Amateur (CODE)`. |
| b′ | No name resolvable at all | Skip rename; WARN; title still `curated_at`-marked. |
| c | Descriptor in draft mode | Parse from current folder via existing `extractDescriptor`; zero schema change. |
| d | Rename collision | Post-commit WARN + skip (`folderRenamed:false`); never roll back committed metadata. |
| e | Title not on staging volume | Both rename + `curated_at` no-op via `volume_id AND stale_since IS NULL` clause. |
| f | Re-enrich a curated title | New draft created; `curated_at` independent of drafts so it persists. Re-promote re-stamps it. (See Q5.) |
| g | Name parity no-draft vs promote | Shared `TitleFolderRenamer` guarantees identical output. |

---

## 6. Open Questions — RESOLVED 2026-06-01

All confirmed by the user; §2 is written to these.

- **Q1 — Primary actress designation.** ✅ CONFIRMED: first non-skip resolved slot (rowid order); also fixes the `titles.actress_id` gap. No new "primary" toggle.
- **Q2 — Descriptor source in draft mode.** ✅ CONFIRMED: parse current folder name via `extractDescriptor` (zero schema change).
- **Q3 — Sentinel folder name.** Default (unchanged): use sentinel `canonical_name` (`Amateur (CODE)`).
- **Q4 — Rename collision behavior.** Default (unchanged): post-commit best-effort, WARN + skip, metadata stays committed.
- **Q5 — Re-enriching a curated title.** ✅ CONFIRMED: `curated_at` persists across a new draft; re-promote re-stamps.
- **Q6 — Auto-advance after promote.** Default (unchanged): keep `advance=true`, fire banner before navigating.
- **Q7 — v1 UI scope.** ✅ CONFIRMED: ship backend `processed` field + rename now; defer v1 JS as approval-gated follow-up.

---

## 7. Risks & Sequencing

**Phases:**
1. **Backend rename helper** — extract `TitleFolderRenamer`, refactor `UnsortedEditorService` to delegate (behavior-preserving), tests for dual path rewrite + collision.
2. **Promote rename + actress_id** — `ORDER BY rowid`, primary selection, Step 3 `SET actress_id`, post-commit Step 10, `unsortedVolumeId` wiring.
3. **`curated_at` + API** — `applyV65`, writes in both paths, `processed` on list/detail, `folderRenamed` in promote response.
4. **v2 UI terminal state** — draft.js / editor.js / queue.js / index.js.
5. **v1 UI** — deferred, approval-gated.

**Risks:**
- **videos.path desync** — the dual rewrite is load-bearing; pin with a regression test.
- **`titles.actress_id` gap** — promote never set it; extending Step 3 fixes a latent filing bug but touches the hot promote path — cover with tests.
- **SMB lock-across-network** — avoided by keeping rename strictly post-commit.
- **Curated≠renamed divergence** — intentional; badge keys off `curated_at`, UI surfaces `folderRenamed:false` as advisory.
- **Legacy protection** — no v1 JS edits without explicit approval.
