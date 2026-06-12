# PLAN: Kanji Misattribution Guards & Standing Attribution Health

**Status:** PLAN v2, 2026-06-12 — generated from `spec/PROPOSAL_KANJI_MISATTRIBUTION_GUARDS.md` (spec v2) / PR #93. v2 revision: maximized safe parallelism — `CastPresenceCheck` split out as Task 1c (it has no dependency on Item E), 2-ui moved to Wave 1 (depends only on the pinned reason/detail contract), Wave 2 runs three agents in isolated worktrees; fixed Task 2's missing `Application.java` wiring permission.
**Source of truth:** the spec is authoritative. Where this plan quotes a pinned value (enum, reason string, gate, migration number), it is copied verbatim from spec §3; do not re-litigate.

## Execution model

- **Opus orchestrates from the main session.** Each task below (1a, 1b, 1c, 2-ui, 2, 3a, 3b) is dispatched to a **Sonnet subagent** via the Agent tool. Wave-mates are dispatched **in a single message** so they run concurrently.
- **Subagents are blind.** A subagent has NOT read the spec or this plan and cannot see this conversation. Each dispatch prompt is therefore fully self-contained: all needed design decisions, file paths, test matrices, and acceptance criteria are inlined.
- **Isolation:** Wave 1 agents have disjoint file sets and may share the working tree. **Wave 2 agents MUST run with `isolation: "worktree"`** — all three add constructor wiring to `Application.java`; the orchestrator merges their results sequentially (order in §1) and resolves the trivially-additive `Application.java` overlaps, re-running `./gradlew test` after each merge.
- **Subagents run `./gradlew test`** and report pass/fail with the failing-test names if any.
- **Subagents do NOT commit and do NOT modify files outside their listed set.** No subagent touches v1 legacy UI (`src/main/resources/public/modules/` outside `v2/` and `chrome/`, per `modules/LEGACY.md`).
- **Item D is excluded** (deferred design-doc task). v1 legacy UI is excluded.
- **No per-wave rebuild.** A single `./gradlew installDist` + app restart happens once at the end (§ Integration & rollout).

## House rules every subagent must honor

- No Spring; dependencies are wired manually in `Application.java`. New classes are constructor-injected.
- Testing is mandatory: **repository tests use real in-memory SQLite**; **service/command/tool tests use Mockito mocks**. No untestable shapes.
- Migrations: idempotent `applyVN()` in `SchemaUpgrader`, guarded by an exists-check (use the existing `addColumnIfMissing` helper for columns; `CREATE TABLE IF NOT EXISTS` for tables). Latest migration in the tree is **V66**; V67 = Item E, V68 = Item C. If a higher VN has landed at execution time, renumber and tell the orchestrator.

---

## 1. Wave plan

Two coding waves (maximum safe parallelism), then integration:

| Wave | Tasks | Parallelism | Depends on | Isolation |
|---|---|---|---|---|
| 1 | **1a** (Item A loader) ∥ **1b** (Item E provenance) ∥ **1c** (`CastPresenceCheck` standalone) ∥ **2-ui** (v2 rendering) | **4 concurrent** — fully disjoint file sets | — | shared tree OK |
| 2 | **2** (Item B promotion guard) ∥ **3a** (Item F portfolio guard) ∥ **3b** (Item C standing health) | **3 concurrent** | 2 needs **1b** (`resolved_via`) + **1c**; 3a needs **1a** (same loader file, merged) + **1c**; 3b needs **1b** merged (V67→V68 numbering) | **worktrees required** — all three wire deps into `Application.java` |
| final | integration: single rebuild + restart + one-time data seed + verification | — | all merged | — |

**Wave-1 disjointness:** 1a = `ActressYamlLoader` + commands; 1b = migration + `DraftPopulator`/`DraftPatchService` + slot entity/repo; 1c = one NEW class + its tests; 2-ui = `modules/v2/workflow/` JS only. No shared files.

**Wave-2 merge order: 2 → 3a → 3b.** The only expected overlap is additive constructor wiring in `Application.java` (and possibly shared test-fixture touch-ups). After each merge: resolve overlap, `./gradlew test`, then merge the next.

**Orchestrator verifies between waves:**
- After Wave 1: all four subagents report green; confirm `resolved_via` migration is `applyV67` (no collision); confirm `LoadResult` distinguishes created vs enriched; confirm `CastPresenceCheck` exposes BOTH `check(...)` and the public gate helper `guardEnforced(...)` (Tasks 2 and 3a precondition on them); confirm 2-ui touched only `modules/v2/workflow/`.
- After Wave 2 (per merge, in order): `./gradlew test` green; for Task 2 spot-read the diff to confirm the guard sits **before** the FIX 1a staging upsert and FIX 1b stage_name backfill inside `insertTitleActresses`, and that `purgeStale` exempts `guard_cast_mismatch`; for 3b confirm the migration is `applyV68` (renumber if anything else landed); confirm the two refactored find_* tools report behavior-unchanged.

---

## 2. Per-task dispatch prompts

### Task 1a — Item A: close the YAML-loader phantom gap

