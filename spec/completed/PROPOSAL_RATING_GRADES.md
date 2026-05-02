# Title Rating Grades from Enrichment

## Status: Ready for implementation

## Goal

Surface javdb-enriched user ratings (`rating_avg` / `rating_count`) as letter grades on the existing `Actress.Grade` scale (`SSS … F`), so titles with real user-rating data get a sortable, filterable, color-coded grade badge in the UI. Lay the groundwork for future sort/filter features without building those features yet.

## Background — read first

- `spec/FUNCTIONAL_SPEC.md`, `spec/IMPLEMENTATION_NOTES.md`, `spec/USAGE.md` — general orientation.
- `spec/PROPOSAL_JAVDB_ENRICHMENT.md` and `spec/ENRICHMENT_TAG_OPS.md` — the enrichment pipeline this hooks into.

### Data already in place

- `title_javdb_enrichment` (per-title) carries `rating_avg REAL` and `rating_count INTEGER`. Indexed on `rating_avg` (`idx_tje_rating_avg`).
- `Actress.Grade` enum (`src/main/java/com/organizer3/model/Actress.java`) defines all 14 grades and their display strings. **Use this enum — do not introduce a new one.**
- `titles.grade` column already exists and is currently populated by hand / by AI-derived actress research. `Title.grade` is `Actress.Grade`.
- `web/TitleSummary.java` already exposes `String grade`, and the JS layer already renders it via `gradeBadgeHtml(t.grade)` in `cards.js` and `title-detail.js`. CSS for all 14 `data-grade=` values is in `src/main/resources/public/css/details.css` (`.grade-badge[data-grade="SSS"]` etc.).
- Schema upgrader is at version 27. Add a v28 migration.

### Decisions already made

These are settled — implementation should follow them, not relitigate.

1. **Single `grade` column with provenance.** Add `titles.grade_source` (`'enrichment' | 'ai' | 'manual'`). Enrichment writes overwrite `grade` only when `grade_source != 'manual'`. Existing non-null `titles.grade` rows are backfilled to `grade_source = 'ai'`.
2. **Enrichment wins.** When enriched data is available, the enrichment-derived grade replaces any AI-derived grade for that title. The AI grade for the title is not preserved separately.
3. **Same scale, no new grades.** Use the existing `Actress.Grade` enum.
4. **Vote-weighted score, not raw `rating_avg`.** Apply Bayesian shrinkage toward the global mean, then map the shrunken score to a grade via population-anchored cutoffs. This handles "4.8 from 3 voters vs 4.8 from 800 voters" naturally.
5. **Curve recomputes on enrichment writes** (per-title stamp uses cached curve; full recompute + re-stamp runs at end of an enrichment batch and via a manual admin action). Grade values may shift over time as the population grows. That's fine — they're derived, not user-set.
6. **No actress grade derivation in this pass.** Actress-level grades stay where they are. A future feature may aggregate enriched title grades into an actress grade — out of scope here.
7. **Display:** card shows just the grade badge (existing behavior). Title detail shows `S+ (4.62)` or similar with vote count nearby. Ungraded titles render no badge on cards; "Not rated" on title detail.

## Architecture

### New schema (migration v28)

```sql
ALTER TABLE titles ADD COLUMN grade_source TEXT;
UPDATE titles SET grade_source = 'ai' WHERE grade IS NOT NULL;

CREATE TABLE rating_curve (
    id                  INTEGER PRIMARY KEY CHECK (id = 1),  -- single-row config
    global_mean         REAL    NOT NULL,
    global_count        INTEGER NOT NULL,
    min_credible_votes  INTEGER NOT NULL,
    cutoffs_json        TEXT    NOT NULL,
    computed_at         TEXT    NOT NULL  -- ISO-8601
);
```

Schema initializer (`SchemaInitializer.java`) must also create both for fresh databases. Mirror the migration faithfully.

### Bayesian shrinkage

```
weighted = (v * R + m * C) / (v + m)
```

- `R` = `rating_avg` for the title
- `v` = `rating_count` for the title
- `C` = `global_mean` from the curve (mean of `rating_avg` across all enriched titles where `rating_count > 0`)
- `m` = `min_credible_votes`. **Default: 50.**

Titles with low `v` get pulled toward the global mean. Titles with `v >> m` use essentially their own score.

### Curve cutoffs

The grade is determined by where `weighted` falls relative to the population of weighted scores. Use **anchored percentiles** (right-skewed because user-supplied ratings self-select upward):

| Percentile (population of weighted scores) | Grade |
|---|---|
| ≥ p99 | SSS |
| ≥ p97 | SS |
| ≥ p92 | S |
| ≥ p82 | A+ |
| ≥ p70 | A |
| ≥ p55 | A- |
| ≥ p40 | B+ |
| ≥ p25 | B |
| ≥ p15 | B- |
| ≥ p8  | C+ |
| ≥ p4  | C |
| ≥ p2  | C- |
| ≥ p1  | D |
| < p1  | F |

