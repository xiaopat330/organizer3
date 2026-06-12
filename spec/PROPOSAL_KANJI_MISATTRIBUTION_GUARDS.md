# PROPOSAL: Kanji Misattribution Guards & Standing Attribution Health

**Status:** Draft 2026-06-12 — for review/consideration, no implementation at this time.
**Origin:** The enrichment-attribution audit of 2026-06-12 (`reference/enrichment_audit/ENRICHMENT_ATTRIBUTION_AUDIT.md`). Two ad-hoc scans surfaced 13 enriched actresses whose javdb `cast_json` did not contain their kanji — 12 confirmed problems (wrong-kanji binds, conflations, stray credits, a consolidation) plus 1 false positive. The audit was a one-off manual session driven by hand-written SQL; every check it ran already exists as logic somewhere in the codebase, but it runs **on-demand, after attribution, only when invoked**. This proposal turns that after-the-fact audit into attribution-time guardrails and a standing health check, and schedules the one strategic fix that removes the audit's dominant false-positive class.

**Relationship to existing tools:** This is not net-new detection logic. `find_enrichment_cast_mismatches` already performs the exact "is the actress's kanji in this title's `cast_json`" check; `find_suspect_credits` already does the co-occurrence check; the gender filter (`CastJsonFilter` / `DraftPopulator.writeCastSlots`) already demonstrates the cheap skip-guard pattern. The gap is **timing and automation**, not capability.

---

## 1. Goal & Scope

Prevent kanji-based actress misattribution from landing silently, by:

1. Closing the **YAML-loader phantom gap** (load-time silent actress creation on romaji mismatch).
2. Adding a **kanji-presence guard at draft promotion** (enrich-time silent wrong-bind).
3. Making the audit **standing** — wiring the existing mismatch/suspect-credit checks into automatic reporting instead of manual invocation.
4. Scheduling **multi-slug-per-actress** support — the schema change that eliminates the rename-chain false-positive class.

**In scope:** `ActressYamlLoader`, the draft promotion path, the revalidation cron / review-queue producers, and `javdb_actress_staging` schema. **Out of scope:** the comp-under-listing confound (javdb data incompleteness — not fixable here; documented as a known caveat), and any UI surface beyond review-queue rows.

---

## 2. Motivation — what the audit traced back to in code

| Audit failure mode (examples) | Root cause | File |
|---|---|---|
| **Wrong-kanji binds** — 1059 male 鮫島 bound; 420 緒川はる→笹倉杏 | `autoLinkActress` Passes 3–4 link by **slug** or **fuzzy curated romaji** and never verify the actress's kanji appears in *this* title's `cast_json`. Promotion inserts with no kanji check. | `DraftPopulator.autoLinkActress:349–414`; `DraftPromotionService.insertTitleActresses:884–942` |
| **Loader phantoms** — Karen Tojo spawned #7331 | `ActressYamlLoader.apply` binds by `canonical_name`/`alias` only. It passes `stage_name` into `resolveByName`, but that method ignores the kanji `stage_name` column. No match → silent phantom create. (Note: enrichment Pass 2.5 *does* bind by kanji — an asymmetry.) | `ActressYamlLoader.apply:240–263`; `JdbiActressRepository.resolveByName:186–204` |
| **Rename-chain false positives** — the dominant audit noise (1071, 2286, 772, 1084, 1623) | `javdb_actress_staging` stores **one slug per actress** (PK=`actress_id`, UNIQUE `javdb_slug`). A multi-name actress can only hold one slug, so her other-name titles always read as "mismatched." | `JavdbStagingRepository` (ON CONFLICT(actress_id)) |
| **Comp under-list noise** | javdb lists only headliners on compilations; nothing models partial casts. | (data confound — out of scope) |

The kanji-in-cast check the audit ran by hand is `find_enrichment_cast_mismatches`. The gender filter — a one-line `if (!"F".equals(entry.gender())) continue;` at `DraftPopulator.writeCastSlots:315` — is the precedent for the guard this proposal wants: cheap, applied at the moment of attribution.

---

## 3. Proposed changes

### Item A — Close the YAML-loader phantom gap *(cheapest, clearly correct)*

