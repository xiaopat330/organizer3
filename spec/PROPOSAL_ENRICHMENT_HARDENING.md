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