```
You are implementing one self-contained change in the Organizer3 Java codebase (Javalin + JDBI + SQLite, NO Spring; dependencies wired manually). Do NOT commit. Do NOT modify any file outside the set listed below. Run `./gradlew test` at the end and report results (name any failing tests).

OBJECTIVE
When an actress profile YAML is loaded, if its romaji name matches no existing actress but its kanji `stage_name` exactly identifies one existing live actress, bind to her instead of silently creating a duplicate ("phantom"). Also make actress creation loud, and add a strict mode.

BACKGROUND (you have not seen the spec — everything you need is here)
- `ActressYamlLoader.apply(slug, data)` resolves a profile to an existing actress by `canonical_name` then alias (`resolveByName`), then — as a second attempt — calls `resolveByName(profile.name().stageName())`. That second call is WRONG: `resolveByName` only checks the `canonical_name` and `actress_aliases` columns; it ignores the `stage_name` (kanji) column entirely, so a kanji stage_name never matches and a new actress is created. This produced the "Karen Tojo #7331" phantom.
- The repository ALREADY has the correct method: `JdbiActressRepository.findByStageName(String)` (in `src/main/java/com/organizer3/repository/jdbi/JdbiActressRepository.java`, ~line 200). It queries `stage_name COLLATE NOCASE`, filters out rejected actresses, and returns a match ONLY when exactly one non-rejected actress has that stage_name (0 or >=2 -> Optional.empty()). This is exactly the safety guard we want; do not add new guard logic.

EXACT CHANGES
1. In `src/main/java/com/organizer3/enrichment/ActressYamlLoader.java`, method `apply(...)` (~line 240): replace the second resolution attempt
       found = actressRepo.resolveByName(profile.name().stageName());
   with a call to the actress repository's `findByStageName(...)`, passing the YAML stage_name **after NFKC-normalizing and trimming it** (scrape-side stage_names are NFKC but YAML strings are not guaranteed to be; normalize in the loader, NOT inside findByStageName — other callers pass already-normalized values). Use `java.text.Normalizer.normalize(s, Normalizer.Form.NFKC).trim()`.
   - Verify `ActressRepository` (the interface in `src/main/java/com/organizer3/repository/ActressRepository.java`) declares `findByStageName`; if it is only on the Jdbi impl, add it to the interface. (Read both before editing.)
2. Make creation loud: the loader returns `LoadResult` (record at the bottom of `ActressYamlLoader.java`, currently fields: `canonicalName, actressId, titlesCreated, titlesEnriched, unresolvedCodes`). Add a boolean `created` field (true when this load created a NEW actress, false when it bound to an existing one). Update the single construction site in `apply(...)` to pass the correct value.
3. Surface creation in summaries: in `src/main/java/com/organizer3/command/LoadActressCommand.java` AND `src/main/java/com/organizer3/utilities/task/actress/LoadAllActressesTask.java`, when summarizing results, list every actress that was CREATED (vs enriched) using the new `created` flag, so batch loads surface phantom creation without manual SQL. (Read both files first; match their existing summary/output style.)
4. Strict mode: add an optional `boolean strict` parameter to the load entry point(s). Default behavior (`strict=false`) is unchanged. When `strict=true`, a no-match outcome must FAIL that YAML's load with a clear error/exception instead of creating an actress. Thread the flag from `LoadActressCommand` and `LoadAllActressesTask` if they expose a way to set it (add a `--strict`/option if the command pattern supports it cleanly; otherwise add the parameter to the loader method and default callers to false — do NOT change existing default behavior). Read the existing entry points to decide the least-invasive plumbing.

FILES YOU MAY MODIFY (and their tests)
- src/main/java/com/organizer3/enrichment/ActressYamlLoader.java
- src/main/java/com/organizer3/repository/ActressRepository.java  (only if findByStageName must be added to the interface)
- src/main/java/com/organizer3/command/LoadActressCommand.java
- src/main/java/com/organizer3/utilities/task/actress/LoadAllActressesTask.java
- corresponding test files under src/test/java/...

DO NOT TOUCH: DraftPopulator, DraftPromotionService, any migration, any UI.

TESTS (required — repository test on real in-memory SQLite; loader test may stub repos with Mockito)
| Case | Expect |
| Romaji miss, kanji stage_name matches exactly one live actress (Karen Tojo fixture) | binds to existing id; NO new actress; LoadResult.created == false |
| Romaji miss, kanji matches 2 actresses | creates (non-strict) / throws (strict) |
| Romaji miss, kanji matches only a REJECTED actress | creates (rejected row must not bind) |
| Kanji differs only by NFKC form / a stray space | still binds |
| Romaji hit | kanji fallback never consulted (fast path unchanged) |
| strict=true, no match | never creates — fails with a clear error |

ACCEPTANCE CRITERIA (definition of done)
- A YAML whose romaji matches nothing but whose kanji stage_name matches exactly one live actress binds to her (no phantom).
- Kanji matching 0 or >=2 -> unchanged behavior (create, or strict-fail).
- LoadResult distinguishes created vs enriched; batch summary lists creations.
- strict=true never creates an actress.
- `./gradlew test` is green.

REPORT BACK: a diff summary (files + what changed) and the test result.
```

### Task 1b — Item E: persist link provenance on draft slots

