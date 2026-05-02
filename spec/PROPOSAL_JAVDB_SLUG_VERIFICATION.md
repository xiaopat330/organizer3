# Proposal: javdb Slug Verification — Stop Picking the Wrong Title

**Status:** Steps 1–5 + 7 shipped to main. Step 4 (filmography cache) shipped beyond original scope — in-memory cache, DB-backed L2 persistence, drift detection, and backup writer (see commits 13a7349, 795f63e, 757ff40, 536b8d2). Step 6 = run the existing `RecomputeActressRatingCurveTask` once cleanup settles (operational, no code work). Step 8 (no_match resolver UI) is the only outstanding code work, gated on whether cleanup leaves a meaningful triage pile.
**Scope:** Fix the cast-mismatch enrichment bug discovered 2026-04-28; clean up ~232 contaminated rows + opportunistically validated extras; recompute the actress rating curve; add a triage UI for unresolvable rows.

## Decisions locked in (Q&A 2026-04-29)

| # | Question | Decision |
|---|---|---|
| 1 | Cleanup ordering | Deploy fix → run cleanup → re-enqueue. New code is the gate. |
| 2 | Behavior when filmography lookup misses the code | Clear the row, mark `status='no_match'`, surface for triage. Bad enrichment > missing enrichment. |
| 3 | Folder-misfile vs slug-mismatch ambiguity | Defer auto-detection. Build a resolver tool/UI (Step 8) for the user to triage `no_match` rows manually. |
| 4 | Rate-limit budget for cleanup | Run at current settings (~8h overnight drain). Don't relax bursts. |
| 5 | Cleanup scope | Re-process the 232 detected mismatches, **plus opportunistic re-validation** of every other enriched title for actresses whose filmography we fetch. Catches latent mismatches with no extra javdb traffic. |

---

## Problem

When we enrich a title from javdb, we sometimes attach data for the **wrong title** — same product code, different studio/era, different cast.

### Concrete example

Title `STAR-334` is filed under Mana Sakura in our library (`/stars/Mana Sakura/Mana Sakura - … (STAR-334)`). After enrichment:

- `title_javdb_enrichment.javdb_slug = V9b7n`
- `title_original = "オシッコに向かう途中に即ハメ！… 永野いち夏"` — Ichika Nagano
- `release_date = 2021-03-11`

The slug is for an Ichika Nagano title released by SOD Create. javdb's STAR series prefix has been reused across decades by different studios (S1 No.1 Style + SOD Create + others). javdb's search-by-code endpoint returns the union — and we pick the first hit without verifying.

### Detected scope (2026-04-28 measurement)

Detection query: `cast_json` for the chosen slug does not contain the actress's `stage_name` (whitespace-stripped) or any of her `alternate_names_json`.

**232 mismatched rows** across the library. Top offenders:

| Actress | Mismatched | Library titles |
|---|---|---|
| Mana Sakura | 75 | 178 |
| Yuma Asami | 18 | 194 |
| Yua Aida | 16 | 80 |
| Honoka | 11 | 46 |
| Yui Hatano | 10 | 616 |
| Rei Amami | 10 | 28 |
| Yua Mikami | 9 | 127 |
| Akane Nagase | 8 | 8 |
| Rio | 7 | — |
| Riku Minato | 7 | 142 |

Spot-check on 8 random Mana Sakura mismatches: 8/8 confirmed wrong by manual inspection of folder names vs javdb cast. The bug is real, not noise.

### Knock-on effects

The wrong-slug data leaks into multiple downstream surfaces:

- `titles.title_original`, `titles.release_date`, `titles.notes` — visible in title cards/detail
- `titles.grade` (when `grade_source = 'enrichment'`) — wrong rating curve input
- `actresses.computed_grade` — the new actress rating curve (shipped 2026-04-28) is contaminated. Mana Sakura's A+ score was built partly on 75 titles that aren't hers.
- Title cover overlays (rating, grade) — shown to user

---

## Root cause

`JavdbSearchParser.parseFirstSlug()` takes the first `<a href="/v/{slug}">` from the search results page. There's no cast verification.

```java
// src/main/java/com/organizer3/javdb/JavdbSearchParser.java
public Optional<String> parseFirstSlug(String html) {
    Element link = Jsoup.parse(html).selectFirst("a[href^='/v/']");
    ...
}
```

The fix needs to anchor the slug choice on something other than search-result order.

---

## Proposed fix

**Switch from code-driven to actress-driven slug resolution.**

javdb's data is actress-rooted: each title slug is uniquely associated with the cast in its credits. So if we anchor on the actress (not on the code), the slug becomes unambiguous.

### New flow when the title has a known actress link

