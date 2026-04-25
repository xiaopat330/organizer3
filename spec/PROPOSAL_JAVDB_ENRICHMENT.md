# Proposal: javdb Enrichment ("Discovery")

**Status:** Approved design, pending implementation
**Date:** 2026-04-25
**Supersedes:** prior draft of this document
**Related:** `reference/javdb_enrichment_notes.md` (POC findings)

---

## Vision

A new utility — **Actress Discovery** — uses javdb.com as a centralized enrichment source for the curated portion of the library. Two enrichment dimensions:

- **Actress info** — name variants (kanji), avatar, social handles, javdb title count. Fills profile gaps without expensive AI scraping.
- **Title info** — release date, duration, maker, publisher, series, javdb rating, **tags**, full female cast. Tags are a strategic goal: collecting and aggregating javdb's tag taxonomy across the library will let us normalize categorization at scale.

Discovery is selective. Enrichment is reserved for elite actresses — Goddess / Superstar / Popular tiers (derived from title count). The vast majority of actresses in the DB will never be enriched.

The current Actress Data screen (loads YAML profiles from AI research) is **renamed to Actress Import**. The new Actress Discovery screen takes its slot in the Utilities menu.

---

## Architecture overview

```
┌──────────────────────────────────────────────────────┐
│  Discovery Screen (web UI)                           │
│   ↓ enqueue                                           │
│  javdb_enrichment_queue   ←── Background Runner       │
│                                  ↓ HTTP (rate-limited)│
│                                javdb.com               │
│                                  ↓                     │
│                         <dataDir>/javdb_raw/*.json     │
│                                  ↓ project              │
│                       javdb_title_staging              │
│                       javdb_actress_staging            │
│                                  ↓ auto-promote         │
│                       (canonical fields, when empty)    │
└──────────────────────────────────────────────────────┘
```

Five major components, all new (except the HTTP client/parsers from the POC):

1. **`javdb_enrichment_queue`** — durable work queue.
2. **Background runner** — long-lived thread, models on the existing background thumbnail generator. Independent of `TaskRunner` (does not block / is not blocked by Utilities atomic tasks).
3. **Staging tables** — `javdb_title_staging`, `javdb_actress_staging`. Hold projected fields + path to raw JSON snapshot on disk.
4. **Raw snapshots on disk** — `<dataDir>/javdb_raw/{title|actress}/{slug}.json`. Comprehensive structured extraction; HTML is *not* persisted.
5. **Discovery screen** — actress explorer with letter/tier filters, per-actress detail panel with Profile / Titles / Conflicts / Errors tabs.

---

## Queue and runner

### Schema

```sql
CREATE TABLE javdb_enrichment_queue (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  job_type        TEXT NOT NULL,        -- 'fetch_title' | 'fetch_actress_profile'
  target_id       INTEGER NOT NULL,     -- title_id or actress_id
  actress_id      INTEGER NOT NULL,     -- denormalized; set on every job (= owning actress for title jobs)
  status          TEXT NOT NULL,        -- 'pending' | 'in_flight' | 'done' | 'failed' | 'cancelled'
  attempts        INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TEXT NOT NULL,        -- ISO; runner only picks rows where this <= now
  last_error      TEXT,
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL
);
CREATE INDEX idx_jeq_claim    ON javdb_enrichment_queue(status, next_attempt_at);
CREATE INDEX idx_jeq_actress  ON javdb_enrichment_queue(actress_id, status);
```

`resolve_actress_slug` is **not** a job type. Slug discovery is a passive side-effect: every `fetch_title` parses the female cast (slug + kanji name pairs) and writes/updates `javdb_actress_staging` rows. Scheduling enrichment for an actress with N titles enqueues N `fetch_title` jobs plus, optionally, a single `fetch_actress_profile` once her slug is known.

### Runner behavior