```
You are implementing one self-contained change in the Organizer3 Java codebase (Javalin + JDBI + SQLite, NO Spring). Do NOT commit. Do NOT modify any file outside the set below. Run `./gradlew test` and report results.

OBJECTIVE
Record HOW each draft cast slot was resolved (which auto-link pass fired, or a human pick), by adding a `resolved_via` column to `draft_title_actresses` and populating it. A later guard depends on this provenance; do not implement that guard here.

BACKGROUND
- `draft_title_actresses.resolution` records THAT a slot resolved (`pick`/`unresolved`/`create_new`/`sentinel:*`) but not HOW. We need a parallel `resolved_via` provenance column.
- `DraftPopulator.autoLinkActress` runs a 5-pass cascade to link a kanji cast entry to a local actress: Pass 1 exact canonical_name, Pass 2 exact alias, Pass 2.5 exact kanji stage_name, Pass 3 slug-anchored (javdb_actress_staging), Pass 4 curated fuzzy (LLM romaji then fuzzy match), Pass 5 prefill/give-up. The method returns `AutoLinkResult`, currently a 3-arg record at ~line 305: `record AutoLinkResult(Long actressId, String englishFirst, String englishLast)` with a constant `EMPTY = new AutoLinkResult(null, null, null)` at ~line 306. `writeCastSlots` (~line 312) consumes it and writes the draft slot row.
- The latest DB migration in `SchemaUpgrader` is V66. Yours is **applyV67** (if a higher VN already exists, use the next free number and say so in your report).

PINNED VALUES (use exactly)
- Column: `draft_title_actresses.resolved_via TEXT` (nullable).
- Allowed values: `canonical` | `alias` | `stage_name` | `slug` | `fuzzy` | `manual` | `prefill`.
- NULL = legacy pre-migration row; consumers treat NULL as unknown/conservative — do not backfill legacy rows.

EXACT CHANGES
1. Migration `applyV67()` in `src/main/java/com/organizer3/db/SchemaUpgrader.java`: idempotent `ALTER TABLE draft_title_actresses ADD COLUMN resolved_via TEXT`, using the existing `addColumnIfMissing(h, "draft_title_actresses", "resolved_via", "TEXT")` helper. Register it in the migration runner the same way V66 is registered. Also add the column to the CREATE TABLE in `SchemaInitializer` if that file defines `draft_title_actresses` (read it; match style — only if present there).
2. `src/main/java/com/organizer3/javdb/draft/DraftPopulator.java`: add a `String via` field to `AutoLinkResult` (update the record, the `EMPTY` constant to pass `null`, and every construction site). Each pass that returns a result must set `via` to the matching pinned value: Pass 1 -> `canonical`, Pass 2 -> `alias`, Pass 2.5 -> `stage_name`, Pass 3 -> `slug`, Pass 4 -> `fuzzy`, Pass 5/prefill -> `prefill`. Then have `writeCastSlots` persist `resolved_via` onto the draft slot row.
3. The model + repository for the slot row: find the entity `DraftTitleActress` (likely `src/main/java/com/organizer3/javdb/draft/DraftTitleActress.java` or under `model/`) and its repository (search for `draft_title_actresses` INSERT/UPDATE SQL — likely a Jdbi repo). Add a `resolvedVia` field to the entity and persist/read it in the repository SQL. Read these before editing.
4. Human picks write `manual`: the draft-editor slot writes go through `src/main/java/com/organizer3/javdb/draft/DraftPatchService.java` (the PATCH `/api/drafts/{titleId}` route in `DraftRoutes.java` delegates to it). In `DraftPatchService.apply(...)`, where it builds/updates a `DraftTitleActress` from a user edit (it constructs `new DraftTitleActress(draftId, edit.javdbSlug(), edit.resolution())` for user-chosen resolutions, ~line 138, and issues UPDATEs), set `resolved_via='manual'` for any slot whose resolution is set/changed to a user-chosen actress (a `pick` with a `linkToExistingId`, or `create_new`). Read DraftPatchService.apply fully before editing to find every write path.

FILES YOU MAY MODIFY (and tests)
- src/main/java/com/organizer3/db/SchemaUpgrader.java
- src/main/java/com/organizer3/db/SchemaInitializer.java (only if it defines draft_title_actresses)
- src/main/java/com/organizer3/javdb/draft/DraftPopulator.java
- the DraftTitleActress entity + its repository (locate them)
- src/main/java/com/organizer3/javdb/draft/DraftPatchService.java
- corresponding tests

DO NOT TOUCH: DraftPromotionService, ActressYamlLoader, any UI.

TESTS (required)
- Populator unit test (Mockito repos): for each pass outcome, assert the persisted slot carries the correct `via` (canonical/alias/stage_name/slug/fuzzy/prefill).
- Repository round-trip on in-memory SQLite: insert a slot with `resolved_via`, read it back; insert a legacy slot with NULL `resolved_via`, read it back without error.
- DraftPatchService test: a user pick / create_new edit results in `resolved_via='manual'`.

ACCEPTANCE CRITERIA
- Every newly populated slot carries the correct `resolved_via`.
- Human edits overwrite it with `manual`.
- Legacy NULL rows do not break any reader.
- Migration is idempotent and named applyV67 (report if renumbered).
- `./gradlew test` green.

REPORT BACK: diff summary + test result + the migration number used.
```

### Task 1c — `CastPresenceCheck` (shared predicate, standalone)

