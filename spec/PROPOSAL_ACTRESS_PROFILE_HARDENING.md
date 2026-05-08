# PROPOSAL: Actress Profile Pipeline Hardening

**Status:** Draft
**Date:** 2026-05-08
**Context:** Triggered by the Rima Arai (id 2276) / Himari Hanazawa (id 771) incident — a single mis-linked multi-cast collection corrupted an actress's `stage_name`, which then claimed the wrong slug in `javdb_actress_staging`, locking the real owner out via the unique-slug constraint. Triage of the broader pipeline surfaced ~659 reported cast-mismatches that were largely false positives, plus several smaller failure modes (whitespace drift, kanji-form mismatch, duplicate actress records, etc.).

This proposal enumerates eight hardening options observed during that triage, prioritized by impact, with recommendations for which to ship.

---

## Failure modes observed

| Class | Example | Affected | Resolved how |
|---|---|---|---|
| Whitespace in `stage_name` | `椎名 そら` vs cast `椎名そら` | 4 actresses (228+126+28+17 = 399 spurious mismatches) | Manual SQL fix |
| Wrong slug bound to actress | id 771 staged with `E2vOx` (Rima Arai's slug) | 1 confirmed (771); 1 likely (3867) | Manual reset + backfill |
| Stage_name out of sync with javdb's canonical kanji | id 107 Aika `愛佳` vs cast `AIKA` | 5 actresses (~489 spurious mismatches) | Manual update |
| Traditional vs simplified kanji | `三上悠亜` vs cast `三上悠亞` | 1 actress (126 mismatches) | Manual update |
| Duplicate actress records | 771 "Himari Hanazawa" + 2302 "Himari Kinoshita" | 1 confirmed pair | Manual merge |
| No staging row, no cast corroboration | 6 actresses with low enrichment ratios | 6 actresses | Deferred (waiting for solo enrichment) |
| Naive mismatch detection | `find_enrichment_cast_mismatches` only checks `stage_name = cast.name`, ignores aliases | ~1,000 false positives | N/A |
| Blind AI lookup writes | `searchStageName` wrote any kanji Claude returned | Caused the 771 corruption | Fixed in PR (cast-corroboration guard, ≥2 hits) |

---

## Hardening options

### Option 1: Normalize `stage_name` on write
**What:** Strip whitespace and apply Unicode NFC at the repository write path (`JdbiActressRepository.setStageName`, `updateProfile`). One-shot migration for existing rows.

**Why:** The whitespace drift (Bucket 2 of triage) accumulated silently — nothing rejected `椎名 そら` when it was first written. Both javdb's cast_json and our equality predicate use exact-match string comparison, so any whitespace divergence becomes a permanent false-positive mismatch.

**Cost:** ~1 hour. Single trim + normalize in one location, plus a backfill query and one regression test.

**Risk:** Very low. Pure data normalization; no semantic change.

**Impact:** Prevents this class entirely from re-emerging. Cleans up ~399 existing false positives. Foundation for Option 6.

**Recommendation: SHIP.** Smallest possible change with the highest defensive value.

---

### Option 2: Surface slug conflicts in `backfill_actress_slugs_from_cast`
**What:** Catch UNIQUE-constraint violations per row (instead of aborting the batch) and write to `enrichment_review_queue` with reason `slug_conflict`. Surface in the review UI: "Actress X cannot claim slug Y; already owned by actress Z."

**Why:** The Rima Arai incident was invisible until the user manually noticed her catalog wasn't enriching. The backfill silently skipped her because slug `E2vOx` was already taken by actress 771. With this change, the conflict would have appeared in the review queue immediately on the first sync that picked up her cast_json.

**Cost:** ~2 hours. Wrap the insert in try/catch on `SQLITE_CONSTRAINT_UNIQUE`, write to review queue, continue batch. New review-queue UI handler.

**Risk:** Low. Pure surfacing of an error condition that's currently silent.

**Impact:** Eliminates the silent-failure class. Future actress slug conflicts get human review within one sync cycle instead of indefinitely.

**Recommendation: SHIP.** Closes the most painful silent-failure mode.

---

### Option 3: Make mismatch detection alias-aware
**What:** Update `find_enrichment_cast_mismatches` (and the analogous SQL we used during triage) to also check `actress_aliases` when matching cast_json names — not just `stage_name`. A cast entry counts as "matching" if its name equals `stage_name` OR any registered alias.

**Why:** ~80% of the original 659 reported "mismatches" were actresses with the correct slug bound but a `stage_name` that differs from the alias-form javdb uses (e.g., id 107 Aika: stage=`愛佳`, alias=`AIKA`, cast=`AIKA`). These are not mismatches at all — the alias relationship is documented. The naive query made the triage 5x more work than necessary.

**Cost:** ~1 hour. Add a `LEFT JOIN actress_aliases` to the EXISTS subquery. Update the corresponding MCP tool.

**Risk:** Low. Loosens the predicate, so existing callers will see fewer rows, not more.

**Impact:** Drops false-positive count by ~80%. Future triage operators (human or agent) start with a clean signal. Saves significant time on the next pass.

**Recommendation: SHIP.** Without this, every periodic mismatch sweep will repeat the same noise.

---

### Option 4: Re-run AutoPromoter Rule 3 on existing staging rows
**What:** A one-shot Utilities task that iterates all `javdb_actress_staging` rows and invokes `AutoPromoter.promoteFromActressProfile(actressId)`. Specifically: if the actress's `stage_name` has CJK but doesn't appear in `name_variants_json`, enqueue a `stage_name_conflict` review row.

**Why:** Rule 3 was added in a later schema version but never back-applied. We have ~30 actresses currently in this state (the Bucket 1(a)/(b)/(c) cohort, several of which we've now fixed). Only 4 `stage_name_conflict` rows exist in the queue; should be ~30+.

**Cost:** ~2 hours. Idempotent task, safe to re-run. Mostly wiring.

**Risk:** Very low. Read-mostly with a controlled write to the review queue.

**Impact:** Surfaces all existing wrong-stage-name conditions for review in one pass. Reduces human-spot-checking load going forward.

**Recommendation: SHIP.** Cheap, idempotent, surfaces accumulated debt.

---

### Option 5: Detect duplicate actress records on alias collisions
**What:** When creating an actress with canonical_name X, check whether X is already an alias (or canonical name) of another actress. If yes, flag the collision instead of silently creating a duplicate row. Optionally: surface as a merge-candidate in the duplicate-triage flow.

**Why:** The 771/2302 duplicate ("Himari Hanazawa" with alias "Himari Kinoshita" + separate id 2302 "Himari Kinoshita") existed for an unknown duration before triage. Almost certainly other duplicate pairs exist that would benefit from the same check.

**Cost:** ~3 hours. Pre-create check in `JdbiActressRepository`, plus a one-shot scan to find existing collisions, plus a UI hook to merge them.

**Risk:** Medium. Incoming sync flows can create actresses; a too-strict block here could halt a sync. Should warn-and-continue rather than reject.

**Impact:** Prevents future duplicates. The one-shot scan likely surfaces a handful of existing pairs.

**Recommendation: SHIP, but as warn-not-reject.** Block on creation feels too strict given sync volume; logging + review queue is better.

---

### Option 6: Health check for normalization drift
**What:** A health-dashboard probe that flags actresses where `stage_name` doesn't NFC-equal-modulo-whitespace any entry in their staging `name_variants_json`. Surfaces both whitespace drift (covered by Option 1) and kanji-form drift (e.g., 三上悠亜 vs 三上悠亞).

**Why:** Catches the Yua Mikami case (one-codepoint-different traditional vs simplified) which Option 1 alone can't detect. Provides ongoing visibility — Option 1 fixes write-path; this one finds drift introduced via other paths (YAML loaders, migrations, manual edits).

**Cost:** ~3 hours. Health check class + ~10 lines of logic + UI surfacing.

**Risk:** Very low. Read-only diagnostic.

**Impact:** Ongoing visibility. Catches drift sources that bypass repository validation.

**Recommendation: SHIP after Option 1.** Complementary, not redundant.

---

### Option 7: Surface rejection reason in `searchStageName` API response
**What:** The route currently returns `{stageName: null}` whether Claude said "unknown" or the new corroboration guard rejected. Add a `reason` field: `"unknown"`, `"low_corroboration"`, `"actress_not_found"`. UI displays the specific message.

**Why:** The user reported "i've failed many times to capture her profile from javdb" precisely because the UI gave no diagnostic feedback. Even with the guard in place, the user can't tell whether to: (a) try again later, (b) override manually, or (c) investigate something else.

**Cost:** ~1 hour. Modify `searchStageName` to return a result object instead of `Optional<String>`, update the route handler, update the JS to display the reason.

**Risk:** Low. Surface change only.

**Impact:** Shifts unknown-cause failures into actionable feedback. Removes a recurring user-frustration source.

**Recommendation: SHIP.** Tiny effort, real UX improvement.

---

### Option 9: Manual stage_name edit endpoint + UI input
**What:** `PUT /api/actresses/{id}/stage-name` with body `{stageName: "..."}` that runs through the same normalization (Option 1) and writes directly. Add a small text-input + save button next to the existing "AI lookup" button on the actress detail page.

**Why:** There is currently no UI path to set `stage_name` manually. The only write path is `/stage-name/search` (Claude-driven), which can fail (Rima Arai original incident) or now be rejected by the cast-corroboration guard (low-enrichment actresses). Without a manual override, the new guard is pure friction for cases like Rima Arai where the user already knows the right kanji from cast_json or external knowledge.

**Cost:** ~1 hour. PUT route + JS input + reuse existing `setStageName` repo method.

**Risk:** Low. Adds a write path that mirrors what direct SQL has been doing throughout this triage.

**Impact:** Closes the loop. With the cast-corroboration guard rejecting bad AI guesses, users need a way to set the right value when they know it. This is the escape hatch.

**Recommendation: SHIP — arguably first.** Without this, every refinement to the AI lookup just shifts where the user gets stuck.

---

### Option 8: Watchlist for low-enrichment-ratio actresses
**What:** Health metric: actresses with ≥50 titles and <5% enrichment ratio. Surface in dashboard. Drill-down shows their enriched + unenriched titles.

**Why:** During triage, several Bucket 3 actresses (Rio Hamasaki 2/142, Sarina Kurokawa 5/167, Hina Nanase 2/56) had unusually low enrichment ratios. This suggests their solo titles are skipping the enrichment queue for some structural reason (unusual codes, javdb-missing labels, etc.). Surfacing the metric makes the structural problem visible.

**Cost:** ~3 hours. SQL for the metric + UI panel + drill-down.

**Risk:** Very low.

**Impact:** Diagnostic only. Doesn't fix anything directly, but exposes a class of issues currently invisible.

**Recommendation: DEFER.** Lower priority than the others. Useful but not on the critical path.

---

## Recommended slate

Ship **Options 9, 1, 3, 4, 2, 7** as a single hardening release:

| Order | Option | Effort | Impact | Why this slot |
|---|---|---|---|---|
| 1 | **#9 manual stage_name edit** | 1h | Unblocks user when AI fails or guard rejects | Without this the guard is pure friction |
| 2 | **#1 normalize on write** | 1h | Prevents Bucket 2 recurrence | Foundation; everything below assumes clean data |
| 3 | **#3 alias-aware detection** | 1h | -80% false-positive noise | Makes #4's output meaningful |
| 4 | **#4 Rule 3 backfill sweep** | 2h | Surfaces ~30 existing issues | Cheap, idempotent, accumulated debt |
| 5 | **#2 slug-conflict surfacing** | 2h | Eliminates silent-skip class | Closes the worst silent-failure path |
| 6 | **#7 searchStageName reason field** | 1h | Better UX feedback | Tiny effort, real win |

**Total:** ~8 hours. **Combined impact:** unblocks the manual override path, prevents three failure modes outright, eliminates one silent-failure mode, makes ongoing triage 5x cheaper.

**Defer:** Options 5, 6, 8 — each is valuable but adds another half-day and isn't blocking anything actively painful.

**Strongest opinions:**
- **#9 was underweighted in the initial draft.** Without a manual stage_name edit, every refinement to the AI lookup just shifts where the user gets stuck. Should ship first.
- **#3 is the biggest force-multiplier per hour spent.** It doesn't fix anything directly but makes every future triage tractable.
- **#2 is the most uncomfortable to skip.** Today's silent-skip cost ~half this session.
- **#4's marginal value is half-spent** because the top mismatchers were manually fixed during this triage. Still worth running for accumulated debt.

## Follow-ups identified during implementation

- **Tighten `stage_name` and `alternate_names_json` predicates in `MISMATCH_WHERE`** to use `json_each` + exact `json_extract($.name)` comparison. Both currently use `REPLACE(cast_json,' ') LIKE '%name%'` substring matching against the raw JSON blob — same brittleness class that motivated the alias predicate tightening (commit `33b1bd6`). For short stage_names or alternate names, this can spuriously suppress real mismatches. Affects the same three callers: `FindEnrichmentCastMismatchesTool`, `EnrichmentProvenanceBackfillTask`, `EnrichmentClearMismatchedTask`. Estimated effort: 30–45 min.

- **UI button for `javdb.autopromote_rule3_sweep`** (Option #4). Task is registered in `TaskRegistry` and runnable via `start_task` MCP / `POST /api/utilities/tasks/.../run`, but no Utilities sub-page card exists yet — each task in this codebase is hand-wired into a specific UI page. Most natural home: `utilities-library-health.js` (already imports `task-center`, hosts diagnostic-style buttons). Estimated effort: 20 min.

## Out of scope / explicit non-goals

- Redesigning the enrichment queue, slug discovery algorithm, or staging schema.
- Fixing Bucket 3 (the 6 low-enrichment actresses); their fix is patience + the Option 1/2/3 hardening + the existing cast-corroboration guard.
- Changes to javdb scraping or rate limiting.

## References

- Cast-corroboration guard already shipped: `ActressBrowseService.searchStageName` + 4 regression tests in `ActressBrowseServiceJdbiTest`.
- AutoPromoter Rule 3 implementation: `AutoPromoter.promoteFromActressProfile`.
- Triage SQL queries used: see session log dated 2026-05-08.
