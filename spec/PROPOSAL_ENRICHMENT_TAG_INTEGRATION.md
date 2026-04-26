# Phase 4 — Enrichment Tag Integration via `curated_alias`

## Status: Draft — Design Discussion

## Background

The enrichment tag system shipped in Phases 1–3 (see `spec/PROPOSAL_TAG_SYSTEM_REVAMP.md` and `spec/ENRICHMENT_TAG_OPS.md`) populates `enrichment_tag_definitions` with javdb-scraped tags and provides an opt-in `curated_alias` column that maps a subset of them to entries in `reference/tags.yaml`.

41 high-confidence aliases have been applied (Solowork → solo-actress, Big Tits → busty, Titty Fuck → paizuri, Best, Omnibus → compilation, etc.). 15 medium-confidence and 37 unmapped entries remain in the Phase 3c proposal file for human review.

**The bridge is populated but no code reads it.**

Today the existing curated-tag UIs surface only the curated system:

| UI surface | Source | Enrichment tags appear? |
|---|---|---|
| Actress detail "Tags" panel | `title_tags` ∪ `label_tags` | No |
| Title cards (`t.tags`) | `title_effective_tags` (direct + label) | No |
| Title detail tag badges | same | No |
| Title browse tag filter | joins `title_effective_tags` | No |
| Discovery faceted picker | reads `enrichment_tag_definitions` directly | Yes (raw names) |
| Tools → Tag Health | reads `enrichment_tag_definitions` directly | Yes (raw names) |

The result is two parallel tag worlds. The whole point of the `curated_alias` bridge is to fix this — with it wired, a curated tag like `busty` can light up across the entire library wherever the enrichment system has detected `Big Tits`, even on titles you haven't manually tagged.

## Goal

Make the `curated_alias` bridge load-bearing in the existing curated-tag UIs. After this phase:

- An actress's "Tags" panel includes any curated tags inferred from enrichment.
- A title card's tag badges include enrichment-derived curated tags.
- A user filtering Titles browse by `busty` matches every title carrying `busty` curatedly **plus** every enriched title carrying any enrichment tag whose `curated_alias = 'busty'`.
- The Discovery and Tag Health silos are unaffected — they continue to operate on raw enrichment tag names and remain the right tools for managing the enrichment vocabulary itself.

## Decided Direction: Extend `title_effective_tags` with a third source

The cleanest integration adds a single new value to the existing source enum and reuses every consumer:

```sql
-- Existing:
--   title_effective_tags.source CHECK(source IN ('direct', 'label'))
-- After:
--   title_effective_tags.source CHECK(source IN ('direct', 'label', 'enrichment'))
```

`title_effective_tags` is already the union table that every curated-tag UI joins against. Adding rows there causes the integration to fall out of every existing query path — no per-callsite changes.

### How the new rows are derived

For each enriched title, for each enrichment tag assignment whose definition has a non-null `curated_alias` that names a real curated tag (i.e. exists in `tags`), insert one row with `source='enrichment'`. SQL:

```sql
INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
SELECT tet.title_id, etd.curated_alias, 'enrichment'
FROM title_enrichment_tags tet
JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
WHERE etd.curated_alias IS NOT NULL
  AND etd.curated_alias IN (SELECT name FROM tags)
```

Notes:
- `INSERT OR IGNORE` makes the per-title primary key (`title_id`, `tag`) handle the case where a title already has the tag from a curated source — no double-counting, no duplicate badges.
- The `IN (SELECT name FROM tags)` guard skips aliases whose target curated tag isn't in the catalog yet (e.g. `reference/tags.yaml` references not yet loaded into the DB). This avoids polluting downstream queries with unknown tags.
- Multiple enrichment tags can map to the same curated tag (Big Tits → busty AND Busty Fetish → busty). The unique key ensures `busty` is inserted once per title.

### Result example

Suppose title `SSIS-256_4K` is enriched and carries enrichment tags `Big Tits, Solowork, Cowgirl`. The 41 existing aliases map them to curated `busty, solo-actress, cowgirl`. The user has also manually tagged the title with `compilation`.

`title_effective_tags` for that title:

| tag | source |
|---|---|
| compilation | direct |
| busty | enrichment |
| solo-actress | enrichment |
| cowgirl | enrichment |

