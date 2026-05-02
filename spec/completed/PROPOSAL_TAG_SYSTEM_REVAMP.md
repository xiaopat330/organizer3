# Tag System Revamp — Enrichment-Backed Title Surfacing

## Status: SHIPPED — schema, data layer, and Discovery surfacing UI live (`enrichment-tag-system` branch from commit 48f71ac onward). Operational reference: `spec/ENRICHMENT_TAG_OPS.md`.

> **v2 supersedes v1.** The original draft proposed a unified `tag_definitions` table with a `source` column. That direction was rejected: enrichment data should live in its own immutable sidecar record, not be merged into the curated tag schema. v2 reflects the actual decided design.

## Background

JAV titles currently carry **two** kinds of tags:

| Type | Storage | Mutability | Source |
|------|---------|------------|--------|
| **Explicit** | `title_tags` | User-editable (add/remove via title editor) | Manual / curated |
| **Inherited** | `label_tags` (via `titles.label`) | Read-only globally per label | Hand-authored YAML taxonomy |

These are denormalized into `title_effective_tags` for query (joined by `JdbiTitleRepository` for browse, filter, and faceting).

`javdb_title_staging.tags_json` holds raw javdb-scraped tags as a JSON array, but they are not promoted to real rows and are not queryable. Empirically (sample of 92 enriched titles): javdb's title extractor returns tags **already English-translated** (e.g. `["Big Tits","Solowork","Slender","Cowgirl"]`). Other extracted fields like `titleOriginal`, `maker`, `series`, and cast names remain Japanese.

## Goal

Enable richer title surfacing through enrichment metadata — primarily **tags** and **rating**, also **maker** and **release date** — without sacrificing the curated tag system.

The motivating use cases:
- "Show me high-rated (`rating_avg ≥ 4.2`, `rating_count ≥ 50`) titles tagged Big Tits AND Solowork"
- "Top 50 javdb-rated titles in my library"
- "Recent (last 6 months) titles from S1 NO.1 STYLE"
- "Hide poorly-received titles" / "surface highly-favorable ones"

Curated tags alone are too sparse to drive these queries; the metadata exists in enrichment data and should be used.

## Enrichment Coverage Reality

**Enrichment is and will remain partial.** Only a small fraction of the library will ever be enriched — likely on the order of 5–10%. Most titles are archives, not actively reviewed; enrichment is performed for titles under active consideration ("what should I watch next?"), not as a comprehensive index of the library.

This shapes the design in three concrete ways:

### 1. The enrichment record is an overlay, not an index

Surfacing queries that only touch enrichment tables (rating, enrichment tags, maker, etc.) inherently return *only enriched titles* — at most ~5–10% of the library. This isn't a downgrade; it matches the use case ("filter the actively-curated subset"). But the UI must communicate it honestly:

- `12 of 200 enriched titles match` — not `12 titles match`.
- A user filtering on the main library browse expects whole-library answers; enrichment-only filters there will silently hide ~95% of the library. **Phase 3 surfacing UI should land in enrichment-aware contexts first** (Discovery, dedicated "Top Rated" views) before touching the main library browse.

### 2. Frequency math is denominated against the enriched subset

`enrichment_tag_definitions.title_count` counts rows in `title_enrichment_tags`, every one of which belongs to an enriched title. The faceted picker's "% of titles with this tag" is therefore implicitly `% of currently-matching enriched titles` — which is the right denominator. **Do not "fix" this by joining against `titles`** — that would dilute every tag toward zero and make the rankings useless.

A side effect: tag distribution in the enriched subset reflects *what you choose to enrich*, not the library's underlying composition. Library-bias persists rather than diluting.

### 3. The `curated_alias` bridge becomes load-bearing

The curated tag system covers the whole library; enrichment tags cover ~5–10% of it. A user asking *"show me Big Tits titles in my library"* needs a query that spans both worlds — and the only mechanism that does so is the alias mapping. Without `curated_alias`, every enrichment-tag query is forever stuck inside the enriched subset.