- Single background thread, started on app boot, **on by default**.
- Loop: claim 1 row → run → sleep ~1s → repeat.
- Claim atomically: `UPDATE … SET status='in_flight', updated_at=now WHERE id = (SELECT id … WHERE status='pending' AND next_attempt_at <= now LIMIT 1)`.
- Crash recovery on boot: rows stuck in `in_flight` for > 5 min → reset to `pending`.
- Rate limit lives in `HttpJavdbClient` (token bucket, ~1 req/sec), not in the runner. Ad-hoc UI lookups share the limit.
- Independent of `TaskRunner`. Models on the background thumbnail generator. Does not consume the Utilities atomic-task slot.

### Failure handling

| Failure | Action |
|---|---|
| Network / 5xx | Backoff (1m → 5m → 30m), max 3 attempts, then `failed` |
| 429 | Pause runner for 5 min globally; do not burn the attempt count |
| 404 (title not on javdb) | Permanent `failed` with `last_error='not_found'`, no retry |
| Parse error in projector | Permanent `failed`; surfaces in Errors tab (signals a parser bug, not a data problem) |

### Cancel / pause

- **Per-actress cancel** — `UPDATE javdb_enrichment_queue SET status='cancelled' WHERE actress_id=? AND status='pending'`. In-flight jobs run to completion.
- **Cancel All** (header button) — same, no `actress_id` filter. Confirm modal.
- **Pause** (header toggle) — global flag (settings row). Runner checks before each claim. Visible state badge in header.

### Logging

- `INFO  javdb: fetching {code}` per job start.
- `WARNING javdb: failed {code}: {error}` on permanent failure.
- No per-step chatter, no heartbeat.

---

## Staging

### Tables

```sql
CREATE TABLE javdb_title_staging (
  title_id            INTEGER PRIMARY KEY,    -- FK titles.id ON DELETE CASCADE
  status              TEXT NOT NULL,          -- 'fetched' | 'not_found' | 'fetch_error'
  javdb_slug          TEXT,                   -- NULL when not_found
  raw_path            TEXT,                   -- relative to dataDir; NULL when not_found
  raw_fetched_at      TEXT,
  -- projected fields (re-derivable from raw JSON):
  title_original      TEXT,                   -- Japanese title from javdb header
  release_date        TEXT,                   -- yyyy-mm-dd
  duration_minutes    INTEGER,
  maker               TEXT,
  publisher           TEXT,
  series              TEXT,
  rating_avg          REAL,
  rating_count        INTEGER,
  tags_json           TEXT,                   -- ["3p","Solowork",…] (English; locale=en)
  cast_json           TEXT,                   -- [{"slug":"ex3z","kanji":"麻美ゆま","gender":"F"},…]
  cover_url           TEXT,
  thumbnail_urls_json TEXT
);

CREATE TABLE javdb_actress_staging (
  actress_id          INTEGER PRIMARY KEY,    -- FK actresses.id ON DELETE CASCADE
  javdb_slug          TEXT NOT NULL,
  source_title_code   TEXT,                   -- which title's cast first gave us this slug
  status              TEXT NOT NULL,          -- 'slug_only' | 'fetched' | 'fetch_error'
  raw_path            TEXT,                   -- NULL until fetch_actress_profile runs
  raw_fetched_at      TEXT,
  -- projected fields:
  name_variants_json  TEXT,                   -- ["麻美由真","麻美ゆま"]
  avatar_url          TEXT,
  twitter_handle      TEXT,
  instagram_handle    TEXT,
  title_count         INTEGER                 -- javdb's count of her filmography
);
CREATE UNIQUE INDEX ON javdb_actress_staging(javdb_slug);
```

Two states for an actress staging row:

1. **`slug_only`** — created as a side-effect of title enrichment. Has slug + `source_title_code`. No profile data yet.
2. **`fetched`** — `fetch_actress_profile` has run. Raw JSON written, projected fields populated.

### Raw JSON on disk