```
You are implementing one self-contained NEW class + tests in the Organizer3 Java codebase (Javalin + JDBI + SQLite, NO Spring). Do NOT commit. Do NOT modify any existing file except to add tests. Run `./gradlew test` and report results.

OBJECTIVE
Build the shared cast-presence predicate that two later changes (a promotion guard and a YAML-loader guard) will both call. This task creates ONLY the class and its tests — no call sites, no wiring into Application.java (consumers wire it themselves later).

BACKGROUND
- `title_javdb_enrichment.cast_json` is a JSON array, each element `{slug, name, gender}` where `name` is the KANJI performer name and gender is "F"/"M"/"U". It is ground truth for who is in a title (modulo compilations, handled by the gate below).
- The containment semantics to mirror are in `src/main/java/com/organizer3/mcp/tools/FindEnrichmentCastMismatchesTool.java` (~lines 93-123): whitespace-stripped LIKE of the actress's stage_name, alternate_names_json[].name entries, and actress_aliases rows against cast_json. READ IT. Your class adds NFKC normalization on top.
- Relevant tables: `actresses(id, stage_name, alternate_names_json, ...)`, `actress_aliases(actress_id, alias_name)`, `title_enrichment_tags(title_id, tag_id)`, `enrichment_tag_definitions(id, name, curated_alias, ...)`.

EXACT CONTRACT (pinned — later tasks precondition on these signatures)
Create class `CastPresenceCheck` in package **`com.organizer3.javdb.enrichment`** (NOT `com.organizer3.enrichment` — both packages exist; use the javdb one). Constructor-injected with Jdbi (match how sibling classes in that package take their dependencies).
  - `Result check(long actressId, String castJson)` where `enum Result { PRESENT, ABSENT, UNCHECKABLE }`:
    - PRESENT: the actress's `stage_name` OR any `actress_aliases.alias_name` OR any `alternate_names_json[].name` appears in castJson — compared **NFKC-normalized and whitespace-stripped** (containment, mirroring FindEnrichmentCastMismatchesTool).
    - UNCHECKABLE: the actress has NO stage_name AND no kanji-bearing alias/alternate name (nothing to look for).
    - ABSENT: checkable, but none of her names are present.
  - `boolean guardEnforced(long titleId, String castJson)` — the comp/size gate, public because two call sites reuse it and must not diverge: returns true when `nfem <= 3 AND the title is NOT compilation-tagged`. nfem = count of "F" entries in castJson. Compilation = the title has a `title_enrichment_tags` row joined to `enrichment_tag_definitions` with `curated_alias = 'compilation'` — RESOLVE THE TAG ID BY ALIAS AT RUNTIME, never hardcode an id.

FILES YOU MAY CREATE
- src/main/java/com/organizer3/javdb/enrichment/CastPresenceCheck.java (new)
- its test class (new, real in-memory SQLite — seed actresses/aliases/tags/enrichment rows directly)

DO NOT TOUCH: any existing production file (including Application.java — no wiring in this task), any migration, any UI.

TESTS (required — real in-memory SQLite)
| Case | Expect |
| stage_name present in cast_json | PRESENT |
| only an alias_name present | PRESENT |
| only an alternate_names_json name present | PRESENT |
| name present but differing by NFKC form / embedded spaces | PRESENT |
| names exist but none in cast | ABSENT |
| actress with NULL stage_name and no kanji alias/alternate | UNCHECKABLE |
| guardEnforced: nfem=1..3, no comp tag | true |
| guardEnforced: nfem=4 | false |
| guardEnforced: nfem=2 but title tagged curated_alias='compilation' | false |
| guardEnforced: comp tag resolved by alias even if definition id differs | correct (no hardcoded id) |

ACCEPTANCE CRITERIA
- Class exists at the pinned FQN with exactly the two public methods above (signatures stable — later tasks depend on them).
- All matrix cases covered; `./gradlew test` green.

REPORT BACK: diff summary (new files) + test result.
```

### Task 2 — Item B promotion guard

```
You are implementing one self-contained change in the Organizer3 Java codebase (Javalin + JDBI + SQLite, NO Spring). Do NOT commit. Do NOT modify any file outside the set below. Run `./gradlew test` and report results.

PRECONDITIONS (verify both; if either fails, STOP and report):
1. The column `draft_title_actresses.resolved_via` exists (values: canonical|alias|stage_name|slug|fuzzy|manual|prefill; NULL=legacy/unknown) and the slot entity exposes it.
2. The class `com.organizer3.javdb.enrichment.CastPresenceCheck` exists with `Result check(long actressId, String castJson)` (enum Result { PRESENT, ABSENT, UNCHECKABLE }) and `boolean guardEnforced(long titleId, String castJson)`. REUSE it — do not modify it, do not re-implement its logic.

OBJECTIVE
At draft promotion, before a cast slot is credited, verify the actress's kanji name actually appears in THIS title's javdb cast. If a slug- or fuzzy-resolved slot fails the check on a small non-compilation cast, divert it to a review queue instead of crediting it — and crucially skip the slug-mapping registration and stage_name backfill that would otherwise teach the pipeline to repeat the mistake.

BACKGROUND
- `title_javdb_enrichment.cast_json` is a JSON array, each element `{slug, name, gender}` where `name` is the kanji name and gender is "F"/"M"/"U". It is ground truth for who is in a title (modulo compilations — handled by guardEnforced).
- `DraftPromotionService.insertTitleActresses(Handle h, long titleId, ...)` (~line 884 in `src/main/java/com/organizer3/javdb/draft/DraftPromotionService.java`) writes `title_actresses` rows. For each resolved slot it ALSO does (read these carefully):
    - FIX 1a (~line 901): `javdbStagingRepo.upsertActressSlugOnly(h, actressId, javdbSlug, titleCode)` — registers slug->actress so future titles auto-bind via Pass 3.
    - FIX 1b (~line 912): backfills `actresses.stage_name` from the draft kanji name when the actress currently has none.
  This is an AMPLIFICATION LOOP: a single wrong fuzzy/slug bind, once promoted, registers the wrong slug mapping and may stamp the wrong kanji onto the actress. THE GUARD MUST RUN BEFORE FIX 1a AND FIX 1b FOR THAT SLOT — not merely before the title_actresses insert.
- `resolveActressId(slot, res, newActressIds)` (~line 958) already returns null for skipped slots and promotion tolerates that (sibling slots promote normally).

THE GUARD
In `insertTitleActresses`, for each slot, BEFORE FIX 1a / FIX 1b / the title_actresses insert:
1. Determine the slot's `resolved_via`. The guard runs ONLY for slots with `resolved_via IN ('slug','fuzzy')` OR NULL (legacy/unknown — treat as guarded/conservative). EXEMPT (never checked, attribute normally): `canonical`, `alias`, `stage_name` (matched string came from this cast — inherently present), `manual` (explicit human choice), `create_new` (actress created FROM this cast entry), `sentinel:*` and skips (no real bind).
2. COMP/SIZE GATE — enforce the guard ONLY when `CastPresenceCheck.guardEnforced(titleId, castJson)` returns true (it encodes `nfem <= 3 AND NOT compilation-tagged`). Call the existing helper — do NOT re-implement the gate.
3. Run `CastPresenceCheck.check(actressId, castJson)`:
   - **ABSENT and enforced** (guarded via, nfem<=3, not comp): DIVERT. Do NOT insert the title_actresses row, SKIP FIX 1a and FIX 1b for that slot, let other slots promote normally. Enqueue ONE review row:
       reason = `guard_cast_mismatch`
       slug   = the slot's javdb slug
       detail = JSON string `{actressId, actressName, stageName, resolvedVia, nfem, castNames:[...]}`
     NOTE: `EnrichmentReviewQueueRepository.enqueue(titleId, slug, reason, resolverSource)` currently does NOT write the `detail` column (the column EXISTS — added in migration V39, `enrichment_review_queue.detail TEXT`). Add an `enqueue` OVERLOAD that also writes `detail`, and call it. Keep the open-row unique index `(title_id, reason)` behavior: if two slots of one title divert, the second INSERT OR IGNORE is silently dropped — ACCEPTED.
   - **PRESENT**: attribute normally (insert + FIX 1a/1b as today).
   - **UNCHECKABLE, or gated-out (comp-tagged, or nfem>=4)**: attribute normally and log at WARN with the same detail payload. Never divert what cannot be verified.

PURGE EXEMPTION
`EnrichmentReviewQueueRepository.purgeStale()` (~line 47) ages out recoverable reasons after 7 days. `guard_cast_mismatch` rows represent DIVERTED DATA, not transient noise — they must be EXEMPT from purging (silent expiry = silent data loss). Add `guard_cast_mismatch` to the not-purged set in the purgeStale SQL.

WIRING
`DraftPromotionService` gains a `CastPresenceCheck` constructor dependency. Update its construction site in `src/main/java/com/organizer3/Application.java` (the new dependency ONLY — touch nothing else in that file) and any test constructors.

FILES YOU MAY MODIFY (and tests)
- src/main/java/com/organizer3/javdb/draft/DraftPromotionService.java
- src/main/java/com/organizer3/javdb/enrichment/EnrichmentReviewQueueRepository.java (add enqueue overload + purge exemption)
- src/main/java/com/organizer3/Application.java (ONLY the DraftPromotionService construction — new dep)
- corresponding tests

DO NOT TOUCH: CastPresenceCheck (reuse only), ActressYamlLoader, DraftPopulator, any UI, any migration (the detail column and resolved_via column already exist).

TESTS (Mockito for promotion service — stub CastPresenceCheck per case; real in-memory SQLite for the queue repo)
| Case | Expect |
| fuzzy bind, check=ABSENT, guardEnforced=true (420-style) | divert + one queue row (with detail JSON) + NO FIX 1a/1b for that slot |
| slug bind, check=ABSENT, guardEnforced=true (1059-style) | divert |
| fuzzy bind, check=PRESENT | attribute |
| fuzzy bind, check=ABSENT, guardEnforced=false (comp-tagged) | attribute + WARN |
| fuzzy bind, check=ABSENT, guardEnforced=false (nfem=5) | attribute + WARN |
| manual bind (check never called) | attribute (exempt) |
| check=UNCHECKABLE | attribute + WARN |
| resolved_via NULL (legacy), check=ABSENT, guardEnforced=true | divert |
| two slots of one title both divert | one queue row, both slots withheld |
| purgeStale with an old guard_cast_mismatch row | row retained |

ACCEPTANCE CRITERIA
- A guarded slot failing the check on a small non-comp cast: no title_actresses row, no staging upsert, no stage_name backfill, one open guard_cast_mismatch row (with detail JSON); siblings unaffected.
- Same slot on comp-tagged or 4+-female title: attributes normally, WARN logged.
- manual/canonical/alias/stage_name/create_new/sentinel slots: never checked.
- guard_cast_mismatch rows survive purgeStale().
- `./gradlew test` green.

REPORT BACK: diff summary + test result.
```

