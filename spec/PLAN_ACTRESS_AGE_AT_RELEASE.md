# PLAN: Actress Age-at-Release Filter — Orchestrated Implementation

**Status:** PLAN v1, 2026-06-12 — generated from `spec/PROPOSAL_ACTRESS_AGE_AT_RELEASE.md` (PR #96).
**Source of truth:** the proposal is authoritative for design. Where this plan quotes a pinned value (SQL shape, column name, castMode semantics, migration number), it is copied from the proposal; do not re-litigate in dispatch.

## Execution model — who does what, and why

- **Opus orchestrates from the main session.** It dispatches tasks, merges results, runs review gates, and is the only party that sees the whole picture.
- **Sonnet subagents do the coding.** Each task below is dispatched to a Sonnet subagent via the Agent tool; wave-mates are dispatched **in a single message** so they run concurrently.
- **Subagents are blind.** A subagent has NOT read the proposal or this plan and cannot see the conversation. Every dispatch prompt is fully self-contained: design decisions, pinned SQL, file paths, test matrices, acceptance criteria are inlined.
- **Model-capability assignment.** The decomposition is deliberate about what each model is good at:
  - **Sonnet** excels at well-scoped, self-contained implementation against a pinned contract (exact SQL, exact column names, explicit test matrix). It is weaker at cross-cutting integration judgment — specifically, reasoning about how a new predicate interacts with an existing dynamic-SQL builder's joins/GROUP BY/HAVING/pagination. Tasks are therefore cut so each Sonnet agent works a disjoint file set with no design freedom on the pinned parts, and the one genuinely tricky integration (Task 2a) gets a mandatory adversarial review gate.
  - **Opus** reviews at the two critical gates (Gate A: age-math/NULL-out correctness; Gate B: dynamic-SQL integration). It does not code by default ([[feedback-dispatch-first]]), but **for this plan the user has pre-authorized Opus to code the Gate-B hot spot itself** (the `findLibraryPaged` predicate integration) if Sonnet's attempt fails review twice — taking over only `JdbiTitleRepository`, leaving the rest of Task 2a's diff intact.
  - **Advisor escalation.** If Opus itself is uncertain at a gate (or its own Gate-B takeover fails tests), it escalates the review/coding of that artifact to a more advanced model via the Agent tool's `model` override (`fable`), with the same self-contained prompt plus the failing diff and test output. Escalation is for the two gated artifacts only — not general coding.
- **Isolation:** Wave 2 agents **MUST run with `isolation: "worktree"`** — 2b and 2c both add constructor wiring to `Application.java`, and 2a/2b can collide on test fixtures. Opus merges sequentially (order in §1) and re-runs `./gradlew test` after each merge.
- **Subagents run `./gradlew test`** and report pass/fail with failing-test names. They do NOT commit and do NOT touch files outside their listed set. No subagent touches v1 legacy UI (`src/main/resources/public/modules/` outside `v2/` and `chrome/`, per `modules/LEGACY.md`). No UI work exists in this plan at all (backend-only feature).
- **No per-wave rebuild.** A single `./gradlew installDist` + app restart happens once at the end (§3).

## House rules every subagent must honor

- No Spring; dependencies are wired manually in `Application.java`. New classes are constructor-injected.
- Testing is mandatory: **repository/migration tests use real in-memory SQLite**; **service/route/tool tests use Mockito mocks**. No untestable shapes.
- Migrations: idempotent `applyVN()` in `SchemaUpgrader` using the `addColumnIfMissing` helper; bump `CURRENT_VERSION`; mirror the column in `SchemaInitializer`'s CREATE TABLE and refresh `SchemaInitializerTest` inventories (the test asserts exact column/version inventories — it WILL fail if forgotten). Latest migration in the tree is **V68**; this feature is **applyV69**. If a higher VN has landed at execution time, renumber and tell the orchestrator.
- All date columns are TEXT; treat `''` as NULL everywhere (`NULLIF(col,'')`).

---

## 1. Wave plan

| Wave | Tasks | Parallelism | Depends on | Isolation |
|---|---|---|---|---|
| 1 | **1** (schema V69 + `AgeAtReleaseRecomputer` + seed) | 1 agent — foundation, everything else needs the column | — | shared tree |
| — | **Gate A** (Opus/advisor): age-math + NULL-out review | — | 1 | — |
| 2 | **2a** (browse filter chain) ∥ **2b** (recompute trigger wiring) ∥ **2c** (MCP repair tool + outlier triage) | **3 concurrent** | all need Wave 1 merged | **worktrees required** |
| — | **Gate B** (Opus/advisor): adversarial review of 2a's dynamic-SQL diff **before merging 2a** | — | 2a | — |
| final | integration: merge 2a→2b→2c, rebuild, restart, live seed verification | — | all | — |

**Why Wave 1 is not parallel:** every other task compiles or tests against `title_actresses.age_at_release` (repository tests boot a fresh in-memory schema via `SchemaInitializer`); coding them before the column exists in both schema paths guarantees churn. One well-pinned agent, then fan out.

**Wave-2 disjointness:** 2a = `JdbiTitleRepository` + `TitleRepository` + `TitleBrowseService` + `TitleRoutes` + `TitleSummary` (+ possibly `TitleActressRepository` read method); 2b = `Application.java` + `ActressYamlLoader`/load commands + `DraftPromotionService` + the three credit-mutation tool handlers; 2c = new MCP tool handler + `Application.java`/MCP registration. Only expected overlap: additive wiring in `Application.java` (2b, 2c) — trivially mergeable.

**Wave-2 merge order: 2a → 2b → 2c** (filter first because it carries the most test surface; wiring merges are mechanical). After each merge: resolve overlap, `./gradlew test`, then merge the next.

### Review gates (Opus; escalate to advisor on uncertainty)

**Gate A — after Wave 1, before dispatching Wave 2.** The recomputer SQL is the heart of the feature; an error here poisons every stored value. Opus reads the diff and verifies against the proposal §3b/§3c:
1. Integer-date age math exact at the birthday boundary (released day-before vs on-birthday) including a Feb-29 DOB fixture.
2. **Full re-derivation semantics**: rows that lose a prerequisite are set to NULL (run the recompute twice in a test: enrich → recompute → remove DOB → recompute → NULL).
3. Release-date precedence `COALESCE(NULLIF(enrichment,''), NULLIF(titles,''))`, with `''`-as-NULL on both sides AND on `date_of_birth`.
4. Migration: V69 numbering, `addColumnIfMissing`, `SchemaInitializer` mirror, `SchemaInitializerTest` inventory refreshed, seed invoked inside the migration.
5. Sanity: the recomputer test fixture reproduces a known hand-computed age.

If any check fails: one revision round with the same Sonnet agent (precise feedback); on a second failure, Opus fixes the SQL directly (it authored the pinned statement) or escalates to the advisor.

**Gate B — after 2a returns, BEFORE merging 2a.** `findLibraryPaged` (~`JdbiTitleRepository:1537`) is hand-built dynamic SQL with conditional joins, tag GROUP BY/HAVING, sort variants, and pagination — the highest-risk integration point in the plan and historically the kind of change where a plausible diff breaks an *unrelated* filter combination. Opus performs an adversarial review:
1. Read the full post-change method, not the diff hunks — verify the age predicates are pure `WHERE … EXISTS` additions that cannot multiply rows (no new join into the paginated SELECT) and cannot disturb tag HAVING-count semantics.
2. Verify all three castMode shapes match the proposal §3e verbatim (incl. strict NULL in `all`, zero-credit exclusion in `solo`/`all`).
3. Verify parameter binding (no string concatenation of user ints), and that `castMode` without age params changes nothing (no-op).
4. Verify pagination/sort unchanged when the filter is off (golden test: same query params pre/post diff produce identical SQL or identical results).
5. Run the composition tests plus one Opus-authored spot test combining age + tags + company + pagination offset.

Failure handling: one Sonnet revision round → if still failing, **Opus takes over `JdbiTitleRepository` itself** (pre-authorized), keeping the rest of 2a's diff → if Opus's version fails tests it cannot explain, escalate the repository method to the advisor model with the diff + failures.

**Gate C — integration (§3).** Mechanical verification, listed below.

---

## 2. Per-task dispatch prompts

### Task 1 — Schema V69 + `AgeAtReleaseRecomputer` + seed

```
You are implementing one self-contained change in the Organizer3 Java codebase (Javalin + JDBI + SQLite, NO Spring; dependencies wired manually in Application.java). Do NOT commit. Do NOT modify files outside the set below. Run `./gradlew test` at the end and report results (name failing tests).

OBJECTIVE
Add a denormalized integer column `title_actresses.age_at_release` (the credited actress's age in years on the title's release date) plus a reusable recompute service that both seeds and repairs it, and run the seed inside the schema migration.

BACKGROUND (you have not seen the spec — everything you need is here)
- `title_actresses(title_id, actress_id)` is the credit junction table (composite PK), defined in src/main/java/com/organizer3/db/SchemaInitializer.java (~line 164).
- `actresses.date_of_birth` (TEXT, full ISO date or NULL/'' — SchemaInitializer ~line 41).
- Release date lives in TWO places, both TEXT ISO dates: `title_javdb_enrichment.release_date` (canonical when present; SchemaInitializer ~line 452) and `titles.release_date` (fallback; ~line 90). Empty string '' occurs in the wild and must be treated as NULL.
- The latest migration is V68 (SchemaUpgrader.java, CURRENT_VERSION at line ~27, applyV68 ~line 2283). Yours is applyV69 — if a higher VN exists when you start, take the next free number and say so in your report.

PINNED VALUES (use exactly — do not redesign)
- Column: `title_actresses.age_at_release INTEGER` (nullable; NULL = not computable).
- Age formula (exact, birthday-aware — do NOT substitute julianday or year subtraction):
    (CAST(strftime('%Y%m%d', <release>) AS INTEGER) - CAST(strftime('%Y%m%d', <dob>) AS INTEGER)) / 10000
- Release-date precedence: COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,'')).
- Recompute statement — FULL RE-DERIVATION over the whole table, single UPDATE; rows that are not computable must be SET TO NULL (not skipped), so corrections self-heal:
    UPDATE title_actresses SET age_at_release = (
        SELECT CASE
            WHEN a.date_of_birth IS NULL OR a.date_of_birth = '' THEN NULL
            WHEN COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,'')) IS NULL THEN NULL
            ELSE (CAST(strftime('%Y%m%d', COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,''))) AS INTEGER)
                - CAST(strftime('%Y%m%d', a.date_of_birth) AS INTEGER)) / 10000
        END
        FROM titles t
        JOIN actresses a ON a.id = title_actresses.actress_id
        LEFT JOIN title_javdb_enrichment e ON e.title_id = t.id
        WHERE t.id = title_actresses.title_id
    )
  (You may adapt syntax minimally if JDBI/SQLite requires, but the semantics — single idempotent statement, NULL-out included, precedence as pinned — are fixed.)

EXACT CHANGES
1. NEW class `src/main/java/com/organizer3/db/AgeAtReleaseRecomputer.java` (constructor takes Jdbi): method `recomputeAll()` executing the pinned UPDATE and returning the changed-row count (compute it honestly — e.g., compare a before/after checksum or use SQLite's changes(); note a full-table UPDATE reports all rows as changed, so derive "changed" as rows whose value actually differs: simplest correct approach is a two-step — count rows where the stored value differs from the derived value, then update. Keep it a single transaction). Also method `findImplausible()` returning rows where age_at_release < 18 OR > 70 (title_id, actress_id, age) — used later by a repair tool; just the query method here.
2. Migration applyV69 in src/main/java/com/organizer3/db/SchemaUpgrader.java: addColumnIfMissing("title_actresses","age_at_release","INTEGER"), then run the seed (the same pinned UPDATE — inline or via the recomputer). Bump CURRENT_VERSION. Register exactly like applyV68.
3. Mirror the column in SchemaInitializer's CREATE TABLE for title_actresses, and refresh src/test/java/... SchemaInitializerTest column/version inventories (this test asserts exact inventories and WILL fail if you forget).
4. Wire `AgeAtReleaseRecomputer` construction into Application.java ONLY if needed for compilation of your own code; do NOT add startup invocation or any other call sites — a separate task owns trigger wiring.

FILES YOU MAY MODIFY
- src/main/java/com/organizer3/db/AgeAtReleaseRecomputer.java (new)
- src/main/java/com/organizer3/db/SchemaUpgrader.java
- src/main/java/com/organizer3/db/SchemaInitializer.java
- corresponding test files under src/test/java/ (incl. SchemaInitializerTest inventory refresh)
DO NOT TOUCH: JdbiTitleRepository, TitleBrowseService, TitleRoutes, ActressYamlLoader, DraftPromotionService, any MCP handler.

TESTS (required — real in-memory SQLite)
| Case | Expect |
| DOB 2000-03-15, release 2020-03-14 | age 19 |
| DOB 2000-03-15, release 2020-03-15 | age 20 |
| DOB 2000-02-29 (leap), release 2021-02-28 / 2021-03-01 | 20 / 21 |
| Enrichment date present AND titles.release_date present (different years) | enrichment wins |
| Enrichment row absent or release_date '' | falls back to titles.release_date |
| DOB '' or NULL, or both release dates NULL/'' | age_at_release NULL |
| Previously computed row, then DOB deleted, recompute again | value cleared to NULL |
| Multi-cast title, 2 actresses different DOBs | each credit row gets its own age |
| recomputeAll() twice, no data change | second run reports 0 changed |
| Fresh DB via SchemaInitializer | column exists; version = 69 |
| Migration on a pre-V69 fixture with seedable data | column added, computable rows seeded, others NULL; re-run no-op |

ACCEPTANCE CRITERIA
- Pinned SQL semantics implemented exactly (precedence, ''-as-NULL, NULL-out, idempotency).
- changed-row count is "rows whose value differs", not "rows touched".
- SchemaInitializer + SchemaUpgrader + inventory test all consistent at V69.
- `./gradlew test` green.

REPORT BACK: diff summary (files + what changed), the migration number actually used, and test results.
```

### Task 2a — Browse filter: ageMin/ageMax + castMode through the pipeline

```
You are implementing one self-contained change in the Organizer3 Java codebase (Javalin + JDBI + SQLite, NO Spring). Do NOT commit. Do NOT modify files outside the set below. Run `./gradlew test` and report results.

OBJECTIVE
Add an "actress age at release" filter to the title library browse pipeline: HTTP params ageMin/ageMax (+ castMode) → service → repository dynamic SQL, plus a nullable ageAtRelease field on the TitleSummary response for solo-cast titles.

BACKGROUND
- The column `title_actresses.age_at_release INTEGER` (nullable; NULL = not computable) ALREADY EXISTS — a prior task added it. Do not create migrations.
- Browse chain: GET /api/titles/browse in src/main/java/com/organizer3/web/routes/TitleRoutes.java → TitleBrowseService.findLibraryPaged(...) (src/main/java/com/organizer3/web/TitleBrowseService.java ~line 189) → TitleRepository.findLibraryPaged (interface ~line 402) → JdbiTitleRepository.findLibraryPaged (src/main/java/com/organizer3/repository/jdbi/JdbiTitleRepository.java ~lines 1537–1623), a hand-built dynamic SQL method with conditional joins for tags (GROUP BY + HAVING COUNT for AND-semantics), sort variants, LIMIT/OFFSET pagination. READ THE WHOLE METHOD before editing.
- titles.actress_id is the FILING actress (folder owner) — it is NOT a cast indicator and must not be used for any cast logic here. Cast = rows in title_actresses.
- TitleBrowseService.toSummaries (~lines 367–496) builds the TitleSummary projection and already calls titleActressRepo.findActressIdsByTitle(titleId) per title (TitleActressRepository ~line 25).

PINNED VALUES (use exactly — these predicate shapes are reviewed against this text verbatim)
- New params: ageMin, ageMax (nullable Integer), castMode enum SOLO|ANY|ALL (default SOLO).
- castMode without any age param = silent no-op (filter inactive). Validation errors → 400: ageMin>ageMax, negatives, non-numeric, unknown castMode string.
- All three modes are EXISTS-shaped predicates appended to WHERE — no new join into the paginated select (must not multiply rows or disturb tag GROUP BY/HAVING):
  SOLO:  EXISTS (SELECT 1 FROM title_actresses ta WHERE ta.title_id = t.id AND ta.age_at_release BETWEEN :ageMin AND :ageMax)
         AND NOT EXISTS (SELECT 1 FROM title_actresses ta1, title_actresses ta2
                         WHERE ta1.title_id = t.id AND ta2.title_id = t.id AND ta1.actress_id <> ta2.actress_id)
  ANY:   EXISTS (SELECT 1 FROM title_actresses ta WHERE ta.title_id = t.id AND ta.age_at_release BETWEEN :ageMin AND :ageMax)
  ALL:   EXISTS (SELECT 1 FROM title_actresses ta WHERE ta.title_id = t.id)
         AND NOT EXISTS (SELECT 1 FROM title_actresses ta WHERE ta.title_id = t.id
                         AND (ta.age_at_release NOT BETWEEN :ageMin AND :ageMax OR ta.age_at_release IS NULL))
  (ALL is STRICT: a credit with NULL age fails the title.)
- Single-value query: caller passes only one of ageMin/ageMax → treat missing bound as open (ageMin only = "at least", ageMax only = "at most"); both present = inclusive range; adapt the BETWEEN accordingly (>= / <= variants are fine; semantics are what's pinned).
- TitleSummary: add nullable Integer ageAtRelease, populated for SOLO-CAST titles only (exactly one credit row), whenever computable — independent of whether the filter is active. Multi-cast → null. Extend the existing per-title cast lookup minimally (e.g., have the repository return (actress_id, age_at_release) pairs instead of bare ids, or add one read method to TitleActressRepository); avoid an N+1 beyond what toSummaries already does.

EXACT CHANGES
1. JdbiTitleRepository.findLibraryPaged: thread the three new params; when the age filter is active, append the pinned predicate for the selected mode to the WHERE clause with bound parameters (no string-concatenated values). Both count and page queries (if separate) must agree.
2. TitleRepository interface: extend the signature.
3. TitleBrowseService.findLibraryPaged: accept and pass through; populate TitleSummary.ageAtRelease in toSummaries per the pinned rule.
4. TitleRoutes /api/titles/browse: parse ageMin/ageMax/castMode with the pinned validation; default castMode "solo" (case-insensitive).

FILES YOU MAY MODIFY
- src/main/java/com/organizer3/repository/jdbi/JdbiTitleRepository.java
- src/main/java/com/organizer3/repository/TitleRepository.java
- src/main/java/com/organizer3/repository/TitleActressRepository.java (+ its jdbi impl) — only if you choose the read-method route for ageAtRelease
- src/main/java/com/organizer3/web/TitleBrowseService.java
- src/main/java/com/organizer3/web/routes/TitleRoutes.java
- the TitleSummary record (wherever it is defined — find it; likely in web/)
- corresponding test files
DO NOT TOUCH: SchemaUpgrader, SchemaInitializer, Application.java, any MCP handler, ActressYamlLoader, DraftPromotionService, any UI file.

TESTS (required — repository tests on real in-memory SQLite seeding title_actresses.age_at_release directly; route/service tests with Mockito)
| Case | Expect |
| SOLO: solo title age 22, filter 20–25 | match |
| SOLO: multi-cast title with one credit age 22, filter 20–25 | NO match |
| SOLO: zero-credit title | NO match |
| ANY: multi-cast, one of three credits in range (others NULL) | match |
| ALL: all credits in range | match; one out of range → no match; one NULL → no match; zero credits → no match |
| NULL age never satisfies a range (SOLO/ANY) | excluded |
| ageMin only / ageMax only | open-ended bounds work |
| age filter + tag filter + company filter | intersection; tag AND-semantics unaffected |
| filter inactive (no age params, castMode present) | results identical to pre-change behavior incl. ordering + pagination |
| Route validation: min>max, negative, non-numeric, bad castMode | 400 |
| TitleSummary.ageAtRelease | solo+computable → value; multi-cast → null; solo non-computable → null |

ACCEPTANCE CRITERIA
- Pinned predicates implemented verbatim in semantics; no new join in the paginated select; tag HAVING logic untouched.
- Existing browse behavior bit-identical when the filter is inactive.
- `./gradlew test` green.

REPORT BACK: diff summary, the full final SQL-building section of findLibraryPaged pasted as text (the reviewer reads it), and test results.
```

### Task 2b — Recompute trigger wiring

```
You are implementing one self-contained change in the Organizer3 Java codebase (Javalin + JDBI + SQLite, NO Spring; manual wiring in Application.java). Do NOT commit. Do NOT modify files outside the set below. Run `./gradlew test` and report results.

OBJECTIVE
Invoke an existing recompute service (AgeAtReleaseRecomputer.recomputeAll() — already implemented in src/main/java/com/organizer3/db/AgeAtReleaseRecomputer.java, returns changed-row count) at the points where its inputs (actress DOBs, release dates, credit rows) change in bulk.

TRIGGER POINTS (all of them; each call logs the changed-row count at INFO)
1. App startup: in Application.java, immediately after schema initialization/upgrade completes, run recomputeAll() once. It is sub-second; run it synchronously.
2. Actress YAML loads: after a load completes in src/main/java/com/organizer3/command/LoadActressCommand.java and after the BATCH completes in src/main/java/com/organizer3/utilities/task/actress/LoadAllActressesTask.java (once per batch, NOT per actress). DOBs arrive here via ActressYamlLoader.updateProfile.
3. Draft promotion: at the end of a successful promotion in DraftPromotionService (find it under src/main/java/com/organizer3/enrichment/ or javdb/draft/ — read it; place the call after the transaction that writes credits/enrichment commits, once per promotion).
4. Credit mutations: at the end of the MCP tool handlers for merge_actresses, reassign_title_credit, remove_title_credit (find them under src/main/java/com/organizer3/mcp/ — search for those tool names; call after a successful non-dry-run mutation only).

CONSTRAINTS
- Constructor-inject the recomputer; wire in Application.java following the existing manual style.
- Do NOT modify AgeAtReleaseRecomputer itself, JdbiTitleRepository, TitleBrowseService, TitleRoutes, or any schema file.
- Dry-run paths and failed operations must NOT trigger recompute.

TESTS (Mockito — verify recomputeAll() is invoked exactly once per trigger; not invoked on dry-run/failure paths; batch task triggers once per batch)

ACCEPTANCE CRITERIA: all five trigger points wired; dry-run/failure paths clean; `./gradlew test` green.
REPORT BACK: diff summary listing each trigger site (file + method) and test results.
```

### Task 2c — MCP repair tool `recompute_age_at_release`

```
You are implementing one self-contained change in the Organizer3 Java codebase (Javalin + JDBI + SQLite, NO Spring). Do NOT commit. Do NOT modify files outside the set below. Run `./gradlew test` and report results.

OBJECTIVE
A new MCP tool `recompute_age_at_release` so out-of-band DB edits have a one-call resync, doubling as a misattribution detector.

BACKGROUND
- AgeAtReleaseRecomputer (src/main/java/com/organizer3/db/AgeAtReleaseRecomputer.java) already exists with recomputeAll() → changed-row count and findImplausible() → rows with age_at_release < 18 OR > 70 (title_id, actress_id, age). Do not modify it (if findImplausible's return shape lacks display fields you need — title code, actress canonical_name — join in YOUR handler's query or extend via a NEW query in your handler, not by editing the recomputer).
- Study 2–3 existing read+write MCP tool handlers under src/main/java/com/organizer3/mcp/ for the registration pattern, parameter parsing, and result formatting; register the tool exactly the same way (Application.java / the MCP server registry — follow the existing pattern).

TOOL CONTRACT
- Name: recompute_age_at_release. No required params. Optional: dry_run (default false) — when true, report what WOULD change (count of rows whose stored value differs from derived) without writing.
- Result: { changedRows, totalComputable, implausible: [ { titleId, code, actressId, actressName, age } ] } — implausible list returned on BOTH dry and live runs (it reflects post-recompute state on live runs).
- Implausible = age < 18 OR age > 70. Report, never suppress or clamp values.

FILES YOU MAY MODIFY
- new handler under src/main/java/com/organizer3/mcp/
- the MCP tool registration point + Application.java (additive wiring only)
- corresponding test files
DO NOT TOUCH: AgeAtReleaseRecomputer, JdbiTitleRepository, TitleBrowseService, TitleRoutes, schema files, other tool handlers.

TESTS (Mockito for the handler; real in-memory SQLite only if you add a join query): dry_run=true writes nothing and reports prospective count; live run invokes recomputeAll and reports its count; implausible rows surface with code+name; empty implausible list when all ages plausible.

ACCEPTANCE CRITERIA: tool registered and discoverable like existing tools; contract above exact; `./gradlew test` green.
REPORT BACK: diff summary, the tool's result JSON shape as implemented, test results.
```

---

## 3. Integration & rollout (Gate C — Opus, mechanical)

1. Merge order 2a → 2b → 2c; resolve additive `Application.java` overlaps; `./gradlew test` after each merge.
2. `./gradlew installDist`, restart the app (schema upgrades to V69 and the startup recompute seeds/refreshes on boot).
3. **Live verification against the measured baselines** (proposal §2, measured 2026-06-12 — expect ≥, since data grows):
   - `SELECT COUNT(*) FROM title_actresses WHERE age_at_release IS NOT NULL` → ≈ 14,278+.
   - Solo-eligible check ≈ 10,236+; spot-check 2–3 known actresses' titles by hand-computed age.
   - `/api/titles/browse?ageMin=18&ageMax=21` returns ≈ the 2,481-row neighborhood measured during design (solo mode).
4. Run `recompute_age_at_release` once; review the implausible-age triage list — first real outlier harvest; file findings as triage items, do not "fix" inside this rollout.
5. Idempotency probe: run the tool again immediately → changedRows = 0.

## 4. Risk register (what the gates exist to catch)

| Risk | Where | Mitigation |
|---|---|---|
| Age off-by-one at birthday / leap day | Task 1 | pinned integer-date formula + boundary test matrix + Gate A |
| Stale values survive corrections | Task 1 | NULL-out re-derivation pinned; Gate A check 2 |
| New predicate breaks tag HAVING / pagination / sort | Task 2a | EXISTS-only shapes pinned; "bit-identical when inactive" test; Gate B adversarial read; Opus takeover path |
| ALL-mode NULL semantics drift to loose | Task 2a | pinned strict shape + explicit NULL-credit test |
| Recompute fires on dry-run/failed mutations | Task 2b | explicit constraint + Mockito verification |
| Migration/version inventory drift (SchemaInitializerTest) | Task 1 | named in prompt + Gate A check 4 |
| V69 number collision with concurrent work | Task 1 | renumber-and-report instruction |
| changed-row count lies (full-UPDATE touches all rows) | Task 1 | "differs, not touched" pinned + idempotency test + §3.5 probe |