1. Title `STAR-334` is linked to Mana Sakura via `title_actresses`.
2. Look up Mana Sakura's javdb actress slug (already cached in `javdb_actress_staging.javdb_slug`).
3. Fetch her **actress page** from javdb (`/actors/{actressSlug}`) — one request returns a paginated list of her full filmography.
4. Parse the page into a `Map<productCode, titleSlug>`.
5. For `STAR-334`, look up the slug in *her* map. The result is the slug for Mana Sakura's STAR-334 specifically — guaranteed correct.
6. Fetch that title slug.

This is also more efficient: one filmography fetch resolves up to ~hundreds of her titles in one shot vs. N per-code searches.

### Fallback when no actress is linked

For unsorted titles (`actress_id IS NULL` and no `title_actresses` row), we still need code-based search. Add a **post-fetch validation gate**: after fetching the chosen slug's detail page, check whether its cast contains *any* actress we know about. If not, mark the staging row `status = 'ambiguous'` and don't promote to `title_javdb_enrichment`. The user can manually triage from the staging table.

### Multi-actress titles

If a title has multiple actresses linked (collab title), at least one must match the candidate slug's cast. Use any of them for the actress-driven lookup. Once a valid slug is found, no further verification needed — javdb's cast list is authoritative.

---

## Implementation steps

### Step 1: Detection query as a reusable health check

Add `mcp__organizer3__find_enrichment_cast_mismatches` (or a Utilities Library Health card) that runs the diagnostic query. Returns the same list this proposal is grounded in. Lets us measure progress without ad-hoc SQL.

**Files:** new MCP tool entry; new query method in `JavdbStagingRepository`.

### Step 2: Add filmography fetch + parse

**First action:** capture a real javdb actress page with the running app (one fetch via the existing `HttpJavdbClient.fetchActressPage()`) and save the HTML to `src/test/resources/javdb/actress_filmography.html`. The current `extractActress()` only reads profile metadata — we don't yet know the markup for the filmography list or pagination.

Extend `ActressExtract` (or add a new `ActressFilmographyExtract`) to capture `List<{productCode, titleSlug}>`. Add `parseFilmography()` to a new `JavdbActressPageParser` (or extend `JavdbExtractor`).

Pagination caveat: javdb actress pages are paginated (~40 titles per page). Need to either:
- Fetch all pages eagerly (`?page=1..N` until empty)
- Or fetch lazily — pages until the code is found or exhausted

Eager fetch is simpler and only happens once per actress per refresh window. Defer caching considerations to step 4. Plan: implement eager fetch first; revisit if rate-limit budget becomes a real problem in practice.

**Files:** `JavdbActressPageParser` (new), `ActressFilmographyExtract` (new), tests against fixture HTML.

### Step 3: Refactor enrichment runner to actress-anchor when possible

In `EnrichmentRunner` (or wherever code→slug resolution happens):

```
if title has actress and actress has javdb slug:
    filmography = fetchOrCacheFilmography(actressSlug)
    candidate = filmography.get(productCode)
    if candidate is null:
        # actress doesn't have this code in her javdb filmography
        mark row 'no_match_in_filmography', skip
    else:
        proceed with candidate slug
else:
    # fallback path — code search + post-fetch cast validation
    candidate = parseFirstSlug(searchByCode(productCode))
    detail = fetchTitlePage(candidate)
    if detail.cast intersects knownActressNames:
        proceed
    else:
        mark row 'ambiguous', skip
```

**Files:** `EnrichmentRunner`, possibly new `JavdbSlugResolver` to keep the logic isolated and testable.

### Step 4: Cache filmographies

Filmography fetches per actress are heavy (multi-page). Cache in `javdb_raw/actress/{slug}/filmography.json` with a TTL (e.g., 30 days). Re-fetch only when stale or explicitly requested.

For new titles imported by sync: enrichment can use the cached filmography immediately. The cache invalidates only on TTL expiry or manual flush.

**Files:** new repo method or just disk-based cache; TTL config in `JavdbConfig`.

### Step 5: Cleanup task — clear bad enrichment rows (with opportunistic re-validation)

New Utilities task `enrichment.clear_mismatched`:

1. Run the detection query → seed the affected-title list.
2. Group affected titles by linked actress.
3. For each affected actress: fetch (or use cached) filmography. While in hand, **opportunistically re-validate ALL her enriched titles** against her filmography map — any slug not matching her filmography becomes a freshly-detected mismatch and joins the cleanup batch (no extra javdb traffic since we already have the filmography).
4. For each row in the (expanded) cleanup batch:
   - Delete the `title_javdb_enrichment` row.
   - Clear `titles.title_original`, `titles.title_english`, `titles.release_date`, `titles.notes`.
   - Clear `titles.grade` and `titles.grade_source` **only if `grade_source = 'enrichment'`** (preserve `manual` and `ai`).
5. Re-enqueue the title for enrichment via the new actress-driven path.

**AutoPromoter interaction**: confirmed safe. `AutoPromoter.promoteFromTitle` only writes `WHERE field IS NULL`, so when the new enrichment lands it will refill cleanly. The stage-name promoter already gates on `cast_json.slug = javdb_actress_staging.javdb_slug`, so wrong-slug cast entries can never propagate as a new stage_name.

