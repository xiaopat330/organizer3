# Enrichment Tag System — Operational Reference

This is the maintenance handbook for the enrichment tag system shipped in the
`enrichment-tag-system` branch (commits `48f71ac` through the present).
Read this when:
- New tags have arrived from a batch of enrichments and you want to triage them
- A near-universal tag has emerged and is cluttering the picker
- You want to extend the curated taxonomy
- Something looks wrong in the Discovery surfacing UI

For the full design rationale see `spec/PROPOSAL_TAG_SYSTEM_REVAMP.md`.

## What's where

| Concern | Location |
|---|---|
| Schema (3 tables: `title_javdb_enrichment`, `enrichment_tag_definitions`, `title_enrichment_tags`) | `SchemaInitializer.java` (fresh installs) + `SchemaUpgrader.applyV25` (existing DBs) |
| Write path | `JavdbEnrichmentRepository.upsertEnrichment` — atomic replace + tag normalization + `title_count` refresh |
| Read paths | `JavdbDiscoveryService` (filtered titles, tag facets, tag-health report) and `AutoPromoter` (canonical promotion) |
| Maintenance script | `scripts/seed_enrichment_tag_aliases.py` |
| Curated taxonomy | `reference/tags.yaml` (authored), loaded into `tags` table |
| Hand-curated semantic mappings | `SEMANTIC_MAP` + `REVIEW_MAP` dicts inside the script |
| Last proposal output | `reference/enrichment_tag_aliases.proposed.yaml` |
| Live DB | `~/.organizer3/organizer.db` (paths hardcoded in `Application.java`) |
| Backup before risky edits | `~/.organizer3/organizer.db.pre-v25` (or make a fresh one) |
| In-app dashboard | Tools → **Tag Health** |
| Surfacing UI | Tools → **Discovery** → select an actress → Titles tab → filter bar |

## The core data model (one-line summary each)

- `enrichment_tag_definitions(id, name, curated_alias, title_count, surface)` — one row per distinct javdb tag string.
- `title_enrichment_tags(title_id, tag_id)` — M:N join to titles.
- `title_javdb_enrichment(title_id, …, rating_avg, rating_count, …)` — the canonical enrichment record (1:1 with titles for enriched titles).
- `curated_alias` is the bridge to `tags(name)` and is what makes enrichment-tag concepts queryable across the un-enriched majority of the library. It is opt-in per tag.
- `surface = 1` (default) means the tag appears in faceted pickers; set to 0 to hide from default UI.
- `title_count` is denormalized; refreshed on every enrichment write and during `seed_enrichment_tag_aliases.py --apply`.

## Recurring tasks

### Task 1 — Triage new tags after a batch of enrichments

**When:** every few hundred new enrichments, or any time the Tag Health summary shows a high "unmapped tags with ≥ 3 titles" count.

```bash
# 1. Generate fresh proposal from current DB state.
python3 scripts/seed_enrichment_tag_aliases.py
# Writes reference/enrichment_tag_aliases.proposed.yaml.
#
# Output summarises:
#   high_confidence: N entries (auto-mapped via SEMANTIC_MAP + normalized exact matches)
#   review:          N entries (REVIEW_MAP — needs human attention)
#   unmapped:        N entries (no candidate found)

# 2. Apply the high-confidence section. Idempotent — re-running on already-mapped
#    tags is a UPDATE-to-same-value no-op.
python3 scripts/seed_enrichment_tag_aliases.py \
  --apply reference/enrichment_tag_aliases.proposed.yaml \
  --only-section high_confidence

# 3. Hand-edit the file:
#    - Inspect `review` entries; change `curated_alias` values you disagree
#      with, or set them to `null` to skip.
#    - Inspect `unmapped` entries; for any worth mapping, replace `null` with
#      the curated tag name (must exist in reference/tags.yaml).

# 4. Apply the (now-reviewed) full file.
python3 scripts/seed_enrichment_tag_aliases.py \
  --apply reference/enrichment_tag_aliases.proposed.yaml
```

### Task 2 — See what's new since last run

```bash
python3 scripts/seed_enrichment_tag_aliases.py \
  --diff reference/enrichment_tag_aliases.proposed.yaml
```

Reports:
- New tag names not in the prior proposal
- Tags whose `title_count` has grown ≥ 50% (signals shifting vocabulary)

Use this before deciding whether to re-run propose.

### Task 3 — Suppress a noise tag (`surface = 0`)

**Why:** when a tag becomes near-universal in your library (the next "Solowork"), it clutters the faceted picker without informing the filter. Hide it.

**Easiest path** — Tools → Tag Health → flip the surface toggle. Live, no restart needed. Faceted picker drops it on next refresh.

**SQL fallback:**
```sql
UPDATE enrichment_tag_definitions SET surface = 0 WHERE name = 'Solowork';
```

