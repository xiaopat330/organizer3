# Proposal: Kanji Stage Name Promotion from javdb

**Status:** SHIPPED 2026-05-02 — bundled with YAML_ALIAS_MIRROR in PR #32 (commit ce3f0f2). Rule 3 conflict surface = log + `enrichment_review_queue` row with `reason='stage_name_conflict'` (anchored to actress's first linked title since queue schema requires title_id NOT NULL).

## Problem

`actresses.stage_name` is populated from YAML during initial load. It may be:
- **NULL** — actress was loaded without a stage name
- **Romanized only** — e.g. "Rei Amami" instead of "天海麗"
- **Wrong** — typo, alternate romanization, or outdated name

The javdb enrichment pipeline already captures the correct kanji name in two places:

| Source | Field | Populated when |
|--------|-------|----------------|
| `javdb_title_staging.cast_json` | `[{"slug":"...","kanji":"天海麗"}]` | After each `fetch_title` |
| `javdb_actress_staging.name_variants_json` | `["天海麗","天海れい"]` | After `fetch_actress_profile` |

The current `AutoPromoter.promoteActressStageName()` uses `cast_json` but **only when `stage_name IS NULL`**. It never corrects an existing value.

## Goals

1. Populate `stage_name` from javdb when it is NULL (already done — keep as-is)
2. Correct `stage_name` when it does not look like kanji (i.e. contains no CJK characters)
3. Use `name_variants_json` from the actress profile as the authoritative source when available
4. Never silently overwrite a value that already contains kanji

## Proposed Rules

### Rule 1: NULL fill (existing, no change)

After `fetch_title` completes, if `stage_name IS NULL`, fill from the matching `cast_json` kanji entry. Already implemented.

### Rule 2: Non-kanji correction (new)

After `fetch_actress_profile` completes, if `stage_name` contains **no CJK characters**, update it from `name_variants_json[0]` (the primary name as listed on the actress's javdb profile page).

Detection: `stage_name NOT GLOB '*[一-龯ぁ-んァ-ン]*'` — no kanji, hiragana, or katakana present.

```sql
UPDATE actresses
SET stage_name = json_extract(jas.name_variants_json, '$[0]')
WHERE id = :actressId
  AND json_extract(jas.name_variants_json, '$[0]') IS NOT NULL
  AND (
    stage_name IS NULL
    OR stage_name NOT GLOB '*[一-龯ぁ-んァ-ン]*'
  )
```

### Rule 3: Conflict logging (new)

After `fetch_actress_profile`, if `stage_name` already contains kanji but does **not** appear in `name_variants_json`, log a WARNING rather than overwriting:

```
javdb: stage_name conflict for actress {id}: ours="{stage_name}", javdb variants={name_variants_json}
```

This surfaces discrepancies (aliases, name changes, data errors) without silent data mutation. A future UI feature could let the user resolve these manually.

## Implementation

### `AutoPromoter.java`

Add `promoteFromActressProfile(long actressId)`:
1. Check `name_variants_json` is non-null and non-empty
2. Apply Rule 2 (non-kanji correction)
3. Apply Rule 3 (conflict log if kanji mismatch)

### `EnrichmentRunner.java`

Call `autoPromoter.promoteFromActressProfile(actressId)` after `fetch_actress_profile` completes (currently only `promoteActressStageName` is called there via the title path).

### `SchemaUpgrader.java`

No schema changes required. All data is already present in staging tables.

## Non-goals

- Do not update `canonical_name` — that is user-curated and romanized by design
- Do not source from DMM or other scrapers — javdb is sufficient
- Do not batch-correct existing actresses retroactively on startup — corrections happen naturally as enrichment runs
- Do not add a UI for manual conflict resolution in this proposal (see Rule 3 — log only for now)

## Test coverage

- Null fill still works (existing tests pass)
- Non-kanji `stage_name` is corrected after profile fetch
- Kanji `stage_name` that matches a variant is not changed
- Kanji `stage_name` that doesn't match any variant logs a warning but is not changed
- Empty `name_variants_json` is a no-op
