# Proposal: Query Optimization

Design spec for a set of targeted database optimizations to eliminate known slow query patterns. All changes are invisible to the user — the app feels faster and more responsive, especially on filtered browse views and company/tag filtering.

No new user-facing features. No behavioral changes.

---

## 1. Motivation

As the repository layer has grown to support the Titles and Actresses dashboards, several expensive query patterns have accumulated:

- A correlated subquery that runs once per row during tag filtering
- An EXISTS chain that traverses three tables per actress for company filtering
- `upper()` function wrappers on indexed join columns that silently disable index use
- `LOWER()` function wrappers on name searches that prevent index use

These patterns compound: a volume browse filtered by company and tag hits all three simultaneously. The result is visible sluggishness that grows with library size.

---

## 2. Issues and fixes

### 2.1 `upper()` on label joins blocks indexes

**Pattern (appears in 6+ queries):**
```sql
JOIN labels l ON upper(l.code) = upper(t.label)
```

`titles.label` has an index (`idx_titles_label`). `labels.code` is a primary key (also indexed). Wrapping both sides in `upper()` forces a per-row function evaluation — SQLite cannot use either index and scans both tables.

**Why the wrappers are unnecessary:** `titles.label` is always stored uppercase — it is parsed from a product code by `TitleCodeParser` (e.g., `ABP-123` → label `ABP`). `labels.code` is seeded uppercase from `labels.yaml`. The defensive `upper()` is not needed.

**Fix:** Remove `upper()` from all label join conditions. Replace:
```sql
JOIN labels l ON upper(l.code) = upper(t.label)
```
with:
```sql
JOIN labels l ON l.code = t.label
```

Affected queries in `JdbiTitleRepository`: `countTitlesByCompanies`, `findNewestActressesByLabels`, `findAddedSinceByLabels` (already uses `upperLabels` variable — remove that too).

Affected queries in `JdbiActressRepository`: `findByTierAndCompaniesPaged`, `findByVolumesAndCompaniesPaged`, `findByStudioGroupCompaniesPaged`, `countByStudioGroupCompanies`.

Affected queries in tag filters: all `findByTagsPaged`, `findByVolumeFiltered`, `findByActressTagsFiltered`, `findByVolumeAndPartitionFiltered`, `findTagsByVolume`, `findTagsByVolumeAndPartition`.

---

### 2.2 Correlated tag subquery

**Pattern (appears in 6 queries):**
```sql
WHERE (
    SELECT COUNT(DISTINCT merged.tag)
    FROM (
        SELECT tag FROM title_tags WHERE title_id = t.id
        UNION
        SELECT lt.tag FROM label_tags lt WHERE lt.label_code = upper(t.label)
    ) merged
    WHERE merged.tag IN (<tags>)
) = :tagCount
```

This correlated subquery executes once per candidate title row. A volume browse with 500 titles and a tag filter runs this 500 times. Each execution unions two indexed lookups — the cost is real and scales linearly with result set size.

**Fix:** Introduce a `title_effective_tags` denormalization table that precomputes the merged tag set for each title. Tag filtering becomes a single indexed JOIN.

**New table (`applyV11`):**
```sql
CREATE TABLE title_effective_tags (
    title_id  INTEGER NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
    tag       TEXT NOT NULL,
    source    TEXT NOT NULL CHECK(source IN ('direct', 'label')),
    PRIMARY KEY (title_id, tag)
);
CREATE INDEX idx_title_effective_tags_tag ON title_effective_tags(tag);
```

**Population:** Maintained by a new `TitleEffectiveTagsService` called from the sync pipeline and from any code path that writes to `title_tags` or `label_tags`.

Population logic per title:
```sql
DELETE FROM title_effective_tags WHERE title_id = :titleId;

INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
SELECT :titleId, tag, 'direct' FROM title_tags WHERE title_id = :titleId;

INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
SELECT :titleId, lt.tag, 'label'
FROM label_tags lt
WHERE lt.label_code = (SELECT label FROM titles WHERE id = :titleId)
  AND lt.label_code IS NOT NULL AND lt.label_code != '';
```

Population triggers:
- End of sync (batch: recompute all titles touched in that sync run)
- When `label_tags` is reseeded (full recompute, run once)
- When a title's `label` column is updated

**Backfill migration (`applyV11`):** After creating the table, run a one-time backfill:
```sql
INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
SELECT title_id, tag, 'direct' FROM title_tags;

INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
SELECT t.id, lt.tag, 'label'
FROM titles t
JOIN label_tags lt ON lt.label_code = t.label
WHERE t.label IS NOT NULL AND t.label != '';
```