- Path format: `<dataDir>/javdb_raw/title/{slug}.json`, `<dataDir>/javdb_raw/actress/{slug}.json`.
- Stored as **structured JSON, not raw HTML**. Extraction strips presentation noise (~5–10 KB per title vs ~50–100 KB raw HTML).
- **Extract liberally** — pull every identifiable field from the page even if not currently projected. The JSON file is a faithful snapshot in stable shape; future field additions can re-project from disk without re-fetching javdb.
- `staging.raw_path` holds the path **relative to dataDir** so dataDir relocations don't break references.

### Two layers of "parsing"

| Layer | Trigger | Re-runnable from cache? |
|---|---|---|
| **Extraction** (HTML → JSON) | At fetch time, in the runner | No — needs javdb refetch |
| **Projection** (JSON → typed columns) | At fetch time, also via one-shot "re-project all" command | Yes — reads from disk only |

If a parser bug is fixed: re-project from disk, no network. If javdb adds a new field we want: refetch (rare).

### Hybrid import: staging IS canonical for new fields

Existing canonical fields where staging *promotes* into the live tables:

| Canonical field | Promote rule |
|---|---|
| `actresses.stage_name` | When `actresses.stage_name IS NULL`, set from any `cast_json` entry matching her slug. Auto-promote. |
| `titles.title_original` | When `titles.title_original IS NULL`, set from `javdb_title_staging.title_original`. Auto-promote. |
| `titles.release_date` | When `titles.release_date IS NULL`, set from `javdb_title_staging.release_date`. Auto-promote. |

All other staged fields (duration, rating, series, maker, publisher, tags, avatar, socials, name variants, title count, cover URL, thumbnails) **never get promoted**. They live in staging and are read from staging by the Discovery screen and any future enriched views.

When canonical is populated and staged disagrees → **conflict**. Surfaced via the Conflicts tab; no overwrite.

---

## HTTP client

The POC client (`HttpJavdbClient`, `JavdbSearchParser`, `JavdbTitleParser`, `JavdbActress`, `JavdbStageLookup`, `JavdbFetchException`) is already present in `com.organizer3.javdb`. Hardening for production:

- Token-bucket rate limiter (~1 req/sec, configurable in `organizer-config.yaml` under `javdb:`).
- 429 detection → signal to runner to globally pause for 5 min.
- Headers already set: browser User-Agent, `Cookie: age_check_done=1; locale=en`.
- Reuse a single `HttpClient` instance.

---

## Discovery screen UX

```
┌─ HEADER ────────────────────────────────────────────────────────┐
│ Actress Discovery        [▶ Running] [⏸ Pause] [Cancel All]    │
│                          Queue: 3 in-flight • 47 pending        │
├──────────────┬────────────────────────┬──────────────────────────┤
│ FILTER       │ Asami, Yuma     165 ✓28│  ← detail panel goes here │
│ A B C D …    │ Aoi, Sora        78 ✓5 │                           │
│              │ ...                    │                           │
│ ─────        │                        │                           │
│ Favorites    │                        │                           │
│ Bookmarked   │                        │                           │
│ Goddess      │                        │                           │
│ Superstar    │                        │                           │
│ Popular      │                        │                           │
│              │                        │                           │
│ Sort:        │ FILTER:                │                           │
│ ◉ name       │  ☐ has conflicts       │                           │
│ ○ count      │  ☐ has failures        │                           │
│ ○ recent     │  ☐ enriched only       │                           │
└──────────────┴────────────────────────┴──────────────────────────┘
```

### Left rail