`curated_alias` mappings on high-frequency enrichment tags are therefore **the load-bearing seed** that makes enrichment data useful at library scale, not a nice-to-have for taxonomy hygiene. The Phase 3 seed-curation script's quality directly determines how useful the surfacing system feels.

## The Model: Three Tag Types, Two Storage Worlds

A new tag type joins the picture:

| Type | Storage | Mutability | Source |
|------|---------|------------|--------|
| Explicit | `title_tags` (existing) | User-editable | Manual |
| Inherited | `label_tags` (existing) | Read-only global | Label taxonomy |
| **Enrichment** | NEW: `title_enrichment_tags` (sidecar) | **Immutable — replaced atomically on re-enrichment** | javdb |

**Critical principle:** the enrichment world and the curated world stay structurally separate. There is no `source` column unifying them. Storage is segregated; unification (if any) happens at query and UI time.

## User-Edit Constraint

Users can directly edit only three fields on a title:
- `favorited`
- `bookmarked`
- explicit tags (add/remove)

**No other title fields are user-editable.** All other field values change only via (a) re-enrichment or (b) folder-data extraction. This means duplication of `title_original` / `release_date` on the enrichment row is for source-of-truth provenance, not drift reconciliation.

## Schema

### `title_javdb_enrichment` (1:1 with titles, only when enriched)

```sql
CREATE TABLE title_javdb_enrichment (
    title_id            INTEGER PRIMARY KEY REFERENCES titles(id) ON DELETE CASCADE,
    javdb_slug          TEXT NOT NULL,
    fetched_at          TEXT NOT NULL,
    -- surfacing scalars (indexed)
    release_date        TEXT,
    rating_avg          REAL,
    rating_count        INTEGER,
    maker               TEXT,
    publisher           TEXT,
    series              TEXT,
    -- ancillary, not for filtering
    title_original      TEXT,
    duration_minutes    INTEGER,
    cover_url           TEXT,
    thumbnail_urls_json TEXT,
    cast_json           TEXT,
    raw_path            TEXT
);
CREATE INDEX idx_tje_rating_avg   ON title_javdb_enrichment(rating_avg);
CREATE INDEX idx_tje_release_date ON title_javdb_enrichment(release_date);
CREATE INDEX idx_tje_maker        ON title_javdb_enrichment(maker);
```

**Promotion to canonical `titles`:**
- `title_original` and `release_date` continue to be promoted into the `titles` table (current AutoPromoter behavior).
- The enrichment row keeps its own copy as the immutable source-of-truth.
- `stage_name` promotion is parked pending a separate discussion about multi-kanji actress identity (an actress-domain problem, not a title-enrichment one).

### `enrichment_tag_definitions` (one row per distinct javdb tag)

```sql
CREATE TABLE enrichment_tag_definitions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL UNIQUE,        -- e.g. 'Big Tits' (javdb returns these English-translated)
    curated_alias   TEXT REFERENCES tags(name),  -- nullable; see "Curated/Enrichment Overlap" below
    title_count     INTEGER NOT NULL DEFAULT 0,  -- denormalized; refreshed on enrichment writes
    surface         INTEGER NOT NULL DEFAULT 1   -- 0 = suppress from default tag pickers
);
CREATE INDEX idx_etd_title_count ON enrichment_tag_definitions(title_count);
```

- **`curated_alias`** is the bridge to the curated taxonomy. It is null for the vast majority of enrichment tags but, when set, declares "this enrichment tag is the same concept as curated tag X" — enabling unified surfacing without merging the two storage worlds.
- **`title_count`** is a denormalized count of how many titles carry this tag. Refreshed when `title_enrichment_tags` rows are inserted/deleted (or recomputed in batch after enrichment runs). Lets the UI compute "X% of library" and rank tags by frequency without aggregation per request.
- **`surface`** is a manual suppression flag. Tags that are noise or near-universal (in this library) can be marked `surface = 0` to hide them from default tag pickers. They remain queryable; they just don't clutter the picker UI.

No `language` column: javdb tags arrive English-translated, so the curated/enrichment storage worlds share the same language. If a non-English enrichment source enters scope later, add the column then.

