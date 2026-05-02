# Proposal: Draft Mode + Bulk Enrich

**Status:** Implementation-ready. Design extracted from `spec/completed/PROPOSAL_ENRICHMENT_HARDENING.md` §452-918 (decisions dated 2026-04-28; left untouched here). This document adds build order, effort estimates, API surface, test plan, migration, and acceptance criteria.

**Origin:** Closing out Priority 4 of Enrichment Hardening. The motivating insight: today every Enrich click writes directly to canonical tables, so bad enrichment must be detected and cleaned up afterward. The 232 contaminated rows from slug-mismatch (cleaned up by `EnrichmentClearMismatchedTask`) and the 171 `no_match` rows (resolved by the No-Match Triage UI shipped in PR #23) are both consequences of "writes-first, validate-after." Draft Mode inverts this: nothing lands in canonical state without user review. It is also the foundation for **Bulk Enrich** — select N titles, fire one task, validate at your pace.

**Concrete claim correction:** the existing Queue title editor (`title-editor.js`, 751 lines, wired via `action.js`; backend `UnsortedEditor` repo + service + routes) is real surface. The hardening proposal's "not yet used, retrofit cost is acceptable" claim from 2026-04-28 stands, but the work is non-trivial; budget accordingly (Phase 4).

---

## 1. Summary

Draft Mode introduces mirror "draft" tables (`draft_titles`, `draft_actresses`, `draft_title_actresses`, `draft_title_javdb_enrichment`) that capture user-initiated enrichment work-in-progress. The Queue title editor is rewritten to operate on these draft rows. A user reviews each draft, resolves any cast-slot ambiguities (PICK / CREATE NEW / SKIP / sentinel), then clicks **Promote** — a single DB transaction copies the draft into canonical tables and deletes the draft. Discard drops the draft cleanly with no canonical-state side-effect.

Bulk Enrich is a Utilities task that walks a filtered set of Queue titles and populates drafts en masse. Each draft must still be opened individually for review/Promote — there is no bulk Validate-and-Promote. The autonomous background `EnrichmentRunner` is unaffected: it continues to write real tables directly, gated by the existing write-time validation logic.

**Non-goals for v1:** old-vs-new diff view on re-enrich (whole-row replacement only), per-session typing-undo within a draft, draft sharing across users (single-user app), and field-level cherry-picking of canonical fields (forbidden by Q3 immutability).

---

## 2. Schema

> **From hardening §Draft Mode design, decided 2026-04-28 — verbatim.**

```sql
CREATE TABLE draft_titles (                  -- mirrors `titles`, columns nullable
  id                       INTEGER PRIMARY KEY AUTOINCREMENT,
  title_id                 INTEGER NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
                                              -- the real titles row this draft edits;
                                              -- promotion UPDATEs that row, ROLLBACK on missing.
  code                     TEXT NOT NULL,    -- snapshot of titles.code at draft creation
  -- mirror of titles columns, all nullable; user-editable in editor:
  title_original           TEXT,
  title_english            TEXT,
  release_date             TEXT,
  notes                    TEXT,
  grade                    TEXT,
  grade_source             TEXT,
  -- (any future titles columns added with the same nullable-mirror pattern)
  upstream_changed         INTEGER NOT NULL DEFAULT 0,  -- 1 if sync touched titles row after draft start
  last_validation_error    TEXT,
  created_at               TEXT NOT NULL,
  updated_at               TEXT NOT NULL     -- optimistic-lock token
);
CREATE UNIQUE INDEX idx_draft_titles_title_id ON draft_titles(title_id);
  -- one active draft per title (uniqueness enforced; second populate refuses)

CREATE TABLE draft_actresses (
  javdb_slug               TEXT PRIMARY KEY, -- shared globally across drafts
  stage_name               TEXT,             -- from javdb (immutable once first written)
  english_first_name       TEXT,             -- user-supplied; nullable for mononyms
  english_last_name        TEXT,             -- user-supplied; required for "create new"
  link_to_existing_id      INTEGER REFERENCES actresses(id),  -- when user picks "link to existing"
  created_at               TEXT NOT NULL,
  updated_at               TEXT NOT NULL,    -- optimistic-lock token
  last_validation_error    TEXT
);
  -- ref-counted via draft_title_actresses; orphan rows reaped by GC sweep.

CREATE TABLE draft_title_actresses (
  draft_title_id           INTEGER NOT NULL REFERENCES draft_titles(id) ON DELETE CASCADE,
  javdb_slug               TEXT NOT NULL REFERENCES draft_actresses(javdb_slug),
  resolution               TEXT NOT NULL,    -- 'pick' | 'create_new' | 'skip' | 'sentinel:<actress_id>' | 'unresolved'
  PRIMARY KEY (draft_title_id, javdb_slug)
);

CREATE TABLE draft_title_javdb_enrichment (
  draft_title_id  INTEGER PRIMARY KEY REFERENCES draft_titles(id) ON DELETE CASCADE,
  -- mirror of title_javdb_enrichment (slug, cast_json, release_date, maker, series, grade,
  -- cover_url, tags_json, etc.) — all nullable; populated by worker, edited by user (limited).
  javdb_slug      TEXT,
  cast_json       TEXT,
  maker           TEXT,
  series          TEXT,
  cover_url       TEXT,
  tags_json       TEXT,                          -- raw javdb tags; resolved at promotion
  rating_avg      REAL,
  rating_count    INTEGER,
  resolver_source TEXT,                          -- 'auto_enriched' | 'manual_picker' (when user disambiguates)
  updated_at      TEXT NOT NULL                  -- optimistic-lock token
);
-- NOTE: no draft_title_enrichment_tags table — tags are resolved at promotion, not at populate.

ALTER TABLE actresses
  ADD COLUMN created_via TEXT,    -- 'manual' | 'sync' | 'yaml_load' | 'draft_promotion'
  ADD COLUMN created_at  TEXT;
```

**Migration notes:**
- Pure additive — new tables + nullable columns on `actresses`. No backfill required (NULL `created_via` = pre-Draft-Mode actress, which is fine).
- Existing `actresses` rows continue to function; the `created_via` column is informational only.
- `SchemaUpgrader` adds an `applyVN()` step with these statements; idempotent via existing version-tracking pattern.

---

## 3. Lifecycle

> **From hardening §Draft Mode design — lifecycle steps verbatim, with implementation-ready clarifications.**

```
1. User clicks Enrich (single or bulk) on a Queue title.
2. Worker (single-title via web request, or BulkEnrichToDraftTask for bulk)
   populates draft_* tables. Cover bytes fetched into _sandbox/draft_covers/.
3. Title surfaces in Queue editor with a DRAFT badge.
4. User opens the draft, fills any gaps:
   - Resolves ambiguous javdb stage_names (PICK / CREATE NEW / SKIP / sentinel)
   - Retries cover fetch if it failed
   - Edits intrinsic user tags (only)
5. User clicks Validate — pre-flight check (BEFORE opening promotion transaction):
   - Cast rule check (3 modes by stage_name count — see §5)
   - All actress slots resolved
   - New actresses have non-empty english_last_name
   - Validation failures surface inline; promotion not attempted.
6. User clicks Promote (or "Validate & Promote"). Promotion is one DB transaction:
   a. INSERT new actresses for newly-created drafts; bind generated IDs.
   b. INSERT/UPDATE titles row from draft_titles.
   c. INSERT real title_actresses rows.
   d. INSERT/UPDATE title_javdb_enrichment from draft_title_javdb_enrichment.
   e. INSERT real title_enrichment_tags (tags resolved at this point — §6).
   f. Append audit log row(s) (one comprehensive promotion row — §10).
   g. Copy _sandbox/draft_covers/<draft_id>.jpg → <title-folder>/<code>.jpg
      (only if no cover at base; preserves user-placed covers — §7).
   h. DELETE all draft rows for this title.
   i. DELETE _sandbox/draft_covers/<draft_id>.jpg.
   j. Single COMMIT. Any failure → ROLLBACK; real tables untouched, drafts intact for retry.
7. On promotion failure: write error to draft_titles.last_validation_error; draft survives for retry.
8. Or user clicks Discard → drops draft rows + scratch cover.
   Title returns to Queue, ready for re-enrich.
```

**No bulk Validate-and-Promote.** Each draft requires individual review and Promote click. Bulk Enrich populates drafts at scale; promotion stays per-draft.

**Step 6g caveat** — the cover-copy is a filesystem write inside the transaction-COMMIT path. SQLite cannot rollback the filesystem. Implementation: do the copy *before* the COMMIT; if copy fails, ROLLBACK the DB transaction. If COMMIT then fails (rare; effectively only on disk-full), we have a copied cover but no DB row — handle by reading post-COMMIT errors and deleting the copied file as compensation. This window is small but real; document it as a known tradeoff.

---

## 4. Promotion transaction

> **Detailed sequence + atomicity contract — extends hardening §Lifecycle step 6.**

The promotion path is the most safety-critical operation in the system. Below is the canonical sequence; implementation must follow it exactly.

### 4.1 Pre-flight (no transaction)

```
PreFlight.check(draftTitleId):
  1. Load draft_titles row. If missing → 404.
  2. Validate cast rule by mode (§5 cast validation):
     - 0 stage_names: must have exactly 1 sentinel resolution
     - 1 stage_name: must have ≥1 'pick' or 'create_new'; sentinel forbidden; skip forbidden
     - ≥2 stage_names: either ≥1 'pick'/'create_new' (skips allowed) OR exactly 1 sentinel (no mixing)
  3. For every draft_title_actresses row with resolution='create_new':
       Confirm draft_actresses.english_last_name is non-empty.
  4. Check upstream_changed flag — if 1, return error with code UPSTREAM_CHANGED.
       (Editor handles by showing the Discard/Continue banner; user re-confirms before retry.)
  5. Optimistic-lock check: caller-supplied updated_at must equal current draft_titles.updated_at.
       Mismatch → CONFLICT error; editor reloads.
  Returns: OK or first failure with structured error.
```

### 4.2 Promotion (one transaction)

```
Promote.execute(draftTitleId):
  Within DB transaction (default isolation):
    1. Re-run pre-flight inside the txn (catches races since last check).
    2. For each draft_actresses row referenced by this draft with resolution='create_new':
         INSERT INTO actresses (canonical_name, first_name, last_name,
                                created_via='draft_promotion', created_at=NOW)
         capture generated id.
       For 'pick': use draft_actresses.link_to_existing_id (already validated).
       For 'sentinel:<id>': use that sentinel id directly.
       For 'skip': no actress row touched.
    3. UPDATE titles SET title_original=?, title_english=?, release_date=?, notes=?,
                          grade=?, grade_source='enrichment'
            WHERE id = draft_titles.title_id.
       (titles row pre-exists from sync; UPDATE not INSERT.)
    4. DELETE FROM title_actresses WHERE title_id = ?.  -- replace cast wholesale
       INSERT INTO title_actresses for each non-skipped resolution.
    5. INSERT OR REPLACE INTO title_javdb_enrichment from draft_title_javdb_enrichment,
       setting resolver_source per §8.
    6. DELETE FROM title_enrichment_tags WHERE title_id = ?.
       Resolve raw tags_json → enrichment_tag_definitions ids → INSERT title_enrichment_tags.
    7. INSERT INTO title_javdb_enrichment_history one row capturing:
       - prior_payload: snapshot of pre-promotion canonical state
       - new_payload: snapshot of post-promotion canonical state
       - promotion_metadata: JSON of { resolutions: [...], cover_fetched: bool, ... }
    8. Cover copy (filesystem, NOT inside DB txn — do BEFORE COMMIT):
         If draft scratch file exists AND no cover at title folder base:
           copy scratch → <title>/<code>.jpg
         (preserves manually-placed covers per Q2(a))
    9. DELETE draft_title_javdb_enrichment WHERE draft_title_id = ?.
       DELETE draft_title_actresses WHERE draft_title_id = ?.
       DELETE draft_titles WHERE id = ?.
       (draft_actresses ref-counted; cleaned up by GC sweep when ref-count drops to 0.)
   COMMIT.
   Post-commit: delete scratch cover file (best-effort; sweep catches leaks).
```

### 4.3 Rollback semantics

| Failure point | Effect |
|---|---|
| Pre-flight check (4.1) | No DB writes, no FS writes. Caller sees structured error. |
| Inside txn (4.2 steps 1-7) | Full ROLLBACK. Real tables untouched. Draft rows intact for retry. Set `draft_titles.last_validation_error`. |
| Cover copy fails (4.2 step 8, before COMMIT) | ROLLBACK. Scratch file may have been partially copied to title folder — delete it as compensation. |
| COMMIT itself fails (rare) | Cover already copied; canonical tables not updated. Compensate by deleting the copied cover. Log loudly; this is an unusual disk-level failure. |
| Post-commit scratch cleanup fails | Promotion succeeded. Leak gets reaped by GC sweep. Log. |

### 4.4 Concurrency

- Promotion holds the table-level write locks SQLite gives by default. With WAL mode (organizer3 default), readers proceed.
- Two users promoting different titles simultaneously is fine.
- Two clients trying to promote the same draft: optimistic-lock token (`draft_titles.updated_at`) catches the second attempt; second client sees CONFLICT.

---

## 5. Cast validation & reconciliation

> **From hardening §Q1 — locked decisions, lifted with implementation pointers.**

### 5.1 Cast modes (by stage_name count returned by javdb)

| stage_names | mode | rule |
|---|---|---|
| 0 | sentinel-only | Resolved cast = exactly 1 sentinel (Amateur / Unknown / Various). |
| 1 | strict | Must resolve to ≥1 real actress. Sentinel forbidden. SKIP forbidden. |
| ≥2 | multi-actress (relaxed) | Path A: ≥1 real actress, with optional SKIPs. Path B: exactly 1 sentinel, all stage_names discarded. **No mixing.** |

### 5.2 Per-cast-slot resolutions

| resolution | meaning | requires |
|---|---|---|
| `pick` | link to existing actress | `draft_actresses.link_to_existing_id` set |
| `create_new` | make new actress | `draft_actresses.english_last_name` non-empty |
| `skip` | discard this stage_name | available only in multi-actress mode |
| `sentinel:<id>` | replace cast with sentinel | only one allowed; available in modes 0 and ≥2-Path-B |
| `unresolved` | populator was unable to auto-link | must be replaced with one of the above before Validate |

### 5.3 Stage-name canonicalization (5 passes)

Normalization (applied before any comparison):

1. NFKC unicode normalization (full-width → half-width, etc.)
2. Whitespace normalization (collapse runs, trim; for CJK-only strings strip all internal whitespace)
3. Lowercase Latin-script characters only
4. No honorific stripping

Match passes (first hit wins):

| pass | rule | outcome |
|---|---|---|
| 1 | Exact match on normalized `actresses.canonical_name` | auto-link |
| 2 | Exact match on normalized `actress_aliases.alias_name` | auto-link |
| 3 | Exact match on `javdb_actress_staging.stage_name` for same `javdb_slug` | auto-link (slug-anchored) |
| 4 | Levenshtein ≤ 1 over normalized forms | suggest only — never auto-link |
| 5 | No match | unresolved slot — user must resolve in editor |

**Special case — token count mismatch:** if javdb returns one token (`Mana`) and DB has two (`Mana Sakura`), pass 4 disabled. User must use picker's existing-actress search.

**Auto-link is a draft-only concern.** The autonomous background runner uses passes 1-3 only. Pass 4-5 → opens `enrichment_review_queue` (existing flow), runner does not write.

### 5.4 Forensic preservation (no data loss on SKIP)

`title_javdb_enrichment.cast_json` always contains the full original javdb cast verbatim, including SKIPped stage_names. `title_actresses` contains only the resolved entries. The audit log captures SKIP actions in `promotion_metadata`. Re-enrich can reconsider SKIPped stage_names later.

---

## 6. Tag handling — immutability

> **From hardening §Q3 — locked.**

Three tag classes:

| class | source | user can toggle? |
|---|---|---|
| Enrichment | from javdb during Enrich | **No** — immutable, locked icon |
| Label/code-derived | from `label_tags` + product code | **No** — auto-derived |
| Intrinsic user | user-applied | Yes |

**Resolved at promote, not populate.** Drafts store only raw `tags_json` on `draft_title_javdb_enrichment`. The promotion transaction (step 6) applies the current alias map (`enrichment_tag_definitions.curated_alias`) to derive `title_enrichment_tags` rows. This means:

- Stale alias mappings cannot ship to canonical state.
- Alias-map fixes propagate to subsequent promotions automatically.
- Editor preview computes "tags that will land" live from `tags_json` + current map (same logic as promotion, just no writes).

---

## 7. Cover handling — two-phase scratch

> **From hardening §Q2 — locked, draft-mode timing.**

### 7.1 Populate phase

```
fetch cover bytes via existing ImageFetcher (free fetch, no rate limiter)
write to _sandbox/draft_covers/<draft_title_id>.jpg
on failure: write draft_titles.last_validation_error = "cover_fetch_failed:..."
            (editor surfaces "cover not fetched — retry?" without blocking populate)
```

### 7.2 Editor

- Renders the scratch file via a new `/api/drafts/:id/cover` endpoint.
- "Refetch" button → re-runs fetch + rewrites scratch.
- "Clear" button → deletes scratch (no preview shown).

### 7.3 Promote phase

Inside promotion (§4.2 step 8): if scratch exists AND title folder has no cover at base, copy scratch → `<title>/<code>.jpg`. Then delete scratch. Existing user-placed covers always preserved (Q2(a)).

### 7.4 GC

The daily GC sweep (§9) reaps orphan scratch files whose `draft_titles.id` no longer exists.

### 7.5 Sandbox directory contract

`_sandbox/draft_covers/` is an organizer3-managed directory under `dataDir`. Per memory rule (Automation workspace / sandbox is AI-only), this is app-managed; no `_trash` lifecycle, no user-visible artifacts. Implementation creates the directory on first write if missing.

---

## 8. Provenance — `resolver_source` values

> **From hardening §Q4 — locked.**

| value | meaning |
|---|---|
| `auto_enriched` | Came from a draft promotion (user-validated Enrich result) |
| `manual_picker` | User picked from ambiguous-resolution picker during draft work |
| `discovery_feed` | Slug from javdb's recent-releases scrape (canonical) |
| `actress_filmography` | Background runner via filmography lookup |
| `code_search` | Background runner via code-search + cast verify |
| `cleanup_cleared` | Cleanup task cleared this row, awaiting re-enrich |

`auto_enriched` is the typical Draft Mode promotion. `manual_picker` is set if the user used a Levenshtein-1 suggestion or picked from search during validation.

---

## 9. Sync interaction & GC

> **From hardening §Q5 (sync) and §Draft Mode design (GC) — locked.**

### 9.1 Sync interaction

- Sync continues to write folder-identity facts (`titles.code`, `title_locations`, `last_seen_at`) directly to real tables. Sync does not interact with drafts.
- If sync rediscovers a title that has an active draft: real-table updates proceed normally; `draft_titles.upstream_changed = 1` is set.
- Editor surfaces a banner when `upstream_changed = 1`: "The underlying title was re-synced after this draft started. Discard and restart? [Discard] [Continue anyway]".
- Sync deleting a title cascades draft deletion via `ON DELETE CASCADE` on `draft_titles.title_id`.

### 9.2 GC sweep

- **Daily at 2am UTC** (configurable via `enrichment.draftGcCron`).
- Reaps:
  - `draft_titles` rows where `updated_at < now() - 30 days` (30-day stale-draft TTL).
  - `draft_actresses` rows with zero referencing `draft_title_actresses` rows (orphan cleanup).
  - `_sandbox/draft_covers/<id>.jpg` files where no `draft_titles.id = <id>` exists.
- Configurable via `enrichment.draftMaxAgeDays` (default 30).

### 9.3 Audit log scope on drafts

- Drafts produce **no** audit log rows. Mid-draft edits (typing, picking, retrying covers) are transient.
- The **promotion event** writes one comprehensive `title_javdb_enrichment_history` row capturing:
  - `prior_payload`: pre-promotion canonical state
  - `new_payload`: post-promotion canonical state
  - `promotion_metadata`: JSON `{ resolutions: [...], skip_count: N, sentinel_chosen: id?, cover_fetched: bool }`
- **Discards are silent** — no audit row.

---

## 10. Bulk Enrich

> **From hardening §Q5 — locked.**

### 10.1 Architecture

```
                  ┌──────────────────┐
                  │ JavdbSlugResolver│
                  │ JavdbClient      │   ← shared parsing/resolution
                  │ JavdbExtractor   │     (one path through javdb data)
                  │ JavdbProjector   │
                  └──────────────────┘
                          ▲
              ┌───────────┴───────────┐
              │                       │
      ┌───────────────┐       ┌──────────────────────┐
      │ Background    │       │ BulkEnrichToDraftTask│
      │ Enrichment    │       │ (Utilities task)     │
      │ Runner        │       │                      │
      │ writes REAL   │       │ writes DRAFT tables  │
      └───────────────┘       └──────────────────────┘
              │                       │
              ▼                       ▼
   title_javdb_enrichment    draft_title_javdb_enrichment
```

Shared: resolver, extractor, projector, HTTP client, rate limiter. Bulk-specific: a new `DraftEnrichmentRepository` (writes draft tables) and the task class.

### 10.2 Write-time gate semantics

Both writers run the same gate logic (HIGH/MEDIUM/LOW/refuse verdict). The "refuse" outcome differs:

- **Background runner**: opens `enrichment_review_queue` entry, no write.
- **Bulk task / single-Enrich populator**: writes draft with the cast slot marked `unresolved`. Picker resolves in editor; no `enrichment_review_queue` entry.

### 10.3 Pause/resume — derivative state

The runner's paused/unpaused state is derived from `task_runs`, not stored:

```
runner.isPaused() := exists(task_runs row WHERE task_id IN <pause-issuing-tasks>
                            AND status = 'RUNNING')
```

Pause-issuing tasks (currently `enrichment.bulk_enrich_to_draft`) listed in code, not DB.

**Fast path**: TaskRunner emits `task_started`/`task_ended` events; runner subscribes and toggles in-memory flag.

**Slow path (self-heal)**: runner re-queries `task_runs` on each tick; missed events resolve naturally on next tick once TaskRunner's startup cleanup reaps stale RUNNING rows.

### 10.4 Scope: "Enrich N visible"

- Acts on user's currently-filtered Queue view.
- Excludes: titles already curated (have validated `title_javdb_enrichment`), titles with active draft.
- Button label: "Enrich 47 titles".
- Confirmation modal lists exclusions: "47 titles will be enriched. (3 already have drafts, 2 already curated — excluded.) Background runner will be paused. Proceed?"

### 10.5 Cancel behavior

- In-flight fetch completes → its draft writes → task tears down.
- Drafts written so far persist regardless of cancel.
- No formal resume — re-running is idempotent (exclusion rules skip already-drafted titles).

### 10.6 Concurrency

`TaskRunner` already enforces single-task-per-utility — no extra logic.

---

## 11. Editor UI

> **Mostly new content; field-by-field editability table from hardening §Draft Mode design.**

### 11.1 Field editability matrix

| field | editability |
|---|---|
| Product code | read-only (folder-derived) |
| `title_original`, `release_date`, `maker`, `series`, `notes` | **read-only** — javdb-frozen |
| Cast slot resolution | **action required** when ambiguous |
| New actress English name | editable — last_name required, first_name optional |
| Sentinel selection | editable when stage_name=0 mode |
| Cover image | clear / refetch only |
| Enrichment tags | read-only / 🔒 locked icon |
| Label-derived tags | read-only / auto-derived |
| Intrinsic user tags | editable |

### 11.2 Editor states

The editor renders one of three states based on the title's current relationship to drafts:

| state | trigger | UI |
|---|---|---|
| **No draft** | title has no `draft_titles` row | "Enrich" button (calls populate); intrinsic-tag editing only on canonical. |
| **Draft populated** | `draft_titles` exists | DRAFT badge in header; full draft editor active. |
| **Draft + upstream_changed** | `draft_titles.upstream_changed=1` | Banner: "Underlying title re-synced. Discard and restart? / Continue anyway." |

### 11.3 Cast-slot picker component

For each entry in `draft_title_actresses` with `resolution='unresolved'`:

```
┌───────────────────────────────────────────────────────┐
│ javdb says: "天海 麗" (slug: AbCd)                     │
│ Suggestion (Levenshtein-1): Rei Amami     [Link]      │
│   — or —                                              │
│ [Search existing actress...]  [Create new...]         │
│ [SKIP this stage_name]  [Replace cast with sentinel]  │
└───────────────────────────────────────────────────────┘
```

For auto-linked slots:

```
✓ Mana Sakura (resolved)             [Unlink and pick different]
```

Auto-linked must always have an unlink affordance (one click away).

### 11.4 Validate / Promote / Discard buttons

- **Validate** — runs §4.1 pre-flight; surfaces errors inline; no DB writes.
- **Promote** — runs §4.1 + §4.2; on success, clears editor and returns user to Queue list.
- **"Validate & Promote"** — combined button if no validation errors expected; shows confirm modal listing actions ("3 new actresses will be created, cover will be copied to title folder").
- **Discard** — confirms ("This will drop the draft and all its work; the title returns to Queue."), then deletes draft rows and scratch file.

### 11.5 Cover preview

- Fetched at populate, cached in `_sandbox/draft_covers/`.
- Editor renders via `/api/drafts/:id/cover` (resolves to scratch file or 404).
- "Refetch" → re-runs fetch + rewrites scratch.
- "Clear" → deletes scratch file (preview disappears; promotion does no copy).

### 11.6 Frontend retrofit scope

`title-editor.js` (751 lines) currently operates against the `UnsortedEditor` backend. Retrofit:

1. Add a new `draft-state` module — pure state (current draft, pending edits, optimistic-lock token).
2. Convert title-editor's read paths from `UnsortedEditor` endpoints to `/api/drafts/*`.
3. Add picker subcomponent for unresolved cast slots.
4. Add Validate/Promote/Discard action handlers.
5. Add upstream_changed banner.
6. Keep intrinsic-tag editor as-is (already operates on `title_tags`).

The backend `UnsortedEditor` (repo + service + routes) likely shrinks to a thin "draft populate" entry point that delegates to the new draft-mode service. Rename for clarity post-refactor.

---

## 12. API surface

### 12.1 New routes

```
POST   /api/drafts/:titleId/populate       Start single-title populate.
                                            Returns 409 if draft already exists.
                                            Returns the new draft row on success.

GET    /api/drafts/:titleId                Fetch current draft (titles + actresses
                                            + enrichment + cover-preview-url).
                                            404 if no draft.

PATCH  /api/drafts/:titleId                Update draft fields. Body:
                                            { castResolutions: {...},
                                              newActresses: {...},
                                              intrinsicTagEdits: {...},
                                              expectedUpdatedAt: <token> }
                                            409 on optimistic-lock failure.

GET    /api/drafts/:titleId/cover          Stream the scratch cover image.
                                            404 if no scratch file.

POST   /api/drafts/:titleId/cover/refetch  Re-fetch cover; rewrite scratch.

DELETE /api/drafts/:titleId/cover          Drop scratch (no copy at promote).

POST   /api/drafts/:titleId/validate       Pre-flight; returns OK or
                                            { errors: [...] }.

POST   /api/drafts/:titleId/promote        Validate + promote in one call.
                                            Returns canonical title id on success.

DELETE /api/drafts/:titleId                Discard. Drops draft rows + scratch.

GET    /api/drafts                         List all active drafts (paginated).
                                            For Queue UI badging.
```

### 12.2 Existing route impact

- `UnsortedEditor` routes — stay during transition; eventually thin wrappers around populate or removed entirely.
- `/api/titles/:id` — unchanged (reads canonical state).
- `/api/enrich/...` (existing single Enrich path) — refactored to call populate + auto-open editor.

### 12.3 Bulk Enrich task

Registered as `enrichment.bulk_enrich_to_draft` via `TaskSpec`:

```
inputs:
  titleIds: List<Long>           — explicit list (UI passes filtered set)
  rateLimitOverride: optional    — defaults to runner's current rate
phases:
  resolveSlugs       (one filmography fetch per actress, mostly cache hits)
  fetchTitles        (one javdb hit per title; rate-limited)
  writeDrafts        (DB writes)
  fetchCovers        (parallel, free fetch)
events:
  task_started, task_ended (for runner pause/resume)
  per-title progress (queued / fetched / written / cover-pending)
```

### 12.4 Repositories (new)

```
DraftTitleRepository
  insert(draftTitle): long
  findById(id): Optional<DraftTitle>
  findByTitleId(titleId): Optional<DraftTitle>
  update(draftTitle, expectedUpdatedAt): void  // optimistic-lock
  delete(id): void
  listAll(offset, limit): List<DraftTitle>
  setUpstreamChanged(titleId): void           // sync hook
  reapStale(maxAgeDays): int                  // GC

DraftActressRepository
  upsertBySlug(draftActress): void
  findBySlug(slug): Optional<DraftActress>
  reapOrphans(): int                          // GC; ref-count via draft_title_actresses

DraftTitleActressesRepository
  replaceForDraft(draftTitleId, resolutions): void

DraftTitleEnrichmentRepository
  upsert(draftTitleId, enrichment): void
  findByDraftId(draftTitleId): Optional<DraftEnrichment>
```

### 12.5 Services (new)

```
DraftPopulator           — single-title populate; called from web + bulk task
  populate(titleId): DraftTitle

DraftPromotionService    — the §4 transaction
  preflight(draftId): PreflightResult
  promote(draftId, expectedUpdatedAt): long  // returns title_id

BulkEnrichToDraftTask    — Utilities task; wraps DraftPopulator
                           in a per-title loop with cancel + progress
```

---

## 13. Build order / phases

Each phase below is independently shippable and self-contained. Phases ordered for incremental value + minimal coupling.

### Phase 1 — Schema + draft repos (1 session)

- `SchemaUpgrader.applyVN()`: CREATE the 4 draft_* tables, ALTER actresses for `created_via`/`created_at`.
- All four repository interfaces + Jdbi implementations + tests (in-memory SQLite).
- No behavior change yet; pure data layer.

**Ship point:** schema + repos in main, no consumer. Unblocks all later work.

### Phase 2 — DraftPopulator + cover scratch (3 sessions)

- `DraftPopulator` service: fetches via shared resolver/extractor/projector, writes draft rows, writes scratch cover.
- `_sandbox/draft_covers/` directory contract.
- Single-Enrich web route: `POST /api/drafts/:titleId/populate`.
- Tests: populator with mocked javdb client (use existing test fixtures).
- Integration test: real-DB populate produces valid draft rows.

**Ship point:** "Enrich" button populates a draft (no editor changes yet — verify via DB / API). Drafts exist but no promote path; safe because nothing is written to canonical.

### Phase 3 — Validation + promotion transaction (2 sessions)

- `DraftPromotionService.preflight()` and `.promote()` per §4.
- `POST /api/drafts/:titleId/validate` and `/promote`.
- Audit log writer for the promotion event with `promotion_metadata`.
- Tests: each ROLLBACK case (mid-txn failure, COMMIT failure compensation, optimistic-lock conflict, pre-flight failures, all 3 cast modes).

**Ship point:** API can populate + promote a draft end-to-end. Verifiable via curl. Editor still uses old path.

### Phase 4 — Editor refactor (3 sessions)

- Frontend: convert `title-editor.js` to operate against `/api/drafts/*`. New picker subcomponent. Validate/Promote/Discard handlers. upstream_changed banner.
- Backend: trim `UnsortedEditor*` to a thin populator-delegation layer (or remove and replace).
- Manual UI verification: walk the populate → resolve → promote loop in browser (puppeteer or real browser).

**Ship point:** users can do single-title Enrich via Draft Mode UI. Old direct-write path retired.

### Phase 5 — Sync hook + GC sweep (1 session)

- Sync hook to set `upstream_changed=1` when a synced title has an active draft.
- GC sweep service running on `enrichment.draftGcCron` (default daily 2am).
- Sweep covers: stale `draft_titles` (>30d), orphan `draft_actresses` (ref-count zero), orphan scratch files.
- Tests: each sweep case.

**Ship point:** drafts no longer accumulate indefinitely; sync drift is signaled.

### Phase 6 — Bulk Enrich (2 sessions)

- `BulkEnrichToDraftTask` Utilities task with phases, cancel, progress events.
- TaskRunner event emission (`task_started` / `task_ended`) — verify exists; add if not.
- Runner pause: derivative state via `task_runs` query + in-memory flag.
- "Enrich N visible" button on Queue + confirm modal.
- Tests: cancel mid-flight saves drafts so far; pause/resume self-heals on missed event; idempotent re-run skips already-drafted.

**Ship point:** Bulk Enrich workflow live end-to-end.

**Total: ~12 sessions.**

---

## 14. Effort estimate

| Phase | Sessions | Risk |
|---|---|---|
| 1 — Schema + repos | 1 | Low |
| 2 — Populator + cover scratch | 3 | Medium (cover FS + sandbox contract; first non-trivial use of two-phase write) |
| 3 — Validation + promotion txn | 2 | High (transactional safety-critical; ROLLBACK cases) |
| 4 — Editor refactor | 3 | Medium (751-line frontend + UI verification) |
| 5 — Sync hook + GC | 1 | Low |
| 6 — Bulk Enrich | 2 | Medium (TaskRunner event surface — may need building) |
| **Total** | **12** | |

Calendar time: 4-6 days of focused work. Not a single-session feature.

---

## 15. Test plan

### 15.1 Repository layer

- Real in-memory SQLite via `SchemaInitializer`.
- All CRUD paths per repo.
- Optimistic-lock enforcement (mismatch → exception).
- GC sweeps: stale draft, orphan actress, orphan scratch.
- ON DELETE CASCADE: deleting `draft_titles` cascades to `draft_title_actresses` and `draft_title_javdb_enrichment`; deleting `titles` cascades to `draft_titles`.

### 15.2 Service layer

- `DraftPopulator`: javdb client mocked; verifies correct draft row contents per fixture.
- `DraftPromotionService.preflight`: each cast-mode rule (3 modes × valid + invalid cases); `english_last_name` requirement; `upstream_changed` rejection.
- `DraftPromotionService.promote`: full happy path; each ROLLBACK case (simulated DB failure mid-txn); optimistic-lock conflict; cover-copy compensation on COMMIT failure.
- `BulkEnrichToDraftTask`: phased dry-run; cancel mid-flight saves drafts; idempotent re-run skips already-drafted.

### 15.3 Route layer

- All 8 new routes (extends `WebServerTest`).
- Authorization unchanged — no auth changes.
- Error responses (404 missing, 409 conflict, 400 validation).

### 15.4 Frontend

- Manual verification (puppeteer or browser):
  - populate → resolve → validate → promote happy path
  - cast-slot picker for unresolved
  - "Try other actress" reassign (existing component reuse)
  - upstream_changed banner appears + Discard/Continue both work
  - Discard cleans up scratch + draft rows

### 15.5 Migration regression

- Schema apply: pre-Phase-1 DB → post-Phase-1 DB; verify `created_via` column exists with NULL on existing rows.
- Existing actresses still loadable / queryable.

---

## 16. Migration & rollout

**Schema migration** is purely additive — see §2 migration notes. Existing data unaffected.

**Code rollout** is phased per §13. Each phase is independently revertible:

- After Phase 1: drafts schema present but unused. Reverting = drop tables.
- After Phase 2: drafts get populated by API; no canonical state mutated. Reverting = stop calling populator.
- After Phase 3: API can promote. No UI yet so no users hit it. Reverting = remove routes.
- After Phase 4: Editor uses Draft Mode. Old direct-write path retired. Reverting = revert editor commit (drafts still safe in DB).
- After Phase 5/6: sweeps + bulk live.

**No feature flag** — phases ship behind no-UI, no-route, or no-task gates naturally. The user can choose which phase is "good enough" to stop at.

**No data backfill** — pre-Mode actresses keep `created_via=NULL`; harmless.

---

## 17. Open risks / unknowns

1. **TaskRunner event mechanism (Phase 6).** Design assumes `task_started` / `task_ended` events exist or are easy to add. **Action: confirm at start of Phase 6; if missing, +0.5 session to add.**

2. **Cover-copy timing (§4.2 step 8 / §3 step 6g).** Filesystem write inside the promotion path before COMMIT. The COMMIT-failure compensation is rare but real. Documented; acceptable tradeoff per design.

3. **`title-editor.js` retrofit complexity.** 751 lines. Phase 4 budgeted 3 sessions; could swell to 4 if existing code has more entanglement than visible from the file count. **Action: do a 30-min code spike at start of Phase 4 to confirm budget.**

4. **Sandbox directory contract.** `_sandbox/draft_covers/` needs to be created on first write, ignored by sync, surviving across restarts. Memory note flags `_sandbox` as AI-only zone — this aligns. Verify no existing sweep/cleanup logic touches `_sandbox` paths.

5. **Re-enrich UX (post-v1).** Locked design says re-enrich is wholesale replacement; v1 ships without diff view. Real risk: silent regressions on re-enrich. Mitigation: `title_javdb_enrichment_history.prior_payload` preserves recoverable state forever. Not a v1 blocker.

6. **Performance of bulk populate.** 50-title bulk = ~50 javdb fetches @ 0.33/s = ~150s minimum. Acceptable. Verify rate limiter handles the bursts.

7. **Discovery feed wishlist coexistence.** Drafts only for owned titles; favorites are orthogonal. Verify favorites mechanism remains untouched after editor refactor.

---

## 18. Acceptance criteria

- [ ] Schema migration applied; new tables visible; existing actresses unaffected.
- [ ] `DraftPopulator` produces a complete `draft_titles` + `draft_title_javdb_enrichment` row from a known javdb slug fixture.
- [ ] Cover scratch file written under `_sandbox/draft_covers/<id>.jpg` for a fixture with valid cover_url.
- [ ] `validate` returns structured errors for: cast-mode violation, missing `english_last_name`, `upstream_changed=1`, optimistic-lock conflict.
- [ ] `promote` happy path: real `titles` updated, `title_actresses` matches resolved cast, `title_javdb_enrichment` row created/updated, `title_enrichment_tags` resolved against current alias map, audit log row written with `promotion_metadata`, draft rows deleted, scratch file deleted.
- [ ] `promote` ROLLBACK: any mid-txn failure leaves real tables untouched and draft intact with `last_validation_error` set.
- [ ] Editor: populate → resolve unresolved → validate → promote loop verified manually in browser.
- [ ] Discard: drops draft rows + scratch file; title returns to Queue.
- [ ] upstream_changed banner appears when sync touches an active draft's title.
- [ ] GC sweep: stale draft, orphan actress, orphan scratch all reaped on dry-run + real run.
- [ ] Bulk Enrich: 47-title run produces 47 drafts; runner paused throughout; cancel mid-flight saves drafts so far; re-run skips already-drafted.
- [ ] All ROLLBACK regression tests green.
- [ ] No existing test regressions.

---

## 19. Out of scope (future)

- Old-vs-new diff view on re-enrich (v1 is whole-row replacement).
- Per-session typing-undo within a draft.
- Field-level cherry-pick on re-enrich (forbidden by Q3 immutability).
- Multi-user draft editing / merge UI (single-user app).
- Auto-promote on Bulk Enrich completion (decision: every draft requires individual review).
- Cover prefetch from Discovery feed.
- Concurrent Bulk Enrich runs (TaskRunner enforces single-task).