`cutoffs_json` persists the 13 numeric boundary values (the weighted-score thresholds for each grade) plus the grade label list, in a stable JSON shape. Suggested:

```json
{
  "version": 1,
  "boundaries": [
    {"min_weighted": 4.71, "grade": "SSS"},
    {"min_weighted": 4.58, "grade": "SS"},
    ...
    {"min_weighted": 0.00, "grade": "F"}
  ]
}
```

Sort descending by `min_weighted`; lookup is "first row whose `min_weighted <= weighted`".

### New code

All under `src/main/java/com/organizer3/rating/` (new package):

- `RatingCurve` — immutable value object holding `globalMean`, `globalCount`, `minCredibleVotes`, `boundaries: List<Boundary>`, `computedAt`. JSON serialization for the `cutoffs_json` column lives here.
- `RatingScoreCalculator` — pure stateless service. `gradeFor(double ratingAvg, int ratingCount, RatingCurve curve) -> Optional<Actress.Grade>`. Returns empty for null/zero inputs. Also exposes `weightedScore(...)` for the title-detail display.
- `RatingCurveRepository` (interface) + `JdbiRatingCurveRepository` (impl). `find()` returns `Optional<RatingCurve>`; `save(RatingCurve)` upserts the single row.
- `RatingCurveRecomputer` — orchestrates the full recompute. Reads all `(title_id, rating_avg, rating_count)` from `title_javdb_enrichment` where `rating_avg IS NOT NULL`. Computes `globalMean`, then for each row computes `weighted`, sorts the population, picks the 13 percentile cutoffs, persists the new `RatingCurve`, then re-stamps every enriched title's `(grade, grade_source)` via `TitleRepository.setGradeFromEnrichment(...)` **only where `grade_source != 'manual'`**. Returns a small summary (counts updated / skipped).
- `EnrichmentGradeStamper` — single-title path. Given a title id and its rating data, looks up the cached curve, computes a grade, and writes `(grade, grade_source='enrichment')` if `grade_source != 'manual'`. Called from the enrichment write path.

### Repository changes

`TitleRepository` (`src/main/java/com/organizer3/repository/TitleRepository.java`):

```java
/** Stamp a title's grade from enrichment. No-op if grade_source = 'manual'. */
void setGradeFromEnrichment(long titleId, Actress.Grade grade);

/** Manual user override. Always wins. */
void setGradeManual(long titleId, Actress.Grade grade);

/** Clear an enrichment-derived grade (e.g. when enrichment data is removed). */
void clearEnrichmentGrade(long titleId);
```

The existing `setGrade` (used by AI-derived flows) should write `grade_source = 'ai'` going forward, or be left alone if no AI-grade-write path is currently exercised at runtime — pick the simpler option that doesn't regress current callers. Audit existing callers and adjust call sites accordingly.

`TitleSummary` projection queries (`TitleBrowseService`, `ActressBrowseService`, `SearchService`, anywhere else `t.grade` is selected) do not need new joins — `titles.grade` is already in the projection. They will surface enrichment-derived grades as soon as the column is populated. Add `grade_source` to the projection if it's useful for tooltip/source-icon UX (optional this pass).

### Hooks into the enrichment pipeline

- **Per-title:** in the projector that writes `title_javdb_enrichment` (`JavdbProjector`), after the row is upserted, call `EnrichmentGradeStamper` if `rating_avg != null`. If no curve exists yet, skip — the first batch recompute will stamp it.
- **Batch end:** wherever the enrichment batch driver exists (find via the queue runner — `javdb_enrichment_queue` consumer), after a batch completes call `RatingCurveRecomputer.recompute()`. Debounce reasonably — once per batch, not once per title. If unclear where the natural batch boundary is, ask before guessing.
- **Manual:** add a Tools-menu entry (mirror the existing utility-task pattern — see `mcp.tools` and the Utilities task plumbing) that runs `RatingCurveRecomputer.recompute()` and reports counts. Keep it under the existing "atomic Utilities task" rule (one at a time).

## UI

### Title cards (standard + actress-detail)

No changes required — `gradeBadgeHtml(t.grade)` is already rendered. CSS already covers all 14 grades. Verify visually that enrichment-derived grades render correctly across all card surfaces after the backend lands.

### Title detail screen

Update `src/main/resources/public/modules/title-detail.js` so that when a title has both a grade and enrichment rating data, the grade row shows the raw score and vote count alongside the badge:

