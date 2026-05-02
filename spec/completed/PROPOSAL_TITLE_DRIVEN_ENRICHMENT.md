# Proposal: Title-Driven Enrichment

**Status:** SHIPPED — `ProfileChainGate` (sentinel-aware, ≥3-title threshold) + `actresses.is_sentinel` + Discovery Titles/Collections tabs live.
**Date:** 2026-04-27
**Extends:** `spec/PROPOSAL_JAVDB_ENRICHMENT.md`

---

## Vision

The current Discovery utility is **actress → titles**: pick an actress, enrich her filmography, profile-fill her entry. It works extremely well for the curated set.

This proposal adds a parallel **title → (maybe actress)** flow for titles that aren't owned by a single curated actress:

- **Recently added titles** across the library (driven by `title_locations.added_date`)
- **Sort-pool titles** (volumes with `structureType = sort_pool`)
- **Collection titles** (volumes with `structureType = collections`, multi-actress)

These flows share the existing queue, runner, staging tables, and HTTP client. What changes is (a) the queue admits jobs without a single owning actress, (b) the post-job profile-fetch chain runs through eligibility gates, and (c) the Discovery screen gets new tabs that drive enqueue from title lists rather than actress lists.

There is no "Enrich All" button anywhere in this flow. Lists can be unbounded; enrichment is always a manual selection.

---

## Two flows, one runner

The existing actress-driven flow assumes a single owning actress per queue row and auto-chains a profile fetch on first slug discovery. Both assumptions break for the new sources:

| Flow | Queue row `actress_id` | Profile chain |
|---|---|---|
| Actress-driven (existing) | Always set; user opted in for this actress | Unconditional auto-chain |
| Title-driven (new) | NULL — title isn't tied to one actress | Gated (see §3) |

The runner does not need to know the difference. The `fetch_title` job is identical. What differs is what happens *after* the fetch lands: actress-driven jobs trust the user's intent; title-driven jobs run cast members through eligibility gates before enqueueing any profile fetch.

---

## Schema changes

### `javdb_enrichment_queue`

```sql
ALTER TABLE javdb_enrichment_queue
  -- actress_id becomes nullable
  RENAME TO javdb_enrichment_queue_old;

CREATE TABLE javdb_enrichment_queue (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  job_type        TEXT NOT NULL,
  target_id       INTEGER NOT NULL,
  actress_id      INTEGER,                       -- now NULLABLE
  source          TEXT NOT NULL DEFAULT 'actress', -- 'actress'|'recent'|'pool'|'collection'
  status          TEXT NOT NULL,
  attempts        INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TEXT NOT NULL,
  last_error      TEXT,
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL
);
INSERT INTO javdb_enrichment_queue
  SELECT id, job_type, target_id, actress_id, 'actress', status, attempts,
         next_attempt_at, last_error, created_at, updated_at
  FROM javdb_enrichment_queue_old;
DROP TABLE javdb_enrichment_queue_old;

CREATE INDEX idx_jeq_claim   ON javdb_enrichment_queue(status, next_attempt_at);
CREATE INDEX idx_jeq_actress ON javdb_enrichment_queue(actress_id, status);
CREATE INDEX idx_jeq_source  ON javdb_enrichment_queue(source, status);
```

`source` exists for telemetry, queue-tab grouping, and so the post-job chain knows whether to apply gates. It is *not* a job-routing key.

### `actresses.is_sentinel`

```sql
ALTER TABLE actresses ADD COLUMN is_sentinel INTEGER NOT NULL DEFAULT 0;
```

Backfilled from a fixed name list at migration time. Match is canonical-only:

```sql
UPDATE actresses SET is_sentinel = 1
 WHERE LOWER(stage_name) IN ('various','unknown','amateur');
```

Aliases are deliberately excluded — alias rows are noisier and a real performer must never be flagged sentinel because someone added a stray alias. If a sentinel-shaped name appears only as an alias, that's an alias-cleanup problem, not a sentinel-detection problem.

The flag is read by every gate, by the UI (sentinel chip styling), and by any future logic that needs to distinguish "real performer" from "scanning artifact."

**Sentinels are also hidden from the existing Actresses tab.** They are not real performers; surfacing them as enrichable invites the wrong action. Their `slug_only` rows in `javdb_actress_staging` (created as a side effect of cast parsing on collections titles) still exist on disk, but the actress list filters out `is_sentinel = 1` rows. No other Actresses-tab behavior changes.

These are the *only* schema changes. Recent / pools / collections are queries, not data.

---

## Eligibility gates

When a `fetch_title` job completes successfully, the runner walks `cast_json`. For each cast entry:

1. Match `kanji` against `actresses.stage_name` and `actress_aliases.alias_name` (existing logic). Write/update `javdb_actress_staging` with `slug` + `source_title_code` (existing behavior).
2. **If the queue row's `source = 'actress'`:** auto-chain `fetch_actress_profile` for the matched actress (existing behavior, unchanged).
3. **If `source != 'actress'`:** apply the three gates. Auto-chain only if **all three** pass:
   - **Existence** — cast entry maps to a real `actresses.id` (already implicit; no map → skip).
   - **Sentinel** — `actresses.is_sentinel = 0`.
   - **Threshold** — actress has ≥ 3 titles, counted as `SELECT COUNT(*) FROM title_actresses WHERE actress_id = ?`. All credits count, including collection appearances where she's one of N — being credited 3 times is the signal regardless of context. Configurable in `organizer-config.yaml` under `javdb.profile_chain_min_titles` (default 3, global, applies across all title-driven sources).

If a profile fetch is already pending/in-flight/done for the actress, do not enqueue a duplicate.

Gates apply only to the **profile chain**, never to the title fetch itself. Any title is enqueueable from any new tab. Title-level enrichment is cheap, and tags/release date/cast slugs are valuable regardless of who's in the cast.

---

## Conflict predicate generalization

Today's predicate joins against `titles.actress_id`. For multi-actress titles, that field is NULL. Replace with a `title_actresses` membership check:

```sql
SELECT t.id, t.code, ts.cast_json
FROM javdb_title_staging ts
JOIN titles t ON t.id = ts.title_id
WHERE ts.status = 'fetched'
  AND NOT EXISTS (
    SELECT 1
    FROM json_each(ts.cast_json) je
    JOIN javdb_actress_staging a ON a.javdb_slug = json_extract(je.value, '$.slug')
    JOIN title_actresses ta ON ta.actress_id = a.actress_id AND ta.title_id = t.id
    WHERE a.actress_id IS NOT NULL
  );
```

A title is in conflict only when *no* credited actress in our DB matches *any* cast slug from javdb. Single-actress titles behave identically to today (since `title_actresses` is always populated alongside `titles.actress_id` in the sync pipeline).

Sentinel actresses do not generate conflict matches — their `title_actresses` rows exist but `javdb_actress_staging.actress_id` is never written for them (they're not real performers; we don't track their slugs).

---

## UX — two new tabs

Top-level tab structure of the Discovery screen becomes:

| Tab | Existing? | Purpose |
|---|---|---|
| **Actresses** (renamed from "Enrich") | Yes | Actress-driven enrichment, unchanged |
| **Titles** (new) | No | Recent + sort-pool titles, single-actress or no-actress |
| **Collections** (new) | No | Multi-actress titles from collections volumes |
| **Queue** | Yes | Unchanged |

### Why Titles and Collections are separate tabs

Recent and Pool rows have identical shape (one title, zero or one credited actress, simple action). They differ only in source filter and are merged into one tab with a chip selector:

```
Source: [ All recent ] [ Pool: qnap_jav ] [ Pool: qnap_av ] …
```

Collection rows are visually different — multiple cast chips per row, each with its own eligibility badge. Action consequences differ too: enqueuing a collection title may trigger several profile chains. Distinct UI for distinct semantics.

### Common interaction pattern (both new tabs)

- Paginated table, 50 rows/page.
- Server-side filter: hide rows where `javdb_title_staging.status = 'fetched'`. Rows already in queue (`pending`/`in_flight`) show a status badge instead of a checkbox. Rows with prior `failed` / `not_found` jobs *are* selectable — `EnrichmentQueue.enqueueTitle` already deletes terminal-state rows on re-enqueue and inserts a fresh `pending`, so re-selection from this UI is a clean retry with no extra logic needed. If the prior failed row was actress-driven and the user re-queues from a title-driven tab, the new row overwrites with the new `source` value; the new context wins.
- Per-row checkbox.
- Sticky footer: `N selected → [Enqueue N]`. Hard cap of 100 per click.
- Convenience action: "Select first 25 unenriched on this page."
- No "Select all" across pages. No "Enrich all."

### Titles tab specifics

Columns: `☐ | code | title | actress | volume | added | status`.

**Source filter chip strip** (radio-style, single-select):

```
Source: [ All recent ] [ Pool: qnap_jav ] [ Pool: qnap_av ] [ Pool: … ]
```