**Replacement query pattern:**
```sql
-- Before (correlated subquery):
WHERE (
    SELECT COUNT(DISTINCT merged.tag) FROM ( ... ) merged
    WHERE merged.tag IN (<tags>)
) = :tagCount

-- After (indexed join):
JOIN title_effective_tags tet ON tet.title_id = t.id AND tet.tag IN (<tags>)
GROUP BY t.id
HAVING COUNT(DISTINCT tet.tag) = :tagCount
```

Also simplifies `findTagsByVolume` and `findTagsByVolumeAndPartition`:
```sql
-- Before: UNION subquery over title_tags and label_tags
-- After:
SELECT DISTINCT tet.tag
FROM title_effective_tags tet
JOIN title_locations tl ON tl.title_id = tet.title_id
WHERE tl.volume_id = :volumeId
ORDER BY tet.tag
```

---

### 2.3 Company filter EXISTS chain

**Pattern (appears in 4 queries):**
```sql
EXISTS (
    SELECT 1 FROM title_actresses ta
    JOIN titles t ON t.id = ta.title_id
    JOIN labels l ON upper(l.code) = upper(t.label)
    WHERE ta.actress_id = a.id
      AND t.label IS NOT NULL AND t.label != ''
      AND l.company IN (<companies>)
)
```

For each actress candidate, this traverses `title_actresses → titles → labels`. The `upper()` layer on top (fixed by §2.1) makes it worse, but even without it, the three-table traversal per actress is the bottleneck for company-filtered browse pages.

**Fix:** Introduce an `actress_companies` denormalization table that precomputes which companies each actress has worked for. Company filtering becomes a single indexed lookup.

**New table (`applyV11`):**
```sql
CREATE TABLE actress_companies (
    actress_id  INTEGER NOT NULL REFERENCES actresses(id) ON DELETE CASCADE,
    company     TEXT NOT NULL,
    PRIMARY KEY (actress_id, company)
);
CREATE INDEX idx_actress_companies_company ON actress_companies(company);
```

**Population:** Maintained by a new `ActressCompaniesService` called from the sync pipeline.

Population logic per actress:
```sql
DELETE FROM actress_companies WHERE actress_id = :actressId;

INSERT OR IGNORE INTO actress_companies (actress_id, company)
SELECT DISTINCT :actressId, l.company
FROM title_actresses ta
JOIN titles t ON t.id = ta.title_id
JOIN labels l ON l.code = t.label
WHERE ta.actress_id = :actressId
  AND t.label IS NOT NULL AND t.label != ''
  AND l.company IS NOT NULL;
```

Population triggers:
- End of sync (batch: recompute all actresses whose titles changed in that sync run)
- When `labels` data is reseeded (full recompute)

**Backfill migration (`applyV11`):** After creating the table:
```sql
INSERT OR IGNORE INTO actress_companies (actress_id, company)
SELECT DISTINCT ta.actress_id, l.company
FROM title_actresses ta
JOIN titles t ON t.id = ta.title_id
JOIN labels l ON l.code = t.label
WHERE t.label IS NOT NULL AND t.label != ''
  AND l.company IS NOT NULL;
```

**Replacement query pattern:**
```sql
-- Before (EXISTS chain per actress):
AND EXISTS (
    SELECT 1 FROM title_actresses ta
    JOIN titles t ON t.id = ta.title_id
    JOIN labels l ON upper(l.code) = upper(t.label)
    WHERE ta.actress_id = a.id AND l.company IN (<companies>)
)

-- After (indexed join):
JOIN actress_companies ac ON ac.actress_id = a.id AND ac.company IN (<companies>)
```

Affected methods: `findByTierAndCompaniesPaged`, `findByVolumesAndCompaniesPaged`, `findByStudioGroupCompaniesPaged`, `countByStudioGroupCompanies`.

---

### 2.4 `LOWER()` on name searches blocks index

**Pattern (appears in 5 methods):**
```sql
WHERE LOWER(canonical_name) LIKE :startsWith
```

`canonical_name` has a unique constraint (implicitly indexed), but the `LOWER()` wrapper prevents SQLite from using the index — full table scan every search.

**Fix:** Add a COLLATE NOCASE index and change queries to use it:

**New index (`applyV11`):**
```sql
CREATE INDEX IF NOT EXISTS idx_actresses_name_nocase
    ON actresses(canonical_name COLLATE NOCASE);
```

**Query change:**
```sql
-- Before:
WHERE LOWER(canonical_name) LIKE :prefix

-- After:
WHERE canonical_name LIKE :prefix COLLATE NOCASE
```

Note: the leading-wildcard pattern (`LIKE '% yua%'`) cannot use a B-tree index regardless of collation — that case always requires a full scan. This fix only benefits prefix queries (`LIKE 'yua%'`). The word-boundary search pattern is an inherent SQLite limitation; FTS5 is the eventual solution if it becomes a bottleneck.