To re-enable, flip the same toggle or set `surface = 1`.

### Task 4 — Add a new curated tag

**When:** an enrichment tag has no good curated equivalent but it's a category worth promoting (e.g. a new role, scenario, or genre that recurs).

1. Add an entry to `reference/tags.yaml` under the appropriate category. Choose a lowercase, hyphenated name. Add a description.
2. Re-run `LoadActressCommand` (or whatever loads tags.yaml into the `tags` table) so the new tag is available.
3. Re-run the propose script — the new curated tag is now a valid target for `curated_alias`. Add the mapping in the proposal file or update `SEMANTIC_MAP` in the script.

### Task 5 — Update the script's `SEMANTIC_MAP`

**When:** you keep manually mapping the same enrichment tag → curated tag in the proposal review step. Bake it in.

Edit `scripts/seed_enrichment_tag_aliases.py`. Two dicts:
- `SEMANTIC_MAP` — strong, unambiguous mappings. The script auto-bucket these into `high_confidence`.
- `REVIEW_MAP` — weaker mappings flagged for review. The script auto-bucket these into `review` so they don't get applied silently.

After editing, regenerate the proposal — your new mapping appears in the right bucket.

## Quick SQL reference

```sql
-- Top unmapped tags by impact
SELECT name, title_count
FROM enrichment_tag_definitions
WHERE curated_alias IS NULL
ORDER BY title_count DESC LIMIT 20;

-- Near-universal candidates (>= 70% of enriched titles)
SELECT name, title_count,
       ROUND(100.0 * title_count /
             (SELECT COUNT(*) FROM title_javdb_enrichment), 1) AS pct
FROM enrichment_tag_definitions
ORDER BY title_count DESC LIMIT 20;

-- Distribution of tags per title
SELECT n_tags, COUNT(*) AS titles FROM (
    SELECT title_id, COUNT(*) AS n_tags
    FROM title_enrichment_tags GROUP BY title_id
) GROUP BY n_tags ORDER BY n_tags;

-- Sanity check: title_count denorm matches actual
SELECT COUNT(*) AS mismatched_definitions
FROM enrichment_tag_definitions etd
WHERE etd.title_count != (
    SELECT COUNT(*) FROM title_enrichment_tags WHERE tag_id = etd.id);

-- See aliases set
SELECT name, curated_alias, title_count
FROM enrichment_tag_definitions
WHERE curated_alias IS NOT NULL
ORDER BY title_count DESC;
```

## What the in-app Tag Health view shows

Tools → **Tag Health** is the human-readable equivalent of the SQL above:

- **Header summary:** total enriched titles, mapped vs unmapped definition counts, suppressed count, plus drift signals: "N unmapped tags with ≥ 3 titles" and "N near-universal (≥ 70% of library)" — both highlighted yellow when non-zero.
- **Filter controls:** name search, "Unmapped only" toggle, "Show suppressed" toggle.
- **Table:** every enrichment tag definition with title count, % of library, curated alias (or em-dash if unmapped), and surface toggle. Suppressed rows are dimmed.
- **Live toggles:** flipping a surface switch writes to the DB immediately and the Discovery faceted picker reflects it on next refresh.

This is the first place to look during routine maintenance — it summarizes the full state of the system in one screen.

## Surfacing UI quirks worth remembering

- **Filter is per-actress** — selecting a different actress wipes filter state.
- **Tag conjunction is AND** — never OR. Multiple tags narrow the result.
- **Faceted picker re-ranks** by *conditional count* after each selection (counts of currently-matching titles that have each tag).
- **Selected chips stay sticky** — they remain in the picker even if their conditional count drops out of the top-30.
- **Un-enriched titles are hidden** when any filter is active (they can't possibly match an enrichment predicate).
- **Counts are honest:** "X of N enriched titles" — never claim whole-library coverage from enrichment-only filters.

## What's deferred

- **Cross-library ("B") surfacing screen** — same filter axes, but unscoped to one actress. The `curated_alias` bridge is what makes this useful at the library scale.
- **Maker / release-date / tag-exclusion** as first-class filter axes — backend supports them via similar predicates; UI hasn't been added.
- **Surface flag visualization in Discovery** — currently the picker just hides suppressed tags silently. Could surface a "12 hidden" affordance.
- **Auto-prompt** when unmapped count crosses a threshold.

These are tracked informally in this file and in the proposal's "Open Questions" section.

## Branch + commit history (for context)

```
22141f9 Phase 3c — curated_alias seeding script + initial proposal
452d037 Phase 3 MVP — per-actress surfacing filter (tags + rating)
cb2ba5a Phase 2 — cut writers and readers over to title_javdb_enrichment
a40e1ca Phase 1 — title_javdb_enrichment + normalized tag tables
48f71ac Spec: tag system revamp — enrichment-backed surfacing
```