(Plus any label-derived tags from `S1 NO.1 STYLE`, with `source='label'`.)

The actress's Tags panel, the title's badges, and any tag filter all see the union: `compilation, busty, solo-actress, cowgirl, …`.

## Lifecycle and Recomputation

`TitleEffectiveTagsService.recomputeForTitle` already does `DELETE-then-INSERT` for direct + label tags. Adding the enrichment source means adding a third INSERT inside the same method.

**Triggers — when to call recompute:**

| Event | Already triggers recompute? | Action needed |
|---|---|---|
| `title_tags` insert/update/delete | Yes | None |
| `label_tags` change | Yes (full recompute) | None |
| **Enrichment row inserted/replaced** | **No — new** | Wire `JavdbEnrichmentRepository.upsertEnrichment` to call `recomputeForTitle` |
| **Enrichment row deleted** | **No — new** | Wire `JavdbEnrichmentRepository.deleteEnrichment` to call `recomputeForTitle` |
| **`curated_alias` set or cleared** | **No — new** | Wire the seed script's `--apply` mode to call `recomputeForTitles` for all titles carrying the affected tag |
| `surface` flipped | Doesn't affect bridge | None |

The dependency direction is `JavdbEnrichmentRepository → TitleEffectiveTagsService`. The repo is in `com.organizer3.javdb.enrichment`; the service is in `com.organizer3.db`. The repo can take the service as a constructor dependency.

For the seed script, two options:
1. Have the script execute its own `recomputeForTitles` SQL inline (adds the recompute logic to two places).
2. Have the script set the alias values, then expose a separate "rebuild effective tags" CLI command that the user runs after applying.

**Option 2 is cleaner.** The script remains a pure data tool; the rebuild happens through a Java code path (`TitleEffectiveTagsService.recomputeAll`) that already exists and is tested. After applying aliases, the user runs the rebuild step.

## UI Implications

**Default behavior:** the integration is silent. Tag panels and badges show a unified list; users cannot distinguish which tags came from where. This matches the "alias asserts equivalence" semantic and avoids cluttering the UI with provenance noise that 99% of users don't care about.

**Optional follow-up — source visibility:** if you ever want to see provenance, two cheap additions:
- API: include the `source` column in the per-tag responses (already in `title_effective_tags`).
- UI: color or icon-mark enrichment-derived badges. CSS-only.

Recommend shipping this proposal without source visibility — add it later if a real need emerges.

## Migration Path

1. **Schema migration v26** — `ALTER TABLE title_effective_tags` to relax the CHECK constraint to include `'enrichment'`. SQLite quirk: CHECK constraints can't be modified in place; the migration drops and recreates the table preserving data.

2. **Code changes:**
   - Add a third INSERT to `TitleEffectiveTagsService.recomputeForTitle` and `recomputeAll`.
   - Inject `TitleEffectiveTagsService` into `JavdbEnrichmentRepository`; call `recomputeForTitle` at the end of `upsertEnrichment` and `deleteEnrichment`.
   - Add a CLI command (or extend an existing one) `rebuild-effective-tags` so the seeding script's user can re-derive after `--apply`.

3. **One-shot backfill** — at end of v26 migration, call the new full-rebuild against the existing data so the integration is live immediately on upgrade.

4. **Tests:**
   - `TitleEffectiveTagsServiceTest` gains coverage for: (a) enrichment alias produces a row, (b) un-aliased enrichment tag is skipped, (c) alias to non-existent curated tag is skipped, (d) duplicate tag from multiple sources is deduped, (e) enrichment row deletion clears the enrichment-source rows.
   - `JavdbEnrichmentRepositoryTest` gains coverage for the recompute call.
   - End-to-end test: enrich a title with a known tag set, assert the actress's Tags API includes the aliased curated values.

## Trade-offs

### Blurred distinction (deliberate)

After Phase 4, a user looking at a title's tag badges cannot tell at a glance whether a tag came from their curation or javdb's enrichment. The alias asserts they mean the same concept; the UI honors that.