### Task 2-ui — v2 workflow rendering for `guard_cast_mismatch`

```
You are adding UI rendering in the Organizer3 v2 web surface (vanilla JS ES modules). Do NOT commit. Do NOT modify any file outside `src/main/resources/public/modules/v2/workflow/`. There is NO build step for JS; just edit the modules. You cannot run `./gradlew test` against JS, but DO run `./gradlew test` at the end to confirm you broke no Java; report results.

OBJECTIVE
The enrichment review queue is gaining rows with `reason = 'guard_cast_mismatch'` (a slot diverted at promotion because the actress's kanji was not in the title's javdb cast). The backend change may not be merged yet — that's fine: you are coding against the pinned contract below, which is final. Render this reason in the v2 workflow triage UI with a label, detail display, and a `mark_resolved` action.

BACKGROUND
- The v2 workflow UI lives in `src/main/resources/public/modules/v2/workflow/`. Relevant files: `index.js`, `row.js` (per-row rendering — the biggest file, ~18KB), `actions.js` (action handlers), plus per-reason renderers like `cast-anomaly.js`, `slug-conflict.js`, `stage-name-conflict.js`. READ row.js, actions.js, and one existing per-reason renderer (e.g. cast-anomaly.js) to learn the dispatch pattern BEFORE editing.
- A `guard_cast_mismatch` review-queue row carries: `reason='guard_cast_mismatch'`, `slug`, and a `detail` JSON string `{actressId, actressName, stageName, resolvedVia, nfem, castNames:[...]}`.
- The human fixes the underlying data via existing MCP tools / draft re-edit (reassign_title_credit, add alias, etc.) OUTSIDE this UI, then marks the row resolved here. A richer inline "approve this bind" action is OUT OF SCOPE.

EXACT CHANGES
1. Add a renderer for `guard_cast_mismatch` following the existing per-reason renderer pattern. Label it human-readably (e.g. "Cast mismatch (guard)"). Display the detail: actress name + stage_name, resolvedVia, nfem, and the list of castNames so the triager can see who javdb listed.
2. Wire the `mark_resolved` action through the existing actions dispatch (`actions.js`) — reuse the queue's existing resolve endpoint/handler that other reasons use; do not invent a new backend route.
3. Register the new reason in whatever lookup/switch dispatches reason -> renderer (likely in row.js or index.js).

FILES YOU MAY MODIFY: only files under src/main/resources/public/modules/v2/workflow/.
DO NOT TOUCH: any v1 legacy UI (anything under modules/ outside v2/ and chrome/), any Java, any other v2 module.

ACCEPTANCE CRITERIA
- The v2 workflow screen lists guard_cast_mismatch rows with a clear label and the detail payload rendered.
- The mark_resolved action resolves the row via the existing resolve mechanism.
- No Java tests broken (`./gradlew test` green).

REPORT BACK: which files changed + how the reason is dispatched + the Java test result.
```