### `title_enrichment_tags` (M:N — the queryable rows)

```sql
CREATE TABLE title_enrichment_tags (
    title_id  INTEGER NOT NULL REFERENCES title_javdb_enrichment(title_id) ON DELETE CASCADE,
    tag_id    INTEGER NOT NULL REFERENCES enrichment_tag_definitions(id),
    PRIMARY KEY (title_id, tag_id)
);
CREATE INDEX idx_tet_tag ON title_enrichment_tags(tag_id);
```

The FK to `title_javdb_enrichment.title_id` (CASCADE) means deleting an enrichment record automatically clears its tag rows — clean atomic replacement on re-enrichment.

## Relationship to `javdb_title_staging`

`javdb_title_staging` is being **promoted from a transient staging table to the canonical enrichment record.** The "staging" framing is dropped. The new table `title_javdb_enrichment` replaces it as the durable home for javdb enrichment data.

The data flow becomes:
1. `EnrichmentRunner` fetches javdb HTML, parses it, writes a row to `title_javdb_enrichment`.
2. `EnrichmentTagPromoter` (new) parses the parsed tag list and populates `enrichment_tag_definitions` + `title_enrichment_tags`.
3. `AutoPromoter` continues to promote canonical fields (`title_original`, `release_date`) into the `titles` table.

## Re-Enrichment Semantics

When a title is force re-enriched (`enqueueTitleForce`), the enrichment record is **atomically replaced**:
1. `DELETE FROM title_javdb_enrichment WHERE title_id = ?` (CASCADE clears its tags).
2. `INSERT` the new enrichment row from the freshly fetched data.
3. Re-populate `title_enrichment_tags` from the new tag list.
4. Re-run canonical promotion to `titles`.

No history retention. No merging. No diffing.

## Surfacing Queries — Worked Examples

All composite filters AND together. Tag combinations are always conjunctions.

**High-rated titles tagged Big Tits AND Cowgirl from S1, released in 2024:**
```sql
SELECT t.id
FROM titles t
JOIN title_javdb_enrichment e ON e.title_id = t.id
JOIN title_enrichment_tags tet ON tet.title_id = t.id
JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
WHERE e.rating_avg >= 4.2 AND e.rating_count >= 50
  AND e.maker = 'S1 NO.1 STYLE'
  AND e.release_date BETWEEN '2024-01-01' AND '2024-12-31'
  AND etd.name IN ('Big Tits','Cowgirl')
GROUP BY t.id
HAVING COUNT(DISTINCT etd.id) = 2;
```

**Top 50 javdb-rated titles in the library:**
```sql
SELECT t.id, e.rating_avg, e.rating_count
FROM titles t
JOIN title_javdb_enrichment e ON e.title_id = t.id
WHERE e.rating_count >= 50
ORDER BY e.rating_avg DESC, e.rating_count DESC
LIMIT 50;
```

**Hide poorly-received titles in the standard browse:**
```sql
... WHERE NOT EXISTS (
  SELECT 1 FROM title_javdb_enrichment e
  WHERE e.title_id = t.id AND e.rating_avg < 3.0 AND e.rating_count >= 20
)
```
(Un-enriched titles are not penalized.)

## Surfacing Axes — In Scope

| Axis | Field | Notes |
|------|-------|-------|
| Tags (AND) | `title_enrichment_tags` | Conjunction only; never OR |
| Tag exclusion | `title_enrichment_tags` | First-class — see "Tag Frequency & Surfacing UX" below |
| Rating (high) | `rating_avg`, `rating_count` | Both must be considered (avoid 5.0/1-vote noise) |
| Rating (exclude poor) | same | Threshold determined at UI layer |
| Release date | `release_date` | "Recent" preset + arbitrary period |
| Maker / studio | `maker` | High-value filter |
| Composite (any of the above) | — | All AND together |

**Out of scope:** duration filtering.

## UI Implications

