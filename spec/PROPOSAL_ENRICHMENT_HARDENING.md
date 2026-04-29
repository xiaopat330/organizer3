# Proposal: Enrichment Pipeline Hardening

**Status:** Aligned 2026-04-28 — all design questions resolved; ready to implement.
**Scope:** Make javdb enrichment robust, observable, and self-healing. Successor to `PROPOSAL_JAVDB_SLUG_VERIFICATION.md` — the slug-mismatch fix plugged the immediate hole; this proposal addresses the systemic patterns that let it happen.

---

## Motivation

The slug-mismatch incident (2026-04-28) revealed several "fast and loose" patterns in the enrichment pipeline:

| Pattern | Consequence |
|---|---|
| No write-time cast validation | Bad enrichment rows landed and sat for months |
| No provenance on rows | Had to derive corruption via heuristic SQL after the fact |
| No filmography persistence | Cache lost on every restart; right-thing was expensive |
| No audit trail | Can't reconstruct which enrichments changed when |
| Unconditional "first-hit" code-search | The original bug — silent failure mode |
| Sentinels can fall through to code-search | Same risk class as above |

Today's cleanup recovered 192 contaminated rows, but a re-occurrence is fully possible without these structural fixes.

---

## Design principles

1. **Authoritative > heuristic** — actress-anchored filmography lookup is ground truth. Code-search is only acceptable with a verification gate.
2. **Provenance is mandatory** — every persisted row records *how* it got there. Trust tiering becomes queryable.
3. **Observability before optimization** — health surfaces and audit trails ship before we tune throughput.
4. **Self-healing where possible** — re-validation cron catches drift before it becomes another 232-row cleanup.
5. **Defense in depth** — write-time gate, persistence, audit, periodic re-check. Each catches what the others miss.

---

## Roadmap (proposed)

### Priority 1 — Correctness

#### 1A: Filmography persistence (Step 4 of slug-verification proposal)

Persist per-actress `code → titleSlug` mappings. Per-process in-memory cache stays as L1; **the database is the source of truth (L2)**; idempotent JSON file backups under `dataDir/backups/filmography/` are the disaster-recovery seed.

**Database schema** (decided 2026-04-28):
```sql
-- Metadata (one row per actress)
CREATE TABLE javdb_actress_filmography (
  actress_slug      TEXT PRIMARY KEY,
  fetched_at        TEXT NOT NULL,
  page_count        INTEGER NOT NULL,
  last_release_date TEXT,
  source            TEXT NOT NULL          -- 'http' | 'imported_backup'
);

-- Entries (one row per (actress, code) pair)
CREATE TABLE javdb_actress_filmography_entry (
  actress_slug TEXT NOT NULL,
  product_code TEXT NOT NULL,
  title_slug   TEXT NOT NULL,
  PRIMARY KEY (actress_slug, product_code),
  FOREIGN KEY (actress_slug) REFERENCES javdb_actress_filmography(actress_slug)
    ON DELETE CASCADE
);
CREATE INDEX idx_filmography_entry_code ON javdb_actress_filmography_entry(product_code);
```

The columnar `_entry` table is what makes Step 8's "which actresses' filmographies contain this code?" a single indexed query — fundamental to the no_match resolver UI's cross-actress suggestion feature. A JSON-in-TEXT representation would force a full-table scan + `json_each` walk on every query.

**Refresh triggers:**
- Auto on `/actors/{slug}` profile fetch (the actress page IS page 1 of filmography — free)
- Manual via MCP `actress.refresh_filmography <slug>` and Step 8 resolver UI button
- Soft TTL (90 days), but skipped when `last_release_date` is >2y old (treat catalog as "settled" — retired actresses)

**Backups (idempotent JSON):**
- **Auto-export on every successful HTTP fetch:** the row that just landed is also written to `dataDir/backups/filmography/{prefix}/{slug}.json`. Sharded by the first 2 chars of slug so any single directory stays small (e.g., `J9/J9dd.json`).
- **Snapshot tool:** `actress.archive_filmography_backups` zips the current per-actress files into `dataDir/backups/filmography_snapshot_YYYY-MM-DD.zip`, keeps last N. For off-machine DR.
- **Restore tool:** `actress.import_filmography_backup` accepts either a directory path OR a snapshot zip and upserts into the DB. Same code path either way.

**MCP tools:**
- `actress.refresh_filmography <slug>` — force HTTP re-fetch
- `actress.evict_filmography <slug>` — drop one actress's data
- `actress.export_filmography_backup` — DB → per-actress JSON files
- `actress.archive_filmography_backups` — bundle current backup files into snapshot zip
- `actress.import_filmography_backup <path>` — JSON files or zip → DB