### Task 3a — Item F: cast cross-check on the YAML portfolio path

```
You are implementing one self-contained change in the Organizer3 Java codebase (NO Spring). Do NOT commit. Do NOT modify any file outside the set below. Run `./gradlew test` and report results.

PRECONDITION: the class `com.organizer3.javdb.enrichment.CastPresenceCheck` exists with `Result check(long actressId, String castJson)` returning `enum Result { PRESENT, ABSENT, UNCHECKABLE }` (NFKC + whitespace-strip containment of the actress's stage_name / aliases / alternate names against the title's cast_json). If it does not exist, STOP and report.

OBJECTIVE
The YAML portfolio loader matches titles BY PRODUCT CODE ALONE and, for an existing enriched title, overwrites its metadata + tags. Product codes are reused across eras, so a portfolio entry can stamp wrong metadata onto a DIFFERENT work wearing the same code. Guard the existing-title metadata write with a cast cross-check; report-and-skip on mismatch.

BACKGROUND
- In `src/main/java/com/organizer3/enrichment/ActressYamlLoader.java`, method `apply(...)`, the portfolio loop (~lines 300-348) iterates `data.portfolio()`. For an EXISTING title (`titleRepo.findByCode(code)` present) it calls `titleRepo.enrichTitle(titleId, ...)` (title/date/notes/grade) and `tagRepo.replaceTagsForTitle(titleId, tags)`. For a MISSING code it creates a stub filed under the actress (`titles.actress_id`) and counts `titlesCreated`.
- This path does NOT write `title_actresses` credit rows — the exposure is filing + metadata, not credits.
- `LoadResult` has a `List<String> unresolvedCodes` field (the review surface for this batch/headless loader; there is no review queue from this path).

EXACT CHANGES (in ActressYamlLoader.apply portfolio loop)
For an EXISTING title that HAS enrichment with `cast_json` present:
  - Run `CastPresenceCheck.check(actress.getId(), castJson)` for the loaded actress.
  - Apply the SAME comp/size gate as the promotion guard by calling the EXISTING reusable helper on `CastPresenceCheck` — `boolean guardEnforced(long titleId, String castJson)` (it returns true when `nfem <= 3 AND the title is NOT compilation-tagged`, nfem = "F"-entry count, compilation tag resolved by `curated_alias='compilation'` at runtime). DO NOT re-implement the gate — reuse this helper so the two call sites cannot diverge. (If the method name differs in the merged code, find the public gate helper on CastPresenceCheck and call it; report if it is missing.)
  - On ABSENT + enforced: SKIP `enrichTitle` + `replaceTagsForTitle` for that entry, and append a structured line to `LoadResult.unresolvedCodes`: `"<code>: cast-mismatch — actress kanji not in enriched cast; skipped"`.
  - On PRESENT / UNCHECKABLE / gated-out (comp or nfem>=4): proceed UNCHANGED.
Titles with no enrichment / no cast_json: UNCHANGED (nothing to check). Stub creation (missing code): NOT blocked — nothing to check against — remains visible via the existing `titlesCreated` count.

You will need to fetch the title's enrichment cast_json + tags. Read the title/enrichment repositories to find the right read method (e.g. a method returning `title_javdb_enrichment` by title_id, and the tag join). Inject `CastPresenceCheck` (and any enrichment repo) into `ActressYamlLoader` via its constructor — update the constructor and every construction site (search the codebase, including Application.java and tests). Keep this minimal.

FILES YOU MAY MODIFY (and tests)
- src/main/java/com/organizer3/enrichment/ActressYamlLoader.java
- its constructor wiring in src/main/java/com/organizer3/Application.java (only the new dependency)
- existing ActressYamlLoader tests (update constructor calls)
- corresponding new tests

DO NOT TOUCH: DraftPromotionService, DraftPopulator, CastPresenceCheck (reuse only), any migration, any UI.

TESTS (loader test with stubbed enrichment rows)
| Case | Expect |
| portfolio code = enriched title, actress kanji ABSENT, nfem<=3, no comp | enrichTitle + replaceTags SKIPPED; LoadResult.unresolvedCodes contains the structured line |
| code = enriched title, actress kanji PRESENT | proceeds (metadata written) |
| code = enriched, ABSENT, comp-tagged title | proceeds (gated out) |
| code = enriched, ABSENT, nfem=5 | proceeds (gated out) |
| code = title with NO enrichment / no cast_json | proceeds unchanged |
| missing code | stub created as today; titlesCreated incremented; not blocked |

ACCEPTANCE CRITERIA
- A portfolio entry whose code collides with an enriched different-cast title leaves that title's metadata/tags untouched and is reported in LoadResult.unresolvedCodes.
- All other entries behave exactly as today.
- `./gradlew test` green.

REPORT BACK: diff summary + test result + how you read enrichment cast_json/tags.
```

### Task 3b — Item C: standing attribution health