- **Curated tag UI is unchanged.** The title editor's tag picker continues to operate on `title_tags`.
- **Enrichment tags are read-only.** They appear in title detail / Discovery views but cannot be edited.
- **Surfacing UI** is new — a filter panel exposing the axes above. **Lands first in enrichment-aware contexts** (Discovery, dedicated "Top Rated" views) where ~100% of visible titles have enrichment data. Extending it to the main library browse — where ~95% of titles will be invisible to enrichment-only filters — requires either (a) clearly displaying "X of N enriched" counts so users understand the scope, or (b) routing through `curated_alias` so the filter can OR-expand into the curated system. See "Enrichment Coverage Reality" above.
- **Visual distinction is required** so users can tell curated tags from enrichment tags at a glance (color, prefix, separate row, or section header — TBD). Even though both are English now, mixing them in one panel without distinction would conflate user-curated semantics with auto-scraped data.
- **High-frequency tags** (e.g. `Solowork` appears in ~85% of enriched titles) carry little surfacing signal. UI may want frequency hints in the tag picker, or to de-prioritize near-universal tags. Acceptable to leave as-is initially.

## Tag Frequency & Surfacing UX

A tag's value as a **surfacing axis** is roughly inversely proportional to its frequency. A tag that 90% of titles share carries almost no information when used as a positive filter — it barely narrows the result set. A tag that 5% of titles share is highly discriminating. The "useful filter zone" is the middle band — roughly 5–40% of the library.

### Sample-size caveat

The frequency observations in this proposal come from a sample of **92 enriched titles**, which is small. The true tag landscape (vocabulary size, frequency distribution, near-universal tags) will shift significantly as enrichment expands across the full library. Design decisions below favor mechanisms that work *dynamically* against the live library rather than absolute thresholds tuned to today's sample.

### Library-bias is real

`Solowork` appearing in ~85% of the current sample is not a property of the tag — it's a property of *this library* (heavily skewed toward solo-actress titles). In a different library `Solowork` might be 20% and a useful filter. Implication:

- **Frequencies are computed dynamically** from `enrichment_tag_definitions.title_count` and refreshed as the library changes.
- **Cutoffs are relative to library size**, not absolute counts. "Appears in >70% of enriched titles" is portable; "appears in >65 titles" isn't.

### Inverse / exclusion filters are first-class

When a tag is near-universal, the *negation* is the discriminating filter. `NOT Solowork` narrows the library to ~15% of titles. The surfacing UI should treat tag exclusion as a primary axis, not a secondary feature.

### The tail problem

~50 of the 93 distinct enrichment tags in the sample appear ≤ 2 times. Two interpretations, indistinguishable automatically:
- **Junk** — javdb extractor over-tagged a single edge case.
- **Highly specific signal** — exactly what you want when you want it.

UI strategy: hide from the main picker (driven by `surface = 0` or low `title_count`), expose in an "all tags" expanded view.

### Faceted picker as Phase 3 design goal

A flat tag picker doesn't handle frequency well. A **faceted** picker re-ranks remaining tags by *conditional* count after each selection: when you've picked `Big Tits`, `Cowgirl` is interesting only if many of the matching titles are also tagged `Cowgirl`. Tags whose conditional count is zero are hidden or grayed.

This handles both the high- and low-frequency problems organically: a near-universal tag stays uninteresting; a rare tag that co-occurs with the current selection rockets to the top.

Phase 3 surfacing UI should be designed faceted-first.

## Curated / Enrichment Overlap

Sample analysis of 92 enriched titles surfaced 93 distinct enrichment tags. Many overlap conceptually with curated tags from `reference/tags.yaml` — e.g. `Big Tits`, `Lesbian`, `POV`, `Cosplay`, `Shaved`, `Creampie`, `Squirting`, `Facials`, `Glasses`, `Cowgirl`. Because both worlds are now English, alias-mapping becomes tractable.

Per the "Enrichment Coverage Reality" section above, this mapping is the only mechanism that lets enrichment-derived tag concepts span the whole library. It is load-bearing, not optional.