- `Grade: <S+ badge> (4.62 · 312 votes)` when graded + enriched.
- `Grade: <badge>` when graded but not enriched (e.g. residual AI grade — won't happen post-cutover but handle it).
- `Grade: Not rated` when no grade.

Add `ratingAvg`, `ratingCount`, and (optional) `gradeSource` to `TitleSummary` and to the title-detail projection. Format `ratingAvg` to two decimals; `ratingCount` with thousands separators if ever large.

### Color coding

Already done in CSS. No change.

## Testing

Tests are mandatory — see `feedback_testing` and `feedback_testing_consistency` in user memory. Use real in-memory SQLite for repository tests; Mockito for service unit tests.

- `RatingScoreCalculatorTest` — pure math:
  - Null / zero inputs return empty Optional.
  - Bayesian shrinkage: known `(R, v, m, C)` produces expected `weighted`.
  - Boundary cases: weighted score exactly at a cutoff lands in the higher grade.
  - Stable mapping for typical values (4.5 with v=200 → some A-tier grade given a representative curve).
- `RatingCurveRecomputerTest` — synthetic population:
  - Build N titles with hand-picked `rating_avg` / `rating_count`, run recompute, assert `globalMean` and that percentile cutoffs are non-decreasing.
  - Assert every non-manual enriched title got a grade stamped, and every `grade_source = 'manual'` title is untouched (regression test for the manual-override path — explicitly required by `feedback_testing_consistency`).
  - Re-running recompute is idempotent (no spurious changes when the population is unchanged).
- `JdbiRatingCurveRepositoryTest` — round-trips a curve through SQLite, including the JSON column.
- `SchemaUpgraderTest` — v28 upgrade path: existing `grade != NULL` rows backfilled to `grade_source = 'ai'`; `rating_curve` table created.
- `SchemaInitializerTest` — fresh DB has both column and table.
- `TitleRepositoryTest` — `setGradeFromEnrichment` no-ops when `grade_source = 'manual'`; `setGradeManual` always wins; `clearEnrichmentGrade` clears the right rows.
- Integration: enrichment of a title produces a stamped grade once a curve exists.

## Build order

Each step is a discrete commit. Tests gating each step.

1. **Schema:** v28 migration + `SchemaInitializer` mirror + tests. Stop and verify upgrade is clean on an existing DB (load a real snapshot if convenient).
2. **Domain types:** `RatingCurve` value object + JSON serialization + `Boundary` record. Tests for round-trip.
3. **Calculator:** `RatingScoreCalculator` + tests.
4. **Repository:** `RatingCurveRepository` + JDBI impl + tests. `TitleRepository` new methods + tests.
5. **Recomputer:** `RatingCurveRecomputer` + tests with synthetic population. Wire DI in `Application.java`.
6. **Stamper + enrichment hook:** `EnrichmentGradeStamper`, hook into `JavdbProjector`, hook batch-end into the enrichment queue runner. Tests for the projector/queue path.
7. **Manual recompute action:** Tools-menu entry as a Utilities task. Reuse the existing atomic-task pattern.
8. **UI:** `TitleSummary` adds `ratingAvg`, `ratingCount` (and optional `gradeSource`); projection queries surface them; `title-detail.js` renders the raw-score line. No changes to cards.

After each step, run the relevant test subset with `--rerun` (the user is a Gradle expert and explicitly requires `--rerun` for verifying test results — see `feedback_gradle_testing`). Cache hits look like passing tests.

## Things not to do

- Don't introduce a new grade enum or new scale.
- Don't keep a separate AI-grade column. The AI title-grade is being replaced by enrichment for any title where both could exist; the `grade_source` field is enough to communicate provenance.
- Don't compute actress-level aggregate grades. Out of scope.
- Don't add sort/filter UI yet — column and value need to exist and be queryable, but the actual UI lands later.
- Don't recompute the curve on every single-title write. Per-title stamping uses the cached curve; full recompute is batch-end + manual.
- Don't bypass the `grade_source = 'manual'` guard. Manual user grades are sacred even if a curve recompute would assign a different value.
- Don't store the weighted score on `titles`. It's cheap to recompute on demand for the detail view (one multiply + one divide), and persisting it adds another field that drifts from `rating_avg`/`rating_count`.

## Open / deferrable

- A small icon on the title-detail badge indicating source (enrichment vs ai vs manual) — punt unless it falls out naturally.
- Whether the AI-derived `setGrade` path should be retired entirely. Audit existing callers; if it's vestigial, remove it after step 8. If it's still used, leave it alone.
- Tunable `min_credible_votes` (default 50). Could later become a Tools setting; for now hardcode the default in the recomputer and store the value in the curve row so changes are auditable.

## Acceptance

- A title with `rating_avg = 4.6, rating_count = 312` enriched on a representative population gets a grade in the A-tier band, consistent across the card and detail UI.
- A title with `grade_source = 'manual'` is never overwritten by enrichment or curve recompute.
- Recompute is idempotent on an unchanged population.
- Existing AI-graded titles are visible as grades after the v28 backfill, with `grade_source = 'ai'`.
- All new tests pass; no existing tests regress.