**Problem:** Any profile YAML whose `profile.name` romaji ≠ DB `canonical_name`, with no matching alias, silently creates a new LIBRARY actress — even when the kanji `stage_name` exactly identifies an existing row. This produced the Karen Tojo phantom (#7331) on batch-load #1; the workaround was post-hoc SQL column-copy + `merge_actresses`.

**Fix:** In `ActressYamlLoader.apply()`, before the create-new branch, add a `findByStageName(kanji)` fallback (the repo already has a `stage_name`-direct query used by enrichment Pass 2.5 — reuse it). Bind **only when exactly one** kanji match exists; on 0 or ≥2 matches fall through to today's behavior (create, or surface a warning). The kanji-dup sweep (`project_kanji_dup_detection`) confirmed ~0 exact `stage_name` collisions, so single-match binding is safe.

**Why guarded by exactly-one:** kanji collisions, while rare, exist (watchlist pairs in the dup sweep). A blind kanji bind could over-merge two distinct people; the count guard makes the change strictly safer than today and never auto-merges an ambiguous name.

**Cost:** ~20 lines, contained to the loader. No schema change. **Risk:** low.

**Pre-mitigation already in use:** the batch-load pre-check (`YAML romaji == DB canonical OR pre-add alias`) — this item makes that check a safety net rather than the only line of defense.

### Item B — Kanji-presence guard at draft promotion *(highest value)*

**Problem:** Slots resolved via the slug pass (Pass 3) or the fuzzy curated-romaji pass (Pass 4) are inserted into `title_actresses` at promotion with no verification that the chosen actress is actually in the title's cast. This is how 420 and 1059 became wrong-kanji binds. The corrective scan exists, but only runs if someone later remembers to call it.

**Fix:** In the promotion path, before `insertTitleActresses`, for each slot resolved via a *non-kanji* pass (slug / fuzzy / curated), check whether the actress's `stage_name`, `alias_name`(s), or `alternate_names_json` appear in this title's `cast_json` (whitespace-stripped LIKE — the same comparison `FindEnrichmentCastMismatchesTool:107–122` already uses). On miss → **route the slot to the review queue** (existing `review_queue` infrastructure) instead of attributing. Slots resolved by an exact kanji/canonical/alias match (Passes 1, 2, 2.5) are inherently in-cast and skip the check.

**Design notes:**
- This is a *soft* guard — it diverts to review, it does not drop. No data is lost; a human confirms ambiguous binds.
- It must tolerate the **comp confound**: on titles with a large/compilation cast, javdb under-lists, so a legitimate actress can be genuinely absent from `cast_json`. Suggested mitigation: only enforce on small casts (e.g. ≤ N female slots), or tag review rows with a "possible-comp" hint rather than blocking. Tune N against the audit's `nfem BETWEEN 1 AND 3` heuristic.
- Modeled structurally on the gender-filter skip — same place in the pipeline, same cheap predicate.

**Cost:** medium-small, contained to the promotion path + a review-queue row type. **Risk:** medium — needs the comp-confound tuning to avoid review-queue spam.

### Item C — Make the audit standing, not manual *(medium value, high reuse)*

**Problem:** The whole audit was a manual session. New misattributions introduced after it will not surface until the next manual sweep.

**Fix:** Wire the two existing read-only detectors into automatic reporting:
- Run `find_enrichment_cast_mismatches` and `find_suspect_credits` after each enrichment batch (or on the existing `RevalidationCronScheduler` cadence), emitting results as `review_queue` rows or a periodic report artifact under `reference/enrichment_audit/`.
- Dedupe against already-resolved rows so the same rename-chain false positives don't re-surface every run (the audit's confirmed-FP list — 1071, 2286, 772, 1084, 1623 — should be suppressible).

**Cost:** small — orchestration around tools that already exist. **Risk:** low. Main design question is *where* the output lands (review queue vs. report file vs. dashboard pill) — defer to review.

### Item D — Multi-slug per actress *(high value, real architecture change)*

**Problem:** The single-slug constraint is the root of the **dominant** audit false-positive class. A renamed actress (multiple javdb slugs across studios, one stored) shows permanent cast-mismatch on every title filed under a name other than the stored slug's. This both (a) generated most of the audit's noise and slowed it down, and (b) breaks discovery — `find_actress_titles`-style lookups only ever see one name's filmography. Today's workaround is "store the dominant-enriched name's slug and accept the others look mismatched."

**Fix (sketch — full design deferred):** Promote `javdb_actress_staging` from one-row-per-actress to a one-to-many `actress_id → slug` relation (new join table or relax the PK), with one slug flagged primary. Update every slug-lookup site (Pass 3 anchor, `backfill_actress_slugs_from_cast`, revalidation, the slug-duplicate detector) to consider all of an actress's slugs. The mismatch scan then treats a title as matched if *any* of the actress's slugs/names is in cast.