```
You are implementing one self-contained change in the Organizer3 Java codebase (NO Spring). Do NOT commit. Do NOT modify any file outside the set below. Run `./gradlew test` and report results.

OBJECTIVE
Make the attribution audit STANDING: extract the existing on-demand detection SQL into a service, persist actress-level findings in a new table, refresh them on the revalidation cron, surface an open-count via a utilities health check, and add a read-only MCP tool to list findings. No behavior change to the existing MCP tools.

BACKGROUND
- Two MCP tools already run the detection by hand: `src/main/java/com/organizer3/mcp/tools/FindEnrichmentCastMismatchesTool.java` (per-title "is the actress's kanji in this title's cast_json" check; small-cast cast-mismatch %) and `src/main/java/com/organizer3/mcp/tools/FindSuspectCreditsTool.java` (co-occurrence / suspect-credit check). READ BOTH.
- These findings are ACTRESS-LEVEL aggregates (mismatch % across a filmography), NOT title-level — so they must NOT go in `enrichment_review_queue` (which is title-keyed). Use a new dedicated table.
- The cron `src/main/java/com/organizer3/javdb/enrichment/RevalidationCronScheduler.java` runs phases on each `tick()` (a drain phase + a safety-net phase). You add a THIRD phase.
- Health-check framework: `src/main/java/com/organizer3/utilities/health/LibraryHealthCheck.java` (interface: `id()`, `label()`, `description()`, `fixRouting()` returning a `FixRouting` enum, `run()` returning `CheckResult(int total, List<Finding>)`). Model your new check on `src/main/java/com/organizer3/utilities/health/checks/UnloadedYamlsCheck.java`. Checks are registered into `LibraryHealthService` — find the registration site and add yours.
- Latest migration is V66; Item E added applyV67. Yours is **applyV68** (use next free number if higher; report it).
- MCP tool registration: tools are registered in `src/main/java/com/organizer3/mcp/ToolRegistry.java` (see how FindEnrichmentCastMismatchesTool is registered). Mirror that for the new read-only tool.

PINNED DECISIONS
1. New service `AttributionAuditService` (package: put it alongside the existing detection — `com.organizer3.javdb.enrichment` is appropriate; confirm where the tools' helper logic naturally lives and match). Move the QUERY LOGIC of FindEnrichmentCastMismatchesTool and FindSuspectCreditsTool into it. The two MCP tools become THIN WRAPPERS that delegate to the service — their external behavior/output must be UNCHANGED.
2. New table via `applyV68()`: `attribution_findings` with columns:
     actress_id INTEGER, finding_class TEXT ('cast_mismatch'|'suspect_credit'), metric REAL,
     sample_json TEXT, first_seen_at TEXT, last_seen_at TEXT,
     status TEXT ('open'|'suppressed'|'resolved'), note TEXT (nullable),
     stage_name_at_suppress TEXT (nullable), slug_at_suppress TEXT (nullable)
   UNIQUE on (actress_id, finding_class). Use timestamps as ISO-8601 microseconds with Z (house convention). Add a small Jdbi repository for it (CRUD + upsert + status transitions).
   [The two *_at_suppress columns capture the premise at suppression time — see point 5.]
3. Cron third phase in `RevalidationCronScheduler.tick()` (AFTER drain + safety-net), BATCH-LIMITED (add a config'd batch size like the existing phases): run AttributionAuditService detection and upsert findings:
     - finding newly seen -> insert status='open', set first_seen_at + last_seen_at + metric + sample_json, log a WARN summary.
     - finding still present -> refresh last_seen_at + metric (+ sample_json); do NOT touch status (keeps suppressed quiet).
     - finding previously open/recorded but now VANISHED -> set status='resolved'.
4. Utilities health check: new `LibraryHealthCheck` impl reporting the COUNT of `status='open'` findings (sample = a capped preview), modeled on UnloadedYamlsCheck; register it in LibraryHealthService.
5. Suppression is DATA, not a hardcoded list. A human sets status='suppressed' + a note (done via SQL/MCP, not in this task). Suppression is INVALIDATED AUTOMATICALLY when the actress's `stage_name` OR staging slug changes after suppression time: at suppression the repo records `stage_name_at_suppress` + `slug_at_suppress` (current values); the cron's refresh, for a suppressed finding, compares current stage_name/slug to the stored ones and reopens (status='open') if either differs (premise changed). Do NOT seed any FP list in code — the rollout step seeds suppressions as data.
6. New READ-ONLY MCP tool `list_attribution_findings`: lists findings (filterable by status), thin read over the repository. Register in ToolRegistry like the existing find_* tools.

FILES YOU MAY MODIFY/CREATE (and tests)
- src/main/java/com/organizer3/javdb/enrichment/AttributionAuditService.java (new)
- src/main/java/com/organizer3/.../AttributionFindingsRepository.java (new — place alongside other jdbi repos; match package of similar repos)
- src/main/java/com/organizer3/db/SchemaUpgrader.java (applyV68 + register)
- src/main/java/com/organizer3/db/SchemaInitializer.java (add CREATE TABLE attribution_findings if it carries fresh-install schema — read it)
- src/main/java/com/organizer3/javdb/enrichment/RevalidationCronScheduler.java (third phase)
- src/main/java/com/organizer3/mcp/tools/FindEnrichmentCastMismatchesTool.java + FindSuspectCreditsTool.java (refactor to thin wrappers — NO behavior change)
- src/main/java/com/organizer3/mcp/tools/ListAttributionFindingsTool.java (new)
- src/main/java/com/organizer3/mcp/ToolRegistry.java (register new tool)
- src/main/java/com/organizer3/utilities/health/checks/AttributionFindingsCheck.java (new)
- src/main/java/com/organizer3/utilities/health/LibraryHealthService.java (register check)
- src/main/java/com/organizer3/Application.java (wire new service/repo/tool/cron-phase deps)
- corresponding tests

DO NOT TOUCH: ActressYamlLoader, DraftPromotionService, DraftPopulator, any v1 UI. CastPresenceCheck is unrelated to this task.

TESTS (service on real in-memory SQLite; cron + health-check with Mockito where appropriate)
- Finding lifecycle on in-memory SQLite: new->open; still-present->refresh (last_seen_at/metric updated, status unchanged); vanished->resolved; suppress->stays quiet across a refresh; premise change (stage_name or slug differs from *_at_suppress)->reopen to open.
- Scheduler test: asserts the third phase is invoked with a batch limit.
- Health-check unit test: open-finding count reflected in CheckResult.total.
- Wrapper tests: assert FindEnrichmentCastMismatchesTool / FindSuspectCreditsTool output is unchanged vs the pre-refactor SQL (encode a fixture or assert against the service result they now delegate to).

ACCEPTANCE CRITERIA
- Cron tick produces/refreshes findings without manual invocation.
- Suppressed findings stay quiet across runs; a stage_name/slug change reopens them.
- Health check reflects open count.
- The two existing MCP tools are behavior-unchanged; list_attribution_findings responds read-only.
- Migration named applyV68 (report if renumbered).
- `./gradlew test` green.

REPORT BACK: diff summary + test result + migration number + confirmation the two existing tools' behavior is unchanged.
```