This is a destructive cleanup — must run `dryRun` first, summary shown to user (X mismatched + Y opportunistic), confirm before running.

**Files:** new task class; uses existing `javdb_enrichment_queue`.

### Step 6: Recompute the actress rating curve

After step 5 settles, run `RecomputeActressRatingCurveTask` to rebuild grades on clean data. (This is the existing task — no code changes.)

### Step 7: Backfill missing actress filmographies

Some actresses still lack a `javdb_actress_staging.javdb_slug` (the prereq for actress-anchored lookup). The existing actress-profile fetch path already populates this — make sure it runs before enrichment runs for any of her titles. If it can't be resolved (rare), fall back to the code-search-with-cast-validation path (Step 3 fallback).

**Sentinel actresses** (Various / Unknown / Amateur, `is_sentinel=1`) have no real javdb identity, so they'll never have a slug. They naturally fall through to the code-search-with-validation path; `ProfileChainGate` already prevents wasted profile fetches for them. No special-casing needed in the new resolver.

### Step 8: Resolver tool/UI for `no_match` triage

Some titles will be marked `status='no_match'` — javdb doesn't have the code in the linked actress's filmography. Possible causes:

- **Folder mis-file** — the file is actually a different actress's title, mis-filed under the wrong actress's folder
- **javdb gap** — old or obscure title that javdb doesn't index
- **Alias issue** — actress credited under an alias we don't have linked
- **Wrong code on disk** — typo in the folder/file name

User-facing UI to triage:

- New Utilities pane (or Library Health card): list of `no_match` rows
- Each row shows: code, current actress link, folder path, the actress's filmography link, "search javdb for this code" link
- Actions per row:
  - **"Try other actress"** — pick from a list of actresses whose filmography contains this code (searches across cached filmographies). One-click reassign + re-enrich.
  - **"Manual slug entry"** — paste a javdb slug, force-enrich with that slug
  - **"Mark resolved (no javdb data)"** — accept that this title has no javdb data and stop nagging
  - **"Open folder"** — jump to the on-disk location to check the file

**Files:** new task spec for "find candidate actresses" (cross-references cached filmographies for a code), new Utilities route + frontend module.

This is non-trivial UI work — could be deferred to a follow-up branch if Steps 1-7 land first and the `no_match` list is small enough to ignore short-term.

---

## Testing

- **Unit:** `JavdbActressPageParser.parseFilmography()` against captured fixture HTML for 2-3 known actresses (Mana Sakura, Yuma Asami, Sora Aoi). Verify pagination handling.
- **Integration:** seed an in-memory DB with a known mismatch (STAR-334 → V9b7n with Ichika Nagano cast); run the resolver with Mana Sakura's filmography; verify it picks the correct Mana Sakura slug.
- **Regression:** add a fixture-driven test that the post-fetch cast-validation gate catches a mismatch when the fallback path runs.
- **Cleanup task:** real-DB integration test that the destructive cleanup only nulls `enrichment`-source grades, never `manual`/`ai`.

---

## Resolved / locked

- **Eager vs. lazy filmography fetch** — eager (one-shot all pages). A top actress may have 600+ titles / ~15 pages; we accept the cost in exchange for simpler logic. Revisit if rate-limit becomes a real problem.
- **Intra-filmography code collisions** — verified none in current data. `(actress_id, code)` is unique across all enriched titles, so the `Map<code, slug>` model is well-defined.
- **`no_match_in_filmography` handling** — clear the row, mark `no_match`, surface in resolver UI (Step 8).
- **TTL on filmography cache** — 30 days, behind `JavdbConfig.filmographyCacheTtlDays` so it's tunable.

---

## Out of scope (future work)

- Automatic folder-misfile detection (cross-reference `no_match` codes against ALL cached filmographies and propose the likely correct actress). Manual triage via Step 8 UI for now.
- Reverse cleanup: titles that javdb attributes to an actress *we* haven't linked yet (slug whose cast contains an actress we don't know about). Could auto-create the `title_actresses` row.
- Cross-validation against DMM/FANZA for double-check on edge cases.

---

## Estimated effort

- Steps 1-2: ~1 session (detection tool + parser + fixture tests; need a real fetch first to capture markup)
- Step 3: ~1 session (resolver refactor + tests)
- Step 8 (resolver UI): ~1-2 sessions; can ship after Steps 1-7 if the `no_match` list is small
- Steps 4-5: ~1 session (cache + cleanup task)
- Steps 6-7: incremental (run existing tooling)

Total: 3-4 focused sessions. Cleanup itself is gated on rate-limited re-enrichment (~232 rows × current rate of 0.33/s ≈ 12 minutes of fetch time, but actress filmography fetches add 1 per affected actress).