**Approach: opt-in `curated_alias` on each enrichment tag definition.**
- A new enrichment tag inserts with `curated_alias = NULL` (no assertion).
- A curation step (initially a script, possibly a UI later) sets `curated_alias` to a curated tag name when an equivalence is asserted.
- Surfacing queries can choose to OR-expand a curated tag against its enrichment aliases:
  ```sql
  -- "show me titles tagged 'big-tits' (curated) OR with the equivalent enrichment tag"
  SELECT t.id FROM titles t
  WHERE EXISTS (SELECT 1 FROM title_effective_tags WHERE title_id = t.id AND tag = 'big-tits')
     OR EXISTS (
       SELECT 1 FROM title_enrichment_tags tet
       JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
       WHERE tet.title_id = t.id AND etd.curated_alias = 'big-tits'
     );
  ```

This is the closest the design gets to "unification" — and it stays opt-in per tag rather than baked into storage. Curated tags remain authoritative for human-curated semantics; enrichment tags remain immutable evidence from javdb.

The initial seeding pass (mapping high-frequency enrichment tags to existing curated tags) can be a one-shot script run after the migration backfill.

## What This Does NOT Change

- `tags`, `title_tags`, `label_tags`, `title_effective_tags` — the curated system stays exactly as-is.
- `TitleEffectiveTagsService` and all its callers — untouched.
- `av_tag_definitions` / `av_video_tags` — AV Stars system, out of scope entirely.
- `JdbiTitleRepository` joins on `title_effective_tags` — untouched. Enrichment surfacing adds new query paths; existing ones are unaffected.

## Migration Path

1. **Schema migration** — add `title_javdb_enrichment`, `enrichment_tag_definitions`, `title_enrichment_tags` via a new `SchemaUpgrader.applyVN()`.
2. **Backfill from `javdb_title_staging`** — copy every fetched staging row into `title_javdb_enrichment`; parse `tags_json` into the two tag tables.
3. **Cut the EnrichmentRunner over** to write directly into `title_javdb_enrichment` (no longer through staging).
4. **Deprecate `javdb_title_staging`** — once cutover is verified, drop it (or rename and keep read-only for one release).
5. **Seed `curated_alias` mappings** — one-shot script reviewing the top-frequency enrichment tags against `reference/tags.yaml` and proposing equivalences. Output reviewed/applied manually. **This step is load-bearing, not cosmetic** — it's what makes enrichment data useful at library scale rather than just inside the enriched subset (~5–10% of titles). See "Enrichment Coverage Reality" and "Curated / Enrichment Overlap".
6. **Build the surfacing UI** as a separate phase once the data is in place.

## Open Questions

1. **Threshold defaults for "high rating" / "low rating"** — UI layer concern, but worth documenting initial defaults. Suggested starting points: high = `avg ≥ 4.2 AND count ≥ 50`; low = `avg < 3.0 AND count ≥ 20`. Confirm or override.

2. **Where does the surfacing UI live first?** Discovery screen (already enrichment-aware) or main library browse?

3. **Enrichment-tag visibility in title detail** — show all of them, or top-N most-common across the library, or filtered by category once that exists?

4. **Cast data (`cast_json`)** — currently captured but not normalized. Out of scope for this proposal, but worth flagging that the same "normalize for queryability" pattern applies (could become `title_enrichment_cast` later).

5. **`curated_alias` curation workflow** — initial seeding is a one-shot script. Is an admin UI for ongoing alias management worth building, or does the script-only workflow suffice (single-user library)?

6. **Promotion of "best stage_name" decision** — parked. Belongs to a separate proposal about actress identity / multi-kanji handling.

## Resolved (Not Open)

- **Sensitive tags (`Rape`, `Molester`, `Confinement`, etc.)** — single-user library, no content policy required. Tags surface as-is.
- **Tag language** — javdb returns English-translated tags. No translation layer needed.
- **High-frequency tag handling** — addressed via the `title_count` denormalization, the `surface` suppression flag, first-class exclusion filters, and faceted picker as a Phase 3 design goal. See "Tag Frequency & Surfacing UX" above. Caveat: current observations are from a 92-title sample; the true tag landscape will only become clear as enrichment scales across the full library.