If this is the wrong call for any specific surface (e.g. an "edit my tags" view should clearly show what's user-managed vs derived), source-aware rendering is a small follow-up. **Don't pre-build it.**

### Coupling the enrichment write path to the effective-tags service

`JavdbEnrichmentRepository.upsertEnrichment` becomes responsible for triggering the recompute. This adds a dependency direction across packages. Worth it because the alternative — a periodic batch job — would create eventual-consistency windows where badges on a title don't reflect what was just enriched. Synchronous is right for this scale.

### `title_effective_tags` grows

For every aliased enrichment tag on every enriched title, one new row is added to `title_effective_tags`. With ~5–10% of titles enriched and ~5 aliased tags per enriched title, this adds maybe a few thousand rows on the current corpus, and a couple hundred thousand at full library scale. Trivial; the table is already indexed appropriately.

## What This Does NOT Change

- **Discovery faceted picker** — still reads from `enrichment_tag_definitions` directly, still uses raw enrichment names. The picker's purpose is enrichment-vocabulary management, not curated-tag browsing.
- **Tools → Tag Health** — same. It manages enrichment definitions, not effective tags.
- **Curated tag editor** (`PUT /api/titles/{code}/tags`) — operates on `title_tags` (direct) only. Users cannot edit enrichment-derived effective rows; they can only un-derive them by editing `curated_alias` in the seed script.
- **`reference/tags.yaml`** — authoritative source of curated taxonomy is unchanged. Aliases point INTO it, not away from it.
- **Phase 3c seeding script** — operates on `enrichment_tag_definitions.curated_alias` only; no schema change. The user's workflow is unchanged.
- **AV Stars system** — out of scope. Its parallel tag tables stay parallel.

## Open Questions

1. **Surface visibility — keep blurred?** Recommendation: yes, ship blurred; add source-aware rendering later only if needed. Confirm.

2. **Bulk-rebuild trigger after `--apply`** — should the seed script automatically run the rebuild as a final step (one less user action), or stay as a separate step (more explicit)? Recommendation: separate, but print a hint at the end of `--apply` saying "now run `rebuild-effective-tags`".

3. **What about enrichment tags whose `curated_alias` points to a tag that's in `reference/tags.yaml` but hasn't been loaded into the `tags` table?** The script's `--apply` already warns about this. The integration's `IN (SELECT name FROM tags)` guard silently skips those rows. Should we instead auto-load `tags.yaml` into `tags` as part of `--apply`, or rebuild? Recommendation: leave separate — tag loading is its own existing concern.

4. **Editing tags in the title editor** — the editor currently writes to `title_tags`. After Phase 4, a title may show `busty` in its badges purely from enrichment derivation. If a user clicks "Tags" to edit and removes `busty`, what happens? The direct row gets removed (or stays absent), but the enrichment-derived row keeps showing it. Does the user need to know they can't "really" remove it from there? Recommendation: out-of-scope for this proposal; tag editor remains direct-only and the UX clarification can come later if it confuses users.

5. **`title_effective_tags` rebuild perf at full library scale** — the existing `recomputeAll` is single-threaded, per-title. With ~50K titles eventually, this could take minutes. We may need to convert the bulk rebuild to a single bulk-INSERT-from-SELECT statement. Recommendation: the new INSERT can be written as a single bulk statement now (the existing per-title pattern is for incremental recompute). Worth doing in this phase.

## Estimated Scope

- Schema migration: ~30 lines
- `TitleEffectiveTagsService` changes: ~20 lines + tests
- `JavdbEnrichmentRepository` wiring: ~10 lines + tests
- New CLI rebuild command: ~30 lines + tests
- Tests: extension of two existing test classes + one end-to-end
- Backfill via migration: handled by calling the new bulk rebuild at end of v26

Half a day, ship in a single PR.

## Resolved (Not Open)

- **Storage architecture** — extend existing `title_effective_tags` enum, do not create a parallel table. Decided because the alias bridge asserts semantic equivalence; downstream consumers should see one unified set.
- **Atomic vs eventual consistency** — synchronous recompute on the enrichment write path. Decided because library is single-user and write volume is modest.
- **Alias targets that don't exist in `tags` table** — silently skip in the integration query. Decided because tag-table loading is a separate concern with its own warnings.
- **Per-title vs bulk recompute** — incremental on enrichment write, bulk on alias change. Decided because the alias change affects many titles at once but a single enrichment write only affects one.