Affected methods: `searchByNamePrefix`, `searchByNamePrefixPaged`, `findByFirstNamePrefix`, `findByFirstNamePrefixPaged`, `countByFirstNamePrefixGroupedByTier`.

---

### 2.5 `DISTINCT` JOIN for actress-by-volume

**Pattern:**
```sql
SELECT DISTINCT a.* FROM actresses a
JOIN titles t ON t.actress_id = a.id
JOIN title_locations tl ON tl.title_id = t.id
WHERE tl.volume_id IN (<volumeIds>)
```

`DISTINCT` forces SQLite to sort the full join product to dedup. An `EXISTS` subquery short-circuits on the first matching title per actress.

**Fix:**
```sql
SELECT a.* FROM actresses a
WHERE EXISTS (
    SELECT 1 FROM titles t
    JOIN title_locations tl ON tl.title_id = t.id
    WHERE t.actress_id = a.id
      AND tl.volume_id IN (<volumeIds>)
)
```

Affected methods: `findByVolumeIds`, `findByVolumeIdsPaged`.

---

## 3. Schema migration

Single migration `applyV12()` (v11 is reserved for AV Stars per `PROPOSAL_AV_STARS.md`):

```
1. CREATE TABLE title_effective_tags + index
2. CREATE TABLE actress_companies + index
3. CREATE INDEX idx_actresses_name_nocase
4. Backfill title_effective_tags from title_tags + label_tags
5. Backfill actress_companies from title_actresses + titles + labels
```

All backfill steps are idempotent (`INSERT OR IGNORE`). Migration is safe to run on an existing populated database.

---

## 4. New service classes

Two new services manage denorm table maintenance. Both live in `com.organizer3.db` or a new `com.organizer3.db.denorm` sub-package.

### `TitleEffectiveTagsService`

```java
void recomputeForTitle(long titleId)      // called when one title's tags change
void recomputeForTitles(List<Long> ids)   // called at end of sync
void recomputeAll()                        // called after label_tags reseed
```

### `ActressCompaniesService`

```java
void recomputeForActress(long actressId)      // called when one actress's titles change
void recomputeForActresses(List<Long> ids)    // called at end of sync
void recomputeAll()                            // called after labels reseed
```

Both services are wired in `Application.java` and injected into the sync pipeline and any seed commands that modify `label_tags` or `labels`.

---

## 5. Sync pipeline integration

At the end of each sync run, after title upserts are complete:

1. Collect all title IDs touched in this sync run
2. Call `TitleEffectiveTagsService.recomputeForTitles(touchedTitleIds)`
3. Collect all actress IDs whose titles changed
4. Call `ActressCompaniesService.recomputeForActresses(affectedActressIds)`

This keeps the denorm tables current without a full recompute on every sync.

---

## 6. Testing

Existing repository tests use real in-memory SQLite and must continue to pass. Each optimization that changes query behavior needs a test that:

- For §2.2: asserts that tag filtering returns the correct titles when tags come from both `title_tags` (direct) and `label_tags` (inherited)
- For §2.3: asserts that company filtering returns the correct actresses after `actress_companies` is populated
- For §2.4: asserts case-insensitive prefix search returns matches regardless of name casing
- For §2.1 and §2.5: existing tests cover correctness; no new tests needed beyond passing existing suite

New tests for `TitleEffectiveTagsService` and `ActressCompaniesService`:
- Recompute populates correctly from seed data
- Recompute after a title's label changes updates the denorm row
- Recompute is idempotent (safe to run twice)

---

## 7. Rollout order

1. **§2.1 — Remove `upper()` wrappers** — pure query change, lowest risk, do first
2. **§2.4 — COLLATE NOCASE index** — migration + query change, no data backfill
3. **§2.5 — DISTINCT → EXISTS** — pure query change
4. **§2.2 — `title_effective_tags`** — schema + backfill + query changes + service
5. **§2.3 — `actress_companies`** — schema + backfill + query changes + service

Items 1–3 are pure query rewrites with no schema changes and can ship together. Items 4–5 require the v12 migration and should ship together since they share the migration.

---

## 8. Out of scope

- **FTS5 for actress name search** — the leading-wildcard word-boundary case (`LIKE '% name%'`) cannot be index-accelerated with B-tree indexes. FTS5 virtual tables would solve it but require additional sync-time maintenance. Deferred until name search performance is actually a complaint.
- **Query plan validation (`EXPLAIN QUERY PLAN`)** — worth running manually after implementation to confirm indexes are being used. Not automated.
- **AV Stars queries** — `av_actresses` and `av_videos` are not yet implemented; their queries are not included here.