- One chip active at a time. `All recent` is the default.
- Pool chips are built from the volumes config — every volume with `structureType = sort_pool` gets a chip.
- A pool chip with zero unenriched titles is greyed out but still visible (the absence is informative).
- Sort mode is tied to the active chip: `All recent` sorts by `title_locations.added_date desc` (latest); a specific pool sorts by `titles.code asc` for linear scanning.
- Multi-select was considered and deferred. Single-mode-per-view keeps sort semantics unambiguous and selection state visually clear. Revisit if cross-pool union becomes a real workflow.

Other behavior:

- Default sort: `added_date desc` when source is "All recent"; `code asc` for a specific pool.
- Row dot in `actress` column indicating profile-chain eligibility (informational only, doesn't gate enqueue):
  - ✓ — eligible (real, non-sentinel, ≥3 titles)
  - ◌ — title-only enrichment (sentinel, below threshold, or unmapped)
- Empty `actress` column when no credit (rare — should only happen for malformed scan results).

### Collections tab specifics

Columns: `☐ | code | title | cast | volume | added | status`.

- `cast` is a chip strip — one chip per credited actress. Each chip shows name + eligibility badge:
  - ✓ green — eligible
  - ◌ grey — real but ineligible (below threshold)
  - ✗ red — sentinel (Various / Unknown / Amateur)
- Tooltip on chip explains badge ("3 titles required, has 1").

The badges make the gate policy visible at the moment of action.

---

## Code layout

Reuse `com.organizer3.javdb.enrichment.*` for runner / staging / projector / promoter. Extend in place:

- `EnrichmentQueue` — accept nullable `actress_id` + `source`; existing `enqueueTitleFetch(actressId, titleId)` keeps `source='actress'`; new `enqueueTitleFetch(source, titleId)` for title-driven flows.
- `EnrichmentRunner` — post-job hook checks `source` to decide whether to apply gates before enqueueing profile chain. New helper `ProfileChainGate` encapsulates the three checks.
- `JavdbStagingRepository` — no change.
- New `TitleDiscoveryService` (web layer) — backs the two new tabs. Methods:
  - `listRecentUnenriched(filter, offset, limit)` — title_locations + left join staging. "Most recently added" uses `MAX(title_locations.added_date)` per title (latest activity across locations, not first appearance).
  - `listPoolUnenriched(volumeId, offset, limit)` — same, scoped to a sort_pool volume.
  - `listCollectionUnenriched(offset, limit)` — joins `title_actresses` to surface cast.
  - `enqueueTitles(source, ids)` — bulk enqueue with cap + dedupe.

Frontend: extend `utilities-javdb-discovery.js` with two new top-level tab views following the state-factory pattern already in use there.

---

## Implementation milestones

| M | Scope |
|---|---|
| **M1** | Schema migration (queue nullable + `source`, `actresses.is_sentinel` + sentinel backfill; runs with the enrichment runner stopped per existing migration pattern). `ProfileChainGate` + extended runner post-job hook. Generalized conflict predicate. Hide sentinels from the existing Actresses tab. No new UI. Unit + integration tests. |
| **M2** | Titles tab: list, source filter chips, selection, enqueue. Backed by `TitleDiscoveryService`. |
| **M3** | Collections tab: list, cast chips with eligibility badges, selection, enqueue. |
| **M4** | Rename "Enrich" → "Actresses" in nav and copy. Help text / tooltips. |

Each milestone is independently shippable. M1 alone delivers the schema and gate logic, queueable from a CLI or test for verification.

---

## Config additions (`organizer-config.yaml`)

```yaml
javdb:
  # ... existing keys ...
  profile_chain_min_titles: 3      # gate threshold for title-driven flows
  enqueue_batch_cap: 100           # hard cap on a single Enqueue N click
```

---

## Out of scope

- **Backfilling profile chains when an actress later crosses the threshold.** If she has 2 titles today and gets a 3rd next month, no automatic catch-up — slug rows already exist; the user can manually promote from the Actresses tab.
- **Conflicts surfacing per-actress for collections titles.** Phase 2 — current Conflicts tab (under Actresses) keeps its current single-actress framing; multi-actress conflicts are queryable but not UI-surfaced yet.
- **Re-projection of legacy queue rows.** Existing rows are migrated with `source='actress'` and continue to behave as before. No backfill of `source` from heuristics.
- **Sentinel detection beyond the fixed name list.** If a new sentinel-like name pattern appears (e.g., "Group", "Multiple"), it's added to the migration list manually.

---

## Open items deferred to implementation time

- Schema migration version slot in `SchemaUpgrader` (next available `applyVN()`).
- Exact column widths and chip styling for the Collections cast strip.
- Whether the Titles tab's "actress" eligibility dot should also appear on the Actresses tab (consistency vs. clutter).