- **A–Z letter chips** (model on the duplicates utility's existing pattern)
- **Tier chips**: Favorites, Bookmarked, Goddess, Superstar, Popular
  - Goddess / Superstar / Popular are derived from title count thresholds (no schema field needed)
- **Sort radio**: by name / by title count / by most-recent-activity

### Middle: actress list

Each row: `Last, First   {totalTitles}   ✓{enrichedCount}` (progress chip omitted if zero).

A small colored dot if the actress has unresolved conflicts or failed jobs.

### Right: detail panel

Tabs: **Profile · Titles · Conflicts · Errors**

**Profile tab:**
- Avatar, name variants, Twitter, Instagram, javdb title count
- "Source title" badge (which title gave us her slug)
- "Re-fetch profile" hidden behind a small icon (per "no re-fetch unless error/corrupt" policy)
- Empty state when no enrichment yet: "No enrichment data yet. **[Enrich All Titles]**" front and center; tabs greyed.

**Titles tab:**
- Table: code | title | release date | status (✓ enriched / ⏳ pending / ⏵ in-flight / ✗ not_found / ! failed) | actions
- Default sort: release date desc. Column sort headers.
- Filter: all / unenriched / failed / not_found
- Inline row expansion shows tags + rating prominently, then duration/maker/publisher/series

**Conflicts tab:**
- Each row: title code, our DB attribution, javdb's attribution, "ignore" / "fix in DB" actions
- No separate `javdb_conflicts` table — derived by query.

**Errors tab:**
- Failed jobs for this actress with `last_error` text and a Retry button.

### Primary action

**[Enrich All Titles]** big button in the detail panel.
- Confirms with: "Enqueue N titles? Estimated ~M minutes at 1 req/sec."
- Enqueues `fetch_title` for every title without staging.
- If actress has no slug yet, also enqueues `fetch_actress_profile` to fire after the first title resolves her slug.

---

## Tag aggregation (future)

Tags are stored per-title in `javdb_title_staging.tags_json` (raw English strings, no taxonomy mapping). A future **Tag Library** utility can materialize from staging without schema changes:

```sql
SELECT json_each.value AS tag, COUNT(*)
FROM javdb_title_staging, json_each(tags_json)
GROUP BY tag ORDER BY COUNT(*) DESC;
```

That utility — and any AI-assisted mapping of javdb tags into our tag taxonomy — is out of scope for v1. Goal of v1 is to start collecting the data.

---

## Backups

`javdb_*_staging` tables and `<dataDir>/javdb_raw/` are included in standard backups. The JSON files compress well; total footprint for 50 elite actresses × ~80 titles is on the order of 40 MB (compressed: a few MB). If size becomes an issue we revisit.

---

## Code layout

```
com.organizer3.javdb/                     (existing, from POC)
  HttpJavdbClient, JavdbClient, JavdbSearchParser,
  JavdbTitleParser, JavdbStageLookup, JavdbActress, JavdbFetchException

com.organizer3.javdb.enrichment/          (new)
  EnrichmentQueue                          - queue table operations
  EnrichmentRunner                         - background thread
  JavdbExtractor                           - HTML → comprehensive JSON
  JavdbProjector                           - JSON → staging columns
  JavdbStagingRepository                   - staging tables + raw_path I/O
  AutoPromoter                             - apply promote rules to canonical
  ConflictDetector                         - query for staging-vs-canonical mismatches
  EnrichmentService                        - high-level entry point used by web layer
```

Frontend: new JS module for the Discovery screen, following the state-factory pattern.

---

## Implementation milestones

| M | Scope |
|---|---|
| **M1** | Schema (queue + staging) + `EnrichmentRunner` + `fetch_title` job + extractor + projector + `JavdbStagingRepository`. CLI command to enqueue a single actress's titles and observe via logs. No UI. |
| **M2** | Discovery screen — read-only browse of staged data. List, filters, Profile + Titles tabs (no actions). |
| **M3** | Enrichment actions wired up. "Enrich All Titles" button, `fetch_actress_profile` job, header pause/cancel, retry from Errors tab. |
| **M4** | Conflicts tab + `AutoPromoter` rules (auto-promote `stage_name` and `release_date` when empty). |
| **M5** | Tag Library utility (separate; deferred). |

Each milestone is independently shippable.

---

## Open items deferred to implementation time

- **Schema migration version number** — picks up next `applyVN()` slot in `SchemaUpgrader`.
- **Goddess / Superstar / Popular thresholds** — exact title-count cutoffs to be set by user during M2.
- **SSS/SS/S/A/B/C ↔ rating range map** — trivial bucket function; scope when surfacing portfolio grades on the Discovery screen.
- **Settings flag for global pause** — column on a tiny `app_settings` table, or reuse an existing settings store if one exists.