**Cost:** large — schema migration + every slug consumer. **Risk:** medium-high blast radius. **Recommendation:** schedule deliberately as its own effort, *after* A/B/C, precisely because it removes the noise that makes audits like this slow — but it is not the first thing to build.

**Related:** `project_rena_kodama_comeback_slug_rebind` (documents the 1-slug arch limit and the comeback/rename cases that motivated it); `project_slug_duplicate_detector_tool`.

---

## 4. Recommended sequencing

1. **A + B first** — together they close both silent-attribution doors (load-time phantoms and enrich-time wrong-binds) for low/medium cost and risk, and convert the manual kanji check into a guardrail that fires *before* bad data lands.
2. **C next** — makes whatever still escapes self-reporting, reusing existing detectors.
3. **D deliberately later** — the strategic fix that eliminates the rename-chain false-positive class and improves discovery, sequenced last for its blast radius.

Each item is independently shippable; none blocks another (D makes C's output cleaner but is not a prerequisite).

---

## 5. Out of scope / known residual

- **Comp under-listing** — javdb lists only headliners on compilations, so cast-mismatch and over-credit scans both false-positive on comps. Not fixable in this codebase; Item B must *tolerate* it (small-cast gating), not solve it.
- **pykakasi reading noise** — the audit's disagreement-scan (scan B) used pykakasi, which mangles name-specific readings and surnames; that scan is a *discovery* aid, not a guard, and is not proposed for automation. Readings continue to be set from canonical romaji, never pykakasi-literal.
- **Over-credited-titles cleanup** — the inverse problem (titles with extra stray credits) is deferred per the audit; its proper fix is a per-title javdb cast re-fetch tool, tracked separately in the audit report's SIDE-FINDING section.
- **Lower-confidence audit rows** (258, 643, 1176, 1179, 859, 2450, 2822) — still open, unrelated to this tooling.

---

## 6. References

- `reference/enrichment_audit/ENRICHMENT_ATTRIBUTION_AUDIT.md` — the audit that motivated this.
- Memory: `project_enrichment_attribution_audit`, `project_rena_kodama_comeback_slug_rebind` (1-slug arch limit), `project_kanji_dup_detection` (collision safety for Item A), `reference_cocredit_phantom_detection`.
- Code touchpoints: `DraftPopulator.autoLinkActress:349–414`, `DraftPopulator.writeCastSlots:315` (gender-filter precedent), `DraftPromotionService.insertTitleActresses:884–942`, `ActressYamlLoader.apply:240–263`, `JdbiActressRepository.resolveByName:186–204`, `FindEnrichmentCastMismatchesTool:93–123`, `FindSuspectCreditsTool`, `RevalidationCronScheduler`, `JavdbStagingRepository`.

---

## Appendix A — Context for an external reviewer

*This appendix is written for a reviewer (e.g. a more advanced model) who has **not** seen the originating conversation, this codebase's history, or its tribal knowledge. It is intended to be self-contained: read this proposal top-to-bottom plus this appendix and you should have everything needed to critique the design or help implement it. Everything below is background; the proposal itself is §§1–6.*

### A.1 What the system is

**Organizer3** is a single-user media-library manager for a large collection of Japanese adult videos (JAV) spread across ~10 NAS volumes accessed over SMB. It is a Java application (Javalin web UI + JLine3 shell + an MCP server for agent-driven maintenance), **no Spring** — all dependencies wired manually. Persistence is **SQLite via JDBI** with versioned migrations. There is no multi-user concern; the "user" and the maintenance agent (Claude, via the MCP tools) are the only writers.

The domain object of interest here is the **actress** and her **attribution** — i.e. which actresses are credited on which titles (videos). Getting attribution right is the core data-quality problem this proposal addresses.

### A.2 The data model (minimum needed to reason about this proposal)

- **`titles`** — one row per work, keyed by a product code (e.g. `IPX-633`, `STAR-829`). Has a `label` (the code prefix family), an optional filing `actress_id`.
- **`actresses`** — one row per performer. Relevant columns:
  - `canonical_name` — the **romaji** display name (e.g. "Karen Tojo"). This is the primary identity key for most lookups.
  - `stage_name` — the **kanji/Japanese** name (e.g. `東条かれん`). This is the form that appears in javdb cast data.
  - `tier` — popularity bucket (GODDESS / SUPERSTAR / POPULAR / LIBRARY / …), used for folder placement and campaign scoping.
  - `name_reading` — kana reading of the kanji.
- **`actress_aliases`** — alternate names (romaji or kanji) that should resolve to the same actress. Many lookups check `canonical_name` **then** `actress_aliases`.
- **`title_actresses`** — the attribution join table (title ↔ actress). **This is the table whose correctness the whole proposal is about.**
- **`title_javdb_enrichment`** — per-title scraped metadata from javdb. The critical column is **`cast_json`**: a JSON array of the cast as javdb lists it, each element `{slug, name, gender}` where `slug` is javdb's per-performer identifier, `name` is the **kanji** name, and `gender` is `"F"`/`"M"`/`"U"`. This is ground truth for "who is actually in this title" — modulo the comp confound (A.6).
- **`javdb_actress_staging`** — maps a local `actress_id` to **one** javdb `slug` (PK = `actress_id`, UNIQUE on `javdb_slug`). The single-slug constraint is central to Item D.

### A.3 Romaji vs kanji — why this is the whole problem

Every actress has two names that must stay in sync: a **romaji** canonical name (how the user reads/searches) and a **kanji** stage name (how javdb and the source files label her). Misattribution happens at the seams between these two namespaces:

- javdb cast is **kanji + slug**. Local identity is keyed on **romaji** canonical. Bridging them is fuzzy (transliteration is many-to-one and reading-dependent).
- Japanese AV performers **frequently rename** across studios (a "rename chain"): one human → several kanji names → several javdb slugs over a career. The DB models her as one actress with one slug, so titles under her other names look mis-attributed even though the credit is correct. **This rename-chain pattern was the single most common false positive in the audit** — distinguishing it from genuine misattribution required per-actress research (Wikipedia 改名 history, birthdate cross-checks, javdb "aka" links). Item D is the structural fix.
- **Product codes are reused across eras.** An old code can be re-issued years later for a different work with a different cast, so attaching credits "by code alone" (which the YAML portfolio loader does) can silently mis-credit.

### A.4 How attribution actually happens (the enrichment pipeline)

1. **Scrape** → javdb metadata for a title lands in `title_javdb_enrichment.cast_json` (kanji + slug + gender).
2. **Draft populate** (`DraftPopulator`) → for each **female** cast slot (males/unknowns skipped — the gender filter, our guard precedent), try to link it to a local actress via a **5-pass cascade**:
   - Pass 1: exact match on normalized `canonical_name`
   - Pass 2: exact match on `actress_aliases`
   - Pass 2.5: exact match on kanji `stage_name`
   - Pass 3: **slug-anchored** — match via `javdb_actress_staging.javdb_slug`
   - Pass 4: **curated fuzzy** — a blocking LLM call returns romaji for the kanji, then fuzzy-match that romaji to an actress
   - Pass 5: prefill / give up → leave the slot unresolved for the user
   **Passes 1, 2, 2.5 are kanji/name-exact (inherently in-cast). Passes 3 and 4 are the dangerous ones** — they can bind an actress whose name is **not** in this title's cast (slug reuse, fuzzy romaji collision). This is where the audit's wrong-binds (e.g. a male surname `鮫島` bound to a female actress; `緒川はる` fuzzy-collided onto `笹倉杏`) originated. **Item B** inserts a kanji-presence check here, at promotion.
3. **Draft promote** (`DraftPromotionService.insertTitleActresses`) → user confirms slot resolutions; rows written to `title_actresses`. **No kanji validation today.**

Separately, **actress profiles** are authored as YAML files (`src/main/resources/actresses/*.yaml`) and loaded by `ActressYamlLoader`, which binds a profile to an existing actress by **romaji canonical, then alias — never by kanji stage_name** — and **creates a new actress ("phantom") on no match.** When a YAML's romaji ≠ the DB's canonical (common after a rename), this spawns a duplicate. **Item A** adds the kanji fallback.

### A.5 How the audit was conducted (so a reviewer can reproduce / critique it)

Two complementary **read-only** scans, neither complete alone:

- **Scan A — small-cast cast-mismatch %.** For titles with 1–3 female cast members (`nfem BETWEEN 1 AND 3`, to dodge the comp confound), flag credited actresses whose `stage_name`/`canonical_name`/alias does **not** appear in that title's `cast_json` (via `json_each`). High mismatch % across an actress's titles ⇒ wrong-slug bind, conflation, or rename chain. This is exactly what `find_enrichment_cast_mismatches` does as a standing tool.
- **Scan B — canonical↔kanji romaji disagreement.** Transliterate the kanji `stage_name` with **pykakasi** and compare to `canonical_name`; large disagreement ⇒ candidate conflation. **Comp-immune but pykakasi-noisy** (it mangles name-specific readings and surnames), so it must be cross-checked against "own-kanji-in-cast %". Used only for discovery, never as a guard.

Outcome: 13 candidates → 12 confirmed problems (wrong-kanji binds, conflations, stray co-credits, one rename consolidation) + 1 false positive; plus a large tail of rename-chain false positives correctly *not* acted on. Full method SQL and per-actress dispositions are in `reference/enrichment_audit/ENRICHMENT_ATTRIBUTION_AUDIT.md`.

### A.6 Confounds a reviewer must keep in mind

- **Comp (compilation) under-listing.** javdb lists only headliners on compilation titles, so a legitimately-credited actress can be genuinely absent from `cast_json`. Both Scan A and the over-credit inverse scan false-positive on comps. **Item B must tolerate this** (small-cast gating / "possible-comp" hints), not pretend it away.
- **pykakasi unreliability.** Good enough for discovery, wrong often enough that it must never drive an automated write. Readings are set from canonical romaji, never pykakasi-literal.
- **Rename chains.** As above — a mismatch is *evidence*, not proof, of error; confirming requires external research. An automated guard should therefore **divert to human review, never auto-delete a credit.**
- **Single-slug arch limit.** Because an actress holds one slug, any per-slug check is blind to her other names until Item D lands.

### A.7 Environment & how to inspect (for an implementing reviewer)

- **Live database:** `~/.organizer3/organizer.db` (SQLite, WAL mode). The `data/*.db` files in the repo are 0-byte stubs — do **not** use them. Safe external read/write requires `PRAGMA busy_timeout`; timestamps in this DB are ISO-8601 microseconds with `Z`.
- **Build/run:** Gradle; `./gradlew installDist` then restart the app to pick up code or newly-added profile YAMLs. There is no hot reload for the loader.
- **Maintenance surface:** most data fixes happen through **MCP tools** (e.g. `merge_actresses`, `rename_actress`, `set_actress_aliases`, `remove_title_credit`, `reassign_title_credit`, `find_enrichment_cast_mismatches`, `find_suspect_credits`, `revalidate_enrichment`, `backfill_actress_slugs_from_cast`). A change shipped to the running binary requires a rebuild+restart before its tool is callable.
- **Specs to read first:** `spec/FUNCTIONAL_SPEC.md`, `spec/IMPLEMENTATION_NOTES.md`. Sibling proposals worth scanning for house style and adjacent concerns: `spec/PROPOSAL_RELOCATION_TOOLING.md`, `spec/PROPOSAL_CURATION_COMPLETION.md`.
- **Testing is mandatory and non-negotiable** in this project: new code must be modularized for testability; repository tests use real in-memory SQLite, command/tool tests use Mockito mocks. Any implementation of A–D is expected to ship with tests in that shape.

### A.8 Where reviewer input would be most valuable (open questions)

1. **Item B comp-gating threshold.** Is small-cast gating (≤ N female slots) the right way to tolerate the comp confound, or is there a more principled signal (e.g. javdb's own "compilation" tag, runtime, or title-type) that distinguishes a genuinely-absent legit credit from a wrong bind? What is a good N?
2. **Item B failure routing.** Divert-to-review-queue vs. block-promotion vs. attribute-with-a-low-confidence-flag — which best fits a single-user system where the same person triages? Risk of review-queue spam vs. risk of silent bad data.
3. **Item C output sink.** Where should standing audit findings land — `review_queue` rows, a periodic report file, a dashboard pill — and how should confirmed rename-chain false positives be permanently suppressed without hiding genuinely new problems?
4. **Item D schema shape.** Join table vs. relaxed-PK on `javdb_staging`; how to designate a "primary" slug; and the migration/backfill path for the ~thousands of existing single-slug actresses. This is the highest-blast-radius change and would most benefit from an experienced second opinion.
5. **Should Items A/B also retroactively scan existing data**, or only guard new writes? (The audit already cleaned the known historical cases, but the guards as specified are forward-looking.)