---

## 3. Integration & rollout

**Merge order:** Wave 1 (1a, 1b, 1c, 2-ui — any order, disjoint) → Wave 2 worktrees merged sequentially **2 → 3a → 3b**, resolving the additive `Application.java` wiring overlap and re-running `./gradlew test` after each merge. Orchestrator merges each subagent's work only after its tests are green and a diff spot-check passes (see § Wave plan verifications).

**Single rebuild + restart (NOT per wave):** after ALL waves are merged, run once:
```
./gradlew installDist
```
then restart the running app. Guards, the cron third phase, the health check, and the new MCP tool only go live after this restart (no hot reload).

**One-time data step — OPERATOR STEP, REQUIRES USER PRESENCE.** Seed the audit's confirmed false positives as suppressed findings so the standing scan does not re-nag them. This is a manual SQL write against the LIVE database at `~/.organizer3/organizer.db` (the repo `data/*.db` files are 0-byte stubs — do NOT use them; use `PRAGMA busy_timeout`; timestamps ISO-8601 µs Z). For each of actress ids **1071, 2286, 772, 1084, 1623**, insert/upsert a row into `attribution_findings` with `finding_class='cast_mismatch'` (these are rename-chain Scan-A cast-mismatch FPs; the column is part of the UNIQUE key `(actress_id, finding_class)` and the cron upserts on it — if `finding_class` is omitted or wrong, the next cron tick inserts a parallel open `cast_mismatch` row beside the suppressed one and the FP nags anyway), `status='suppressed'`, a `note` (e.g. "audit FP rename-chain 2026-06-12"), and `stage_name_at_suppress`/`slug_at_suppress` set to the actress's CURRENT stage_name and current staging slug (so premise-change reopening works correctly). Do this AFTER the V68 migration has run (i.e. after restart). Flag to the user before running.

**Post-deploy verification:**
1. Promote a draft known to contain a fuzzy/slug-bound slot whose kanji is absent on a small non-comp cast; confirm: no `title_actresses` row for it, no staging upsert, no stage_name backfill, one open `guard_cast_mismatch` row with a detail JSON payload; sibling slots credited.
2. Open the Utilities health screen; confirm the new attribution-findings check renders an open count.
3. Call the MCP tools: `find_enrichment_cast_mismatches` + `find_suspect_credits` (confirm unchanged output) and `list_attribution_findings` (confirm it responds).
4. Open the v2 workflow screen; confirm the `guard_cast_mismatch` row renders with its label/detail and `mark_resolved` works.

---

## 4. Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| Migration numbering collision (another migration lands before V67/V68) | low | Each migration task re-checks the latest `applyVN` at execution time and renumbers + reports; orchestrator verifies V67 after Wave 1, V68 after Wave 2. |
| Wave-2 `Application.java` wiring overlap (3 agents add constructor deps) | medium | Worktree isolation per agent; orchestrator merges sequentially (2 → 3a → 3b), resolves the additive conflicts by hand, re-runs tests after each merge. |
| Wave-2 agents touching the same test fixtures (e.g. shared promotion-service test class) | low | Allowed-file lists are disjoint for production code; if two agents extend the same test class, orchestrator unions the test methods at merge. |
| `enqueue` detail payload dropped (existing signature ignores `detail`) | medium | Task 2 prompt explicitly requires an `enqueue` overload that writes the existing `detail` column; test asserts the JSON lands. |
| Review-queue UI regression in v2 | low | 2-ui scoped strictly to `modules/v2/workflow/`; reuses existing resolve handler; new reason is additive (other reasons untouched). |
| Fuzzy/slug binds that USED to silently bind now divert — visible behavior change | expected/by design | Documented; gated to small non-comp casts; soft (review row, nothing deleted); WARN-logged when gated out so nothing is silent. |
| Cron third-phase runtime on large DB | medium | Batch-limited phase (config'd batch size mirroring drain/safety-net); refresh is upsert-cheap; runs on the existing interval, not at startup. |
| `CastPresenceCheck` placed in wrong `enrichment` package | low | FQN pinned verbatim (`com.organizer3.javdb.enrichment.CastPresenceCheck`) in tasks 2 and 3a. |
| Refactor of the two find_* tools changes their output | medium | Wrapper tests assert behavior parity; acceptance criterion requires "no behavior change." |

---

## 5. Explicit exclusions

- **Item D** (multi-slug-per-actress / `actress_javdb_slugs` join table) — deferred; future design-doc task only, scheduled after Item C accumulates suppressed rename-chain findings to seed the backfill. NOT in any coding wave here.
- **v1 legacy UI** — untouched per `modules/LEGACY.md`; the new `guard_cast_mismatch` reason renders inert there (no actions), which is acceptable.
- **Richer guard-row triage actions** (inline "approve this bind" that writes credit + staging) — deferred until the basic guard proves out; 2-ui ships `mark_resolved` only.
- **Retroactive standing scan as a separate effort** — not needed; Item C IS the standing retroactive scan. Guards cover new writes.
- **Comp under-listing, pykakasi reading noise, over-credited-titles cleanup, lower-confidence audit rows (258/643/1176/1179/859/2450/2822)** — out of scope per spec §5.