**Eliminates:** the "drain re-fetches every actress's filmography per title" cost (today's session lost ~5-10x amplification on this).

#### 1B: Provenance columns on `title_javdb_enrichment`

```sql
ALTER TABLE title_javdb_enrichment
  ADD COLUMN resolver_source TEXT,    -- 'actress_filmography' | 'code_search' | 'manual'
  ADD COLUMN confidence      TEXT,    -- 'HIGH' | 'MEDIUM' | 'LOW'
  ADD COLUMN cast_validated  INTEGER  -- 0/1: did write-time cast gate pass
```

**Confidence tiering rules:**
| Path | cast_json contains anchor actress? | confidence |
|---|---|---|
| actress_filmography | yes | HIGH |
| actress_filmography | no | LOW (logged as anomaly) |
| code_search | yes | MEDIUM |
| code_search | no | (refused — see 1C) |
| manual | n/a | HIGH |

**Backfill strategy** (decided 2026-04-28): UNKNOWN-by-default with a fast LOW-only initial scan.

- Every existing pre-provenance row gets `resolver_source = 'unknown'`, `confidence = 'UNKNOWN'`.
- A one-shot initial scan runs the cast-doesn't-contain-actress heuristic (mirrors `find_enrichment_cast_mismatches`) and stamps those rows `confidence = 'LOW'` immediately. This surfaces the suspicious rows day 1 without claiming confidence we haven't earned.
- The re-validation cron (2C) walks remaining UNKNOWNs over time, validating each against cached filmography and stamping the real value (HIGH if filmography confirms, LOW if not).

The asymmetry is intentional: cast-doesn't-contain is a strong "this is wrong" signal (~92% true positive in the 2026-04-28 incident), but cast-contains is a weak "this is right" signal — wrong-slug rows can coincidentally mention the actress under an alias variant. Stamping those MEDIUM would be overconfident; UNKNOWN until filmography-verified is honest.

#### 1C: Write-time cast-validation gate

Before any `INSERT` into `title_javdb_enrichment`:
- Actress-anchored path: log if cast doesn't contain anchor (HIGH→LOW), but still write — filmography is authoritative.
- Code-search path: **refuse to write** if cast doesn't contain *any* known linked actress. Route to a new `ambiguous` status in the staging queue. Surfaces in resolver UI.

#### 1D: Strict sentinel handling

Sentinel actresses (`is_sentinel=1`: Various, Unknown, Amateur) currently fall through to code-search via the resolver. Instead: short-circuit at the resolver entry point and route to ambiguous queue without an HTTP fetch. (No real javdb identity to anchor on.)

---

### Priority 2 — Observability

#### 2A: Library Health enrichment card

Surfaces:
- % of enrichment rows by confidence (HIGH / MEDIUM / LOW / UNKNOWN)
- count in `failed` / `no_match` / `ambiguous`
- last cleanup task timestamp + outcome
- filmography cache stats (entries, hit rate, oldest fetched_at)

#### 2B: Enrichment audit log

```sql
CREATE TABLE title_javdb_enrichment_history (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  title_id        INTEGER NOT NULL,
  changed_at      TEXT NOT NULL,
  reason          TEXT,            -- 'enrichment_runner' | 'cleanup' | 'manual_override'
  prior_slug      TEXT,
  prior_payload   TEXT             -- full JSON snapshot of the prior row
);
CREATE INDEX idx_tjeh_title ON title_javdb_enrichment_history(title_id);
```

Append-only. Triggered on `clearOne()` and on every fresh enrichment write.

**Retention** (decided 2026-04-28): **forever**. At realistic write rates (~5K changes/year × ~1 KB/row = ~5 MB/year), 10 years = ~50 MB. Negligible. The forensic value of long-tail debugging far outweighs the storage cost. If storage ever becomes a real concern, an opt-in `audit.prune_older_than <date>` MCP tool can be added later — don't preemptively design for it.

**Snapshot format** (decided 2026-04-28): full prior-row JSON in `prior_payload`. Captures every column (cast_json, release_date, grade, etc.) so any future question can be answered by `json_extract` on the snapshot without walking history to reconstruct state.

#### 2C: Re-validation pass — hybrid (event-driven + weekly safety net)

A re-validation pass is **pure SQL** (after 1A lands — every actress's filmography is already in `javdb_actress_filmography_entry`). The pass walks every enriched title, checks whether its stored slug appears in any of its slug-bearing actresses' cached filmographies, and:
- Stamps `confidence` for any UNKNOWN row (topic 2's gradual classifier)
- Drops HIGH → LOW for any row whose filmography no longer confirms; opens a `no_match` entry in `enrichment_review_queue`
- Counts drift since last pass; surfaces in Library Health if exceeded threshold

Cadence (decided 2026-04-28): **hybrid event-driven + calendar safety net**.

**Event triggers** (fire a full pass automatically — these catch ~all real drift sources at the moment they happen):
- After `sync` completes (new titles → may be enriched → re-check)
- After enrichment runner queue drains (just wrote N rows; verify)
- After `enrichment.clear_mismatched` task completes (just changed things; verify result)
- After manual override (`force_enrich_title`)

**Calendar safety net**: weekly, Sunday 3am (or wherever activity is lowest). Catches anything the event hooks miss — manual SQL inserts, javdb-side edits over time, future code regressions that bypass the event triggers. With well-instrumented events, the safety net is usually a no-op; that's fine.

Tunable in config: `enrichment.revalidationCron: "0 3 * * 0"` (cron expression). Default Sunday 3am UTC; user can flip to daily during high-churn periods.

---

### Priority 3 — UX

#### 3A: Triage queues (Step 8 of slug-verification proposal — expanded)

Decision (2026-04-28): backend stays unified (one `enrichment_review_queue` table with a `category` column); UI surfaces split by category so each has homogeneous actions.

```sql
CREATE TABLE enrichment_review_queue (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  title_id        INTEGER NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
  category        TEXT NOT NULL,        -- 'no_match' | 'ambiguous' | 'fetch_failed' | 'permanently_failed'
  detail          TEXT,                 -- per-category context (candidate slugs JSON for ambiguous, error msg for fetch_failed, etc.)
  first_seen_at   TEXT NOT NULL,
  last_seen_at    TEXT NOT NULL,
  resolved_at     TEXT,                 -- null = open
  resolution      TEXT                  -- 'matched' | 'manual_slug' | 'accepted_gap' | 'reassigned'
);
CREATE INDEX idx_review_open  ON enrichment_review_queue(category, resolved_at);
CREATE INDEX idx_review_title ON enrichment_review_queue(title_id);
```

**Categories and per-category UI:**
| category | what it means | resolver actions |
|---|---|---|
| `no_match` | Actress's filmography doesn't contain code | Suggest alternate actress (cross-actress lookup via 1A's columnar entries), accept javdb gap, fix on-disk attribution |
| `ambiguous` | Code-search returned a slug, write-time cast gate refused | Pick from candidate slugs, paste manual slug |
| `fetch_failed` | Transient HTTP/parse error | Retry, give up |
| `permanently_failed` | Definitive 404 | Mark resolved (one-click) |

**Multiple consumer surfaces** (intentional design — schema-decoupled-from-UI):
- **Tools → Step 8**: bulk triage, four sub-pages.
- **Curation flow**: inline badges/banners on title detail and actress detail screens — "this title needs triage" with a one-click jump to the resolver. Lets the user fix issues without context-switching out of curation.
- **Library Health card**: aggregate counts per category.
- **MCP / CLI**: `enrichment.list_review_queue --category=…` for scripting.

The unified table means new surfaces (e.g., a future "curation queue" view) just join to `enrichment_review_queue` — no schema migration, no new tables.

#### 3B: Force re-enrich tool

MCP + UI: "force enrich title X with slug Y, bypass resolver". Writes `resolver_source='manual'`. For rare javdb gaps the user wants to override.

---

## Open questions

These are starting points for discussion before locking the plan:

1. ~~**Storage format for filmography cache**~~ — **resolved 2026-04-28**: DB is source of truth (columnar entries table), idempotent JSON files for backup, sharded directory + on-demand snapshot zips. See 1A.
2. ~~**Confidence tiering for existing rows**~~ — **resolved 2026-04-28**: UNKNOWN-by-default + fast LOW-only initial scan; remaining UNKNOWNs classified gradually by re-validation cron. See 1B.
3. ~~**Ambiguous queue surface**~~ — **resolved 2026-04-28**: unified table backend, separated UI surfaces per category. Designed to support multiple consumers (utilities, curation flow, MCP). See 3A.
4. ~~**Audit log retention**~~ — **resolved 2026-04-28**: forever; full prior-row JSON snapshot in `prior_payload`. See 2B.
5. ~~**Cron cadence**~~ — **resolved 2026-04-28**: hybrid event-driven (after sync, queue drain, cleanup task, manual override) + weekly Sunday 3am safety net. See 2C.

---

---

## Impact on existing enrichment screens

The hardening is largely transparent to current screens, but a few flows change behavior. Captured here so we can anticipate the user-visible diff.

### Multi-hit handling (Q1 disambiguation pipeline)

When `searchByCode` returns more than one candidate slug, the resolver runs:

1. **Filmography cache lookup** — for each candidate, check `javdb_actress_filmography_entry`: does any cached actress have `(code, candidateSlug)` in her filmography? If yes → confirmed by authoritative source. `confidence=HIGH`, write. (This is the killer use case for 1A's columnar entries — cross-actress lookup is one indexed query.)
2. **Cast-page verification** — for candidates not in cache, fetch each candidate's title page (paying N HTTP requests), inspect cast. Exactly one candidate's cast contains a known actress → `confidence=MEDIUM`, write. Otherwise → ambiguous.
3. **Single-hit, no-cast-evidence** — search returned one slug but cast didn't verify → ambiguous, with that single candidate. User can confirm or override.
4. **Zero hits** → `permanently_failed`.

The pipeline guarantees **no enrichment is written without either filmography or cast confirmation**. The old "first hit, hope for the best" path is closed.

### Picker UI for `ambiguous` rows (decided 2026-04-28)

When the gate refuses and writes an `ambiguous` queue entry, the disambiguation pipeline has already fetched all N candidate title pages — snapshot that data into `enrichment_review_queue.detail` so the picker renders without re-fetching:

```json
{
  "code": "STAR-334",
  "candidates": [
    { "slug": "V9b7n", "title_original": "...", "release_date": "2021-03-11",
      "maker": "SOD Create", "cover_url": "https://...", "cast": [...] },
    { "slug": "J3kxQ", "title_original": "...", "release_date": "2014-08-21",
      "maker": "S1 NO.1 STYLE", "cover_url": "https://...", "cast": [...] }
  ],
  "fetched_at": "2026-04-29T03:14:00Z"
}
```

**Picker UI:**
- Side-by-side cards (one per candidate): cover image, title, release date, maker, full cast list (linked-actress names highlighted)
- Per-card action: **"Pick this"** → writes enrichment with `resolver_source='manual_picker'`, `confidence=HIGH`, copies snapshot data into `title_javdb_enrichment`
- **"None of these"** → marks `permanently_failed` or `accepted_gap`, no enrichment written
- **"Refresh candidates"** → re-runs the pipeline and snapshots fresh data (covers javdb updates)

**Cover images** load directly from `cover_url` (javdb CDN); no server-side mirroring needed unless rate-limited later.

**Where the picker lives** (intentionally multi-surface, mirroring 3A's design):
- **Tools → Step 8 → Ambiguous sub-page** (bulk triage)
- **Title-detail inline:** "🔍 Resolve enrichment ambiguity" badge opens the picker as a modal, returns to title-detail when done
- **Curation flow:** ambiguous badge on a title → one click opens picker without leaving the curation context

### Per-flow impact summary

| Screen / flow | Today | After hardening |
|---|---|---|
| Discovery → Most Recent | Slug from javdb feed (already canonical) | Unchanged. `confidence=HIGH`, `resolver_source=discovery_feed`. |
| Discovery → Actresses | Slug from feed | Unchanged. |
| Discovery → Title-driven enrich (actress library) | First search hit | Filmography lookup. `confidence=HIGH`. Faster + correct. |
| Pools (actress-rooted) | First search hit | Filmography lookup. `confidence=HIGH`. |
| Pools (label-rooted, no actress anchor) | First search hit | Q1 pipeline. Most HIGH/MEDIUM; some `ambiguous`. |
| Sentinel-actress titles (Various, etc.) | First search hit | Q1 pipeline. Most MEDIUM; some `ambiguous`. |
| Manual `enrich_title` | First search hit | Q1 pipeline. |

The user-visible difference: titles that previously enriched silently-and-wrongly now land in the `ambiguous` review queue if the gate can't verify them. They surface inline (curation badges) and in Tools → Step 8 with the picker UI.

### Migration concern

When the gate first runs over the existing ~1500 enriched rows (after rollout), the `ambiguous` queue may balloon. Today's heuristic detected 232; the strict gate could find more because it's stricter than the cast-presence heuristic. Plan time for the initial triage wave; the picker UI makes this tractable but not free.

---

## Out of scope

- Cross-source validation (DMM/FANZA cross-check) — separate effort
- Migration to a different metadata source — javdb stays primary
- Fully automated folder-misfile detection — Step 8 UI handles manually for now

---

## Forward-looking: Queue "Enrich" button (next big feature)

Captured 2026-04-28 — not part of this proposal's deliverables, but the hardening design must keep this future feature tractable.

### Vision

The Queue screen lists uncurated titles with proper folder structure but no metadata — just a product code (e.g., `ONED-123/`). Today the user manually adds cover, actresses, and metadata via the Unsorted Editor. The future feature adds an **"Enrich"** button that, given just the product code:

- Resolves to a javdb slug via the Q1 pipeline
- Auto-fills actresses (with reconciliation against existing canonical actresses)
- Downloads the cover image into the title folder
- Writes all enrichment metadata
- Leaves the user in the editor to review + override

This makes the editor a **power-assisted workflow** rather than fully manual, while preserving manual control where javdb is wrong or incomplete.

### Why hardening makes this safe

Without today's hardening, "one-click Enrich" with code-only input would be the **single biggest source** of the corruption we just cleaned up. Queue titles have no actress anchor → resolver falls through to code-search → original bug surface. Stamping silent enrichment from there at scale would be catastrophic.

With hardening:
- Code-only runs the Q1 pipeline (filmography cache → cast verify → ambiguous picker)
- Ambiguous cases trigger the **picker UI inline in the Queue editor** — same component as Tools → Step 8, no context switch. The Queue "Enrich" button becomes a consumer of the same pipeline, not a special case.
- Confidence stamps make it clear which fields were auto-filled and which were user-confirmed.

### Open design questions

#### Q1: Actress reconciliation — **resolved 2026-04-28: draft mode (revised)**

(Earlier "block-and-ask inline reconciler" approach was superseded by the Draft Mode pattern below. The reconciler still exists but operates on draft tables, not real ones. See "Draft Mode design" further down.)

**Cast validation rule** (locked, with multi-actress exception 2026-04-28):

Three modes, gated by how many stage_names javdb returned:

| stage_names returned by javdb | mode | rules |
|---|---|---|
| **0** | **sentinel-only** (default) | Resolved cast = exactly 1 sentinel (Amateur / Unknown / Various). No other path available. |
| **1** | **strict** | Must resolve to ≥1 real actress (linked or newly created). Sentinel forbidden. SKIP forbidden. |
| **≥2** | **multi-actress (relaxed)** | Either Path A (≥1 real actress, with optional SKIPs of individual stage_names) OR Path B (exactly 1 sentinel, all stage_names discarded). Mixing real + sentinel is **forbidden**. |

Per-cast-slot actions in the picker:
- `PICK` → link to existing actress
- `CREATE NEW` → make new actress with English name (last_name required, first_name optional for mononyms)
- `SKIP` → discard this stage_name, no `title_actresses` link (only available in multi-actress mode)

Cast-section actions:
- "Replace all with sentinel" → collapses to Path B (only available in multi-actress mode)

**Forensic preservation (no data loss):**
- `title_javdb_enrichment.cast_json` retains the full original javdb cast verbatim — including SKIPped stage_names — because enrichment data is immutable (Q3).
- `title_actresses` only contains the resolved entries.
- Clean separation: `cast_json` = what javdb said, `title_actresses` = what we chose to track. The user can re-enrich later and re-evaluate SKIPped stage_names if needed.
- Audit log captures SKIP actions as part of the promotion's history row, so we can answer "which stage_names did the user choose not to track on this title?"

**Mononym handling:**
- `actresses.first_name` is nullable; picker accepts `{last_name: "Aika", first_name: null}` → canonical = "Aika"

When javdb returns a cast member we can't match, **block enrichment write and open an inline reconciler** rather than auto-creating an actress. Workflow:

```
For each cast entry returned by javdb:
  1. javdb_slug match in javdb_actress_staging → link, done (HIGH confidence)
  2. stage_name / alias / canonical_name match (whitespace-stripped, fuzzy) → link, done (MEDIUM)
  3. NO MATCH → block enrichment, open inline reconciler:
     (a) Search existing actresses (typeahead, in case manual lookup finds her)
     (b) Create new actress — user enters English canonical name
     (c) Use sentinel — Amateur / Unknown / Various

Once all cast reconciled → write title_actresses + enrichment row.
New actresses (3b) get needs_review=1.
Sentinel fallbacks (3c) get an entry in enrichment_review_queue
  (category='unidentified_cast') so they can be reassigned later.
```

**Schema additions:**
```sql
ALTER TABLE actresses
  ADD COLUMN created_via   TEXT,            -- 'manual' | 'sync' | 'yaml_load' | 'enrichment_picker'
  ADD COLUMN created_at    TEXT,
  ADD COLUMN needs_review  INTEGER NOT NULL DEFAULT 0;
```

**New surface:** `actresses.list_needs_review` — list of auto-created actresses awaiting curator confirmation. Sortable by `created_at`. Confirm or merge-into-existing. Surfaces in Library Health.

**Reasoning:** auto-create with javdb-side stage names would salt the DB with low-quality, potentially-duplicate canonical rows. Block-and-ask trades user friction (~20-30 min for a batch of 50) for DB integrity. Acceptable because the alternative today is fully manual — any automation is net positive even with reconciliation interruptions.

**Edge cases handled:**
- Name collision on "create new" — warn + suggest linking to existing
- Fuzzy-match (Levenshtein over normalized forms) before declaring "no match" — reduces reconciler churn for cosmetic mismatches (full-width vs half-width katakana, stylization variants)
- Sentinel fallback creates `enrichment_review_queue` entry with javdb name + slug stored in `detail`, so a future reassignment is trivial
- Bulk Enrich (Q5) needs to either pause-per-unknown or sentinel-bomb-then-batch-reconcile — see Q5

#### Q2: Cover image fetch — **resolved 2026-04-28**

Compose existing `ImageFetcher` + `CoverWriteService` (both already used by Unsorted Editor). Synchronous, best-effort.

| sub-question | decision |
|---|---|
| (a) Existing cover present? | **Preserve** — autofill never overwrites a manually-placed cover |
| (b) Filename convention | `<code>.jpg` (e.g. `STAR-334.jpg`) — matches title folder identity |
| (c) Thumbnails (`thumbnail_urls_json`) | **Discard entirely** — neither fetch nor store. Drop `thumbnail_urls_json` population from enrichment write. |
| (d) Failure mode | **Soft warning** — enrichment row + metadata write atomically; cover fetch is best-effort. Failure surfaces in editor as "retry?" without blocking the Enrich completion. (Queue editor already tolerates incompleteness.) |
| (e) Rate limit | **Free fetch** — separate `HttpClient` for `c0.jdbstatic.com`, no rate limiter. Add a CDN limiter only if 429s appear in logs. |
| (f) Sync vs async | **Synchronous** — Enrich click handler waits for cover fetch (~2-3s typical). Async infra not worth it for short ops. |

Flow:
```
After enrichment row + metadata write succeeds:
  if enrichment.cover_url and !title.has_cover_at_base:
      try:
          bytes = imageFetcher.fetch(cover_url)        // free fetch, no limiter
          coverWriteService.write(title, bytes,
                                  filename = title.code + ".jpg")
      except FetchOrWriteError as e:
          log.warn(...)
          surface_in_editor("cover not fetched — retry?", error=e)
          // enrichment write itself stays committed
```

No new schema, no new queue category. Reuses existing services.

#### Q3: Tag application — **resolved 2026-04-28: enrichment data is immutable**

The original "auto-apply with editor visibility / X-to-remove" decision was superseded by a stronger rule: **all javdb-sourced data is absolute and immutable**, not just tags.

Three tag classes with distinct mutability:

| class | source | user can toggle? |
|---|---|---|
| **Enrichment tags** | from javdb during Enrich | **No** — immutable, locked |
| **Label/code-derived tags** | derived from `label_tags` + product code | **No** — auto-derived |
| **Intrinsic user tags** | user-applied | **Yes** — toggleable |

Editor renders enrichment + derived tags with a 🔒 lock icon (no X-to-remove); intrinsic tags rendered as today.

**Reasoning:** allowing edits to javdb-sourced data lets the user diverge from javdb's view, defeating the entire premise of having javdb as the source of truth. If javdb is wrong, the user re-enriches (replacing the draft) — they don't override field-by-field. Enforces consistency across re-enrich cycles.

#### Q4: Provenance after manual edit — **resolved 2026-04-28 (simplified)**

Because enrichment data is immutable (Q3), there is no "user edited a field after auto-enrich" state to track. `resolver_source` becomes a pure provenance field.

**`resolver_source` values** on `title_javdb_enrichment`:
| value | meaning |
|---|---|
| `auto_enriched` | Came from a draft promotion (user-validated Enrich result) |
| `manual_picker` | User picked from ambiguous-resolution picker |
| `discovery_feed` | Slug from javdb's recent-releases scrape (already canonical) |
| `actress_filmography` | Background runner via filmography lookup |
| `code_search` | Background runner via code-search + cast verify |
| `cleanup_cleared` | Cleanup task cleared this row, awaiting re-enrich |

**Audit log** still captures every write (per topic 2B's forever-retention rule). Volume is small — only resolver-driven writes occur, since user edits to enrichment fields are forbidden.

**Re-enrich is wholesale replacement:** a new draft is created, user validates, promotion replaces the existing real row entirely. No per-field merge logic. Simpler than the earlier conservative-auto-merge model and consistent with the immutability rule.

**Diff-and-pick UI** (side-by-side comparison of old real row vs new draft) is a useful affordance during validation — user can see what's changing before they hit Promote. Deferred to feature scoping; not load-bearing for v1.

#### Draft Mode design — **adopted 2026-04-28 as the foundational pattern**

Bulk Enrich (and ultimately single Enrich too) does not write directly to canonical tables. It writes to **mirror "draft" tables** that the user must validate before promotion.

**Schema:**
```sql
CREATE TABLE draft_titles (                  -- mirrors `titles`, columns nullable
  id                       INTEGER PRIMARY KEY AUTOINCREMENT,
  code                     TEXT NOT NULL,
  -- ... (mirror of titles columns, all nullable except code) ...
  last_validation_error    TEXT,
  updated_at               TEXT NOT NULL     -- optimistic-lock token
);

CREATE TABLE draft_actresses (
  javdb_slug               TEXT PRIMARY KEY, -- shared globally across drafts (Q3 decision)
  stage_name               TEXT,             -- from javdb (immutable once first written)
  english_first_name       TEXT,             -- user-supplied; nullable for mononyms
  english_last_name        TEXT,             -- user-supplied; required for "create new"
  link_to_existing_id      INTEGER REFERENCES actresses(id),  -- when user picks "link to existing"
  created_at               TEXT NOT NULL,
  updated_at               TEXT NOT NULL,    -- optimistic-lock token
  last_validation_error    TEXT
);

CREATE TABLE draft_title_actresses (
  draft_title_id           INTEGER NOT NULL REFERENCES draft_titles(id) ON DELETE CASCADE,
  javdb_slug               TEXT NOT NULL REFERENCES draft_actresses(javdb_slug),
  resolution               TEXT NOT NULL,    -- 'pick' | 'create_new' | 'skip' | 'sentinel:<actress_id>'
  PRIMARY KEY (draft_title_id, javdb_slug)
);
-- draft_actresses ref-counted via draft_title_actresses; orphan rows removed when last reference drops.

CREATE TABLE draft_title_javdb_enrichment    -- mirrors enrichment row
CREATE TABLE draft_title_enrichment_tags     -- mirrors enrichment tag links
```

**Concurrency** (Q3 decision 2026-04-28): no concurrent edit support. Both `draft_titles` and `draft_actresses` carry an `updated_at` token; writes include the token they read, and the server rejects stale writes with a "draft modified elsewhere — reload" error. No locking, no merge UI; user reloads the draft.

**Ref-counting on discard:** Discarding draft #N drops only its `draft_title_actresses` rows (cascade) — shared `draft_actresses` rows survive while other drafts still reference them. The `draft_actresses` row is reaped when the last `draft_title_actresses` referencing it is gone.

**Stage_name conflict** (rare): if two drafts return slightly different `stage_name` for the same `javdb_slug`, first-write-wins; subsequent draft writes confirm the existing row without overwriting `stage_name`.

**Auto-link to existing real actresses** (Q4 decision 2026-04-28):

| match type | trigger | action |
|---|---|---|
| **Slug match** (deterministic) — `javdb_actress_staging.javdb_slug` already maps to an existing `actresses.id` | At draft creation **and** at pre-flight validation | Worker writes `draft_actresses.link_to_existing_id` directly. Cast slot renders as "✓ Mana Sakura (resolved)" — no user action needed. |
| **Name match** (heuristic) — user types a name in picker that matches an existing canonical or alias | Editor suggests inline | Banner: "Likely match: [name]. [Link] [Override]" — user confirms. |

**Override affordance:** every auto-linked cast slot has an "Unlink and pick different" action. Auto-link must not trap the user — the picker is always one click away.

**Cross-session persistence:** draft_actresses with `link_to_existing_id` stay in the table until promotion. User can leave and return; the draft's auto-link decision survives. Promotion is the big event that consumes drafts.

**Edge cases handled:**
- Stale slug staging (slug mapping but real actress deleted): auto-link fails at pre-flight; downgrade to name-match suggestion or force user resolution.
- Existing actress has a different stale slug: slug-match fails; name match still surfaces as suggestion.
- Multiple name-match candidates: editor renders a chooser inline.
- Slug-match becomes available between creation and validation (e.g., user enriched another title meanwhile): pre-flight upgrades the draft to use the slug match.

**Sync interaction** (Q5 decision 2026-04-28):

Sync writes to real `titles` as today (folder identity, NULL metadata). Drafts are exclusively for **user-initiated mutations** (Enrich, manual Edit). Important rule: **editing always creates a draft.** When the user opens any title — curated or uncurated — for editing, the system clones the current state into a draft and the user works against the draft. Real tables are never directly mutated by user edits.

- Background EnrichmentRunner writes directly to real tables, gated by write-time validation (Q1c). Ambiguous results go to `enrichment_review_queue`. Drafts are not involved in the autonomous path.
- Sync writes folder-identity facts (`titles.code`, `title_locations`) directly to real tables. Sync does not interact with drafts.

**Sync rediscovering a title that already has an active draft:** sync only updates real-table sync facts (`last_seen_at`, locations). The draft is left untouched, but a flag is set on the draft (`draft_titles.upstream_changed = 1`) and the editor surfaces a banner: "The underlying title was re-synced after this draft started. Discard and restart? [Discard] [Continue anyway]".

**Sync deletes a title:** `draft_title.title_id REFERENCES titles(id) ON DELETE CASCADE` — drafts have no value beyond editing the underlying title. If the title is gone, the draft has nothing to promote into.

**Garbage collection (decision 2026-04-28):**
- Drafts have a 30-day lifetime. If `draft_titles.updated_at` is older than 30 days and the draft has not been promoted or discarded, it's GC'd automatically by a background sweep. Acts as a safety net for forgotten work and prevents draft accumulation.
- `draft_actresses` rows whose ref-count (number of `draft_title_actresses` rows referencing them) drops to zero are GC'd in the same sweep. This handles cleanup after individual draft discards naturally.
- GC sweep runs daily (or weekly — TBD; small operation).

**Discovery feed scope** (Q6 decision 2026-04-28): drafts only exist for titles we own (i.e., a real `titles` row exists). Discovery feed remains browse-only for unowned titles; favorites continue to work as today and are unrelated to drafts. No "orphan drafts" before the file is on disk. Reasoning: drafts are a curation surface — without something to promote into, drafts have nothing to do.

- Acquisition lag (user scouted via Discovery 6 months ago, finally acquires + syncs the file): no auto-Enrich. Sync creates the real `titles` row with NULL metadata; user invokes Enrich deliberately. Favorites stays as a separate wishlist concept.
- Cover prefetch from Discovery: deferred — not a correctness concern.
- Existing favorite mechanism: unchanged. Drafts and favorites remain separate concepts for separate purposes.

**Audit log scope on drafts** (Q7 decision 2026-04-28): drafts do not generate audit log rows. The audit log captures **changes to canonical state only**.

- Mid-draft edits (typing names, picking actresses, retrying covers) are not logged — drafts are transient by definition.
- A **promotion event** writes one comprehensive history row capturing:
  - Prior real-row state (`prior_payload`, per topic 2B)
  - New canonical state (the post-promotion row contents)
  - **`promotion_metadata` JSON** — the decisions made during draft validation: cast resolutions (`pick` / `create_new` / `skip` / `sentinel`), resolved actress IDs, the resolution path (`manual_picker` / `auto`). Cheap forensic context for "what choices led to this state."
- **Discards are silent** — no audit log row when a draft is discarded. Common workflow event with no canonical-state impact; logging would clutter the forensic record.
- **Per-session undo within a draft** (typing-level undo for the user's convenience): out of scope for this proposal; revisit if it becomes a UX pain point.

**Lifecycle:**
1. User clicks Enrich (single or bulk) on a Queue title.
2. Worker populates `draft_*` tables — fills as much as possible from javdb.
3. Title surfaces in Queue editor with a DRAFT badge.
4. User opens the draft, fills any gaps (resolves ambiguous slugs via picker, picks/creates actresses, retries cover, etc.).
5. User clicks **Validate** — pre-flight check (runs *before* opening the promotion transaction; cleaner error UX than relying on DB constraint failures):
   - Cast rule check (mode-by-stage-name-count table)
   - All actress slots resolved (no unresolved javdb stage_names without a SKIP)
   - New actresses have non-empty English last_name
   - Validation failures surface in the editor inline; promotion is not attempted.
6. User clicks **Promote** (or "Validate & Promote" combined). Promotion is **one DB transaction** wrapping all writes:
   - INSERT new `actresses` rows for newly-created drafts; bind generated IDs back
   - INSERT/UPDATE `titles` from `draft_title`
   - INSERT real `title_actresses` rows
   - INSERT/UPDATE `title_javdb_enrichment` from `draft_title_javdb_enrichment`
   - INSERT real `title_enrichment_tags`
   - Append audit log row(s) for the promotion (per Q4)
   - DELETE all draft rows for this title
   - Single COMMIT. Any failure → full ROLLBACK; real tables untouched, drafts intact for retry.
7. **On promotion failure**: write the error message to `draft_titles.last_validation_error` so the user can see what went wrong. Draft stays put for retry.
8. **No bulk Validate-and-Promote.** Decision 2026-04-28: every draft requires individual review and Promote click. Bulk Enrich populates many drafts at once, but each draft must be opened, validated, and promoted one at a time. Enforces the "user must see every change before it lands in real tables" principle.
9. Or user clicks **Discard** → drops all draft rows associated with this title; title returns to Queue, ready for re-enrich.

**Editor under Draft Mode is a constrained resolution surface, not a free-form editor:**

| field | editability in draft |
|---|---|
| Product code (immutable from folder) | read-only |
| Title metadata (`title_original`, `release_date`, `maker`, `series`, etc.) | **read-only** — from javdb, frozen |
| Cast slot resolution (existing actress link) | **action required** when ambiguous: PICK from existing or "create new" via picker |
| New actress English name | editable — last_name (required), first_name (optional, supports mononyms) |
| Sentinel selection | editable when the cast slot has no stage_name |
| Cover image | limited: clear / refetch only |
| Enrichment tags | read-only / 🔒 locked |
| Label/code-derived tags | read-only / auto-derived |
| Intrinsic user tags | editable — toggle freely |

**Properties of the pattern:**
- Real `titles` and `actresses` tables only ever hold validated, complete entities.
- "Discard" is cheap and clean — no DELETE-cleanup cascade through real tables.
- Enrichment immutability (Q3) is enforced at validation time, not after-the-fact.
- Re-enrich on an already-promoted title creates a NEW draft alongside the real row; user reviews diff during validation, promotion replaces the real row.
- Manual editing without Enrich works on drafts the same way — Enrich is just one input source.

**This pattern replaces** the earlier `actresses.needs_review` flag and `actresses.list_needs_review` surface from the original Q1 design. Drafts ARE the review surface. Cleaner.

**Refactor scope:** the existing Queue title editor is rewritten to operate on `draft_*` tables. User noted (2026-04-28) the existing editor has not been used yet, so the retrofit cost is acceptable.

#### Q5: Bulk Enrich — **resolved 2026-04-28**

Bulk Enrich is a Utilities task that walks a filtered set of Queue titles and populates drafts (via the separated pipeline below). Drafts handle all reconciliation/ambiguity work asynchronously; the bulk task itself only fetches and writes drafts.

**Architecture (Q5b decision — separated pipeline):**

```
                  ┌──────────────────┐
                  │ JavdbSlugResolver│
                  │ JavdbClient      │   ← shared parsing/resolution components
                  │ JavdbExtractor   │     (one path through javdb data,
                  │ JavdbProjector   │     no drift between bulk and runner)
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
   ...                        ...
```

Shared components: resolver (with filmography cache 1A), extractor, projector, HTTP client + rate limiter, write-time validation gate (1C).
Bulk-specific: a new `DraftEnrichmentRepository` (mirrors `JavdbEnrichmentRepository` but writes draft tables) and the task class itself.

**Where it runs (Q5a):** backend Utilities task with phases, cancellable, status checkable. Same pattern as `enrichment.clear_mismatched`. User can navigate away during the run; banner in the Queue screen shows progress.

**Rate-limit interaction (Q5b):** task pauses the background `EnrichmentRunner` for its duration (start of task → unpause on completion/cancel). Bulk gets 100% of the rate budget while running; the runner's pending queue waits. Same rate limiter, no separate budget, no risk to the rate-limit experiment.

**Scope — "Enrich all visible" (Q5c):**
- Acts on the user's currently-filtered Queue view
- Excludes titles already curated (have validated `title_javdb_enrichment`)
- Excludes titles with an active draft — to re-curate, user must explicitly Discard the draft first
- Button label shows eligible count (e.g. "Enrich 47 titles")
- Confirmation modal lists exclusions explicitly: "47 titles will be enriched. (3 already have drafts, 2 already curated — excluded.) Background runner will be paused. Proceed?"

**Cancel behavior (Q5d):**
- Cancel signal: in-flight fetch is allowed to complete → its draft writes → task tears down. Avoids wasting rate budget already spent.
- Drafts written so far persist regardless of cancel.
- No formal resume — re-running Bulk Enrich is idempotent because the exclusion rules naturally skip already-drafted titles. The user simply re-clicks the button to continue where they left off.
- Pause is not a separate concept — only cancel exists. Cancel + re-run = pause + resume in effect.

**Concurrency:** only one Bulk Enrich task at a time; `TaskRunner` already enforces single-task-per-utility semantics.

### Compatibility checks against the hardening design

The hardening design holds up:
- **Filmography persistence (1A)** — directly accelerates Queue Enrich (no per-click filmography refetch)
- **Provenance (1B)** — stamps `resolver_source` so the editor can show "auto-enriched" vs "manual" badges
- **Write-time gate (1C)** — refuses bad enrichment, falls through to picker. The Queue "Enrich" button can't write corruption.
- **Ambiguous picker (3A)** — already designed multi-surface; "in-line in Queue editor" is just another consumer
- **Audit log (2B)** — captures Enrich → user-edit transitions for forensic value

The only new infrastructure the future feature would need: **cover-image fetcher + actress reconciliation**. Both are well-bounded and independent.

---

## Estimated effort

| Item | Sessions |
|---|---|
| 1A filmography cache | ~1 |
| 1B provenance columns + backfill | ~0.5 |
| 1C write-time gate | ~0.5 |
| 1D sentinel short-circuit | ~0.25 |
| 2A health card | ~1 |
| 2B audit log | ~0.5 |
| 2C re-validation cron | ~0.5 |
| 3A no_match resolver UI | ~1-2 |
| 3B force re-enrich tool | ~0.25 |

Total: 5-7 sessions, sequenced as listed.
