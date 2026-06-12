# SPEC: Kanji Misattribution Guards & Standing Attribution Health

**Status:** Consolidated spec v2, 2026-06-12 — **ready for implementation planning.** No implementation yet; awaiting user go-ahead.
**Process:** Draft authored by Opus 2026-06-12; vetted same day against the working tree + live DB by a second model pass; this v2 folds all review corrections inline and pins every previously-open design decision. **Intended pipeline: Opus generates the formal implementation plan and orchestrates; Sonnet subagents implement.** Each item below carries acceptance criteria and a test matrix so a subagent prompt can be self-contained.
**Origin:** The enrichment-attribution audit of 2026-06-12 (`reference/enrichment_audit/ENRICHMENT_ATTRIBUTION_AUDIT.md`). Two ad-hoc scans surfaced 13 enriched actresses whose javdb `cast_json` did not contain their kanji — 12 confirmed problems plus 1 false positive. Every check the audit ran already exists as logic somewhere in the codebase, but it runs **on-demand, after attribution, only when invoked**. This spec turns that after-the-fact audit into attribution-time guardrails and a standing health check, and schedules the one strategic fix that removes the audit's dominant false-positive class.

**Relationship to existing tools:** This is not net-new detection logic. `find_enrichment_cast_mismatches` already performs the exact "is the actress's kanji in this title's `cast_json`" check; `find_suspect_credits` already does the co-occurrence check; the gender filter (`CastJsonFilter` / `DraftPopulator.writeCastSlots`) already demonstrates the cheap skip-guard pattern. The gap is **timing and automation**, not capability.

---

## 1. Goal & Scope

Prevent kanji-based actress misattribution from landing silently, by:

1. **Item A** — closing the YAML-loader phantom gap (load-time silent actress creation on romaji mismatch).
2. **Item E** — persisting link provenance on draft slots (prerequisite for B's precise gating).
3. **Item B** — a kanji-presence guard at draft promotion (enrich-time silent wrong-bind — and the amplification loop it feeds).
4. **Item F** — the same cast cross-check on the YAML portfolio path (code-reuse misfiling).
5. **Item C** — making the audit standing: automatic detection + a persistent-suppression model.
6. **Item D** — multi-slug-per-actress support (deferred; design-doc task only).

**In scope:** `ActressYamlLoader`, the draft populate/promote path, `RevalidationCronScheduler`, the enrichment review queue, the utilities health-check framework, and (D, deferred) `javdb_actress_staging` schema. **Out of scope:** the comp-under-listing confound (javdb data incompleteness — tolerated, not solved), v1 legacy UI surfaces (per `modules/LEGACY.md` — no edits without explicit user approval; new review reasons render inert there, which is acceptable).

---

## 2. Motivation — what the audit traced back to in code

| Audit failure mode (examples) | Root cause | File |
|---|---|---|
| **Wrong-kanji binds** — 1059 male 鮫島 bound; 420 緒川はる→笹倉杏 | `autoLinkActress` Passes 3–4 link by **slug** or **fuzzy curated romaji** and never verify the actress's kanji appears in *this* title's `cast_json`. Promotion inserts with no kanji check — **and then registers the slug mapping (FIX 1a) and backfills `stage_name` (FIX 1b), teaching Pass 3 to repeat the mistake on every future title for that slug.** | `DraftPopulator.autoLinkActress:349–414`; `DraftPromotionService.insertTitleActresses:884–950` |
| **Loader phantoms** — Karen Tojo spawned #7331 | `ActressYamlLoader.apply` resolves by `canonical_name`/alias only; its second attempt passes the kanji into `resolveByName`, which ignores the `stage_name` column. No match → silent phantom create. (Enrichment Pass 2.5 *does* bind by kanji — an asymmetry.) | `ActressYamlLoader.apply:240–263`; `JdbiActressRepository.resolveByName:214–232` |
| **Portfolio code-reuse misfiles** (latent) | The YAML portfolio path matches titles **by product code alone** and on stub-create files the title under the actress (`titles.actress_id`); codes are reused across eras (`reference_force_enrich_code_reuse_trap`). | `ActressYamlLoader` portfolio loop (~300–350) |
| **Rename-chain false positives** — the dominant audit noise (1071, 2286, 772, 1084, 1623) | `javdb_actress_staging` stores **one slug per actress** (PK=`actress_id`, UNIQUE `javdb_slug`). A multi-name actress can only hold one slug, so her other-name titles always read as "mismatched." | `JavdbStagingRepository` (ON CONFLICT(actress_id)) |
| **Comp under-list noise** | javdb lists only headliners on compilations; nothing models partial casts. | (data confound — tolerated via gating, §3.B) |

The kanji-in-cast check the audit ran by hand is `find_enrichment_cast_mismatches`. The gender filter — a one-line `if (!"F".equals(entry.gender())) continue;` at `DraftPopulator.writeCastSlots:315` — is the precedent for these guards: cheap, applied at the moment of attribution.

**The amplification loop (why Item B is the highest-value item).** A single wrong Pass-4 fuzzy bind does three things at promotion: (a) mis-credits the title; (b) registers the wrong slug→actress mapping in `javdb_actress_staging`, so **every future title for that slug auto-binds to the wrong actress via Pass 3**; (c) if the actress had no `stage_name`, stamps the wrong kanji onto her identity. The audit's worst cases (1059) are this loop running unchecked. The guard must therefore fire **before** the staging upsert and stage_name backfill, not merely before the `title_actresses` insert.

---

## 3. Specification

> Shared convention used below: **"kanji-presence check"** = does the actress's `stage_name`, any `actress_aliases.alias_name`, or any `alternate_names_json[].name` appear in this title's `cast_json` names — compared NFKC-normalized and whitespace-stripped (same containment semantics as `FindEnrichmentCastMismatchesTool:93–123`, plus NFKC). Implemented once as `CastPresenceCheck` (Item B) and reused by Item F.

### Item A — Close the YAML-loader phantom gap

**Problem:** Any profile YAML whose `profile.name` romaji ≠ DB `canonical_name`, with no matching alias, silently creates a new LIBRARY actress — even when the kanji `stage_name` exactly identifies an existing row. Produced the Karen Tojo phantom (#7331) on batch-load #1; the workaround was post-hoc SQL + `merge_actresses`.

**Design (decisions pinned):**

1. In `ActressYamlLoader.apply()`, replace the second resolution attempt — currently `resolveByName(profile.name().stageName())`, which checks canonical+alias columns only — with **`actressRepo.findByStageName(stageName)`**. That method (`JdbiActressRepository:200–211`) already implements the required safety guard: it returns a match only when **exactly one non-rejected** actress has that `stage_name`; 0 or ≥2 → `Optional.empty()` → today's behavior. No new guard logic needed.
2. **Normalize before comparing:** NFKC-normalize and trim the YAML stage_name before lookup (scrape-side `draft_actresses.stage_name` is already NFKC; YAML strings are not guaranteed to be). Add the normalization inside the loader, not inside `findByStageName` (other callers pass already-normalized values).
3. **Make creation loud:** add a `created` boolean (or created-actress id) to `LoadResult`; `LoadActressCommand` / `LoadAllActressesTask` summaries must list every actress *created* (vs enriched) so batch loads surface phantom creation without manual SQL.
4. **Strict mode:** add an optional `strict` flag to the load entry points (default `false` = current behavior). When `true`, a no-match outcome **fails that YAML's load with a clear error instead of creating** — campaign batch loads always expect the actress to pre-exist.

**Acceptance criteria:**
- A YAML whose romaji matches nothing but whose kanji `stage_name` matches exactly one live actress binds to her (no phantom).
- Kanji matching 0 or ≥2 actresses → unchanged behavior (create, or strict-fail).
- `LoadResult` distinguishes created vs enriched; batch summary lists creations.
- `strict=true` never creates an actress.

**Tests (in-memory SQLite repo test + loader unit test):**
| Case | Expect |
|---|---|
| Romaji miss, kanji unique match (Karen Tojo fixture) | binds to existing id |
| Romaji miss, kanji matches 2 actresses | creates (non-strict) / fails (strict) |
| Romaji miss, kanji matches only a rejected actress | creates (the rejected row must not bind) |
| Kanji differs only by NFKC form / stray space | still binds |
| Romaji hit | kanji fallback never consulted (unchanged fast path) |

**Cost:** small (call-swap + plumbing). **Risk:** low.

### Item E — Persist link provenance on draft slots

**Problem:** `draft_title_actresses.resolution` records *that* a slot resolved (`pick`/`unresolved`/`create_new`/`sentinel:*`) but not *how* — a Pass-1 exact bind, a Pass-4 fuzzy bind, and a human pick in the draft editor are indistinguishable at promotion. Item B needs provenance to gate precisely; future audits get it for free (the 2026-06-12 audit had to infer provenance from mismatch patterns).

**Design (decisions pinned):**

1. **Migration `applyV67()`** (next free slot after V66; idempotent `ALTER TABLE draft_title_actresses ADD COLUMN resolved_via TEXT` guarded by a column-exists check, per house migration style).
2. Values: `canonical` | `alias` | `stage_name` | `slug` | `fuzzy` | `manual` | `prefill`. NULL = legacy row (pre-migration) — treated by consumers as *unknown*, equivalent to `fuzzy` for guarding purposes (conservative).
3. `DraftPopulator.autoLinkActress` returns which pass fired — extend `AutoLinkResult` with a `via` field; `writeCastSlots` persists it.
4. Any draft-editor route that sets/changes a slot's resolution to a user-chosen actress writes `resolved_via='manual'`. (Implementer: locate the slot-update route(s) in the draft editor web routes; all human writes go through them.)

**Acceptance criteria:** every newly-populated slot carries the correct `resolved_via`; human edits overwrite it with `manual`; legacy NULL rows don't break any reader.

**Tests:** populator unit test asserting `via` per pass (mock repo per pass outcome); repository round-trip; editor-route test asserting `manual` on user pick.

**Cost:** small. **Risk:** low. **Must land before or with Item B.**

### Item B — Kanji-presence guard at draft promotion

**Problem:** Slots resolved via Pass 3 (slug) or Pass 4 (fuzzy) are inserted into `title_actresses` at promotion with no verification the actress is in the title's cast — then the promotion **learns** the bind (FIX 1a slug registration, FIX 1b stage_name backfill), making the error self-reinforcing (see §2). The corrective scan exists but only runs when invoked.

**Design (decisions pinned):**

1. **Shared predicate:** new class `CastPresenceCheck` (package `com.organizer3.javdb.enrichment`), constructor-injected with JDBI/repos, exposing `Result check(long actressId, String castJson)` where `Result ∈ {PRESENT, ABSENT, UNCHECKABLE}`. `UNCHECKABLE` = actress has no `stage_name` AND no kanji-bearing alias/alternate name (nothing to look for). Comparison: NFKC + whitespace-strip containment, mirroring `FindEnrichmentCastMismatchesTool`'s semantics.
2. **Guard scope (uses Item E):** at promotion, the check runs **only** for slots with `resolved_via IN ('slug','fuzzy')` or `NULL` (legacy/unknown). Exempt: `canonical`/`alias`/`stage_name` (the matched string came from this title's cast — inherently present), `manual` (explicit human choice; attribute normally), `create_new` (actress is being created *from* this cast entry), `sentinel:*` and skips (no real bind).
3. **Comp/size gate:** enforce only when the title's female cast count `nfem ≤ 3` **and** the title is not compilation-tagged. Comp signal: `title_enrichment_tags` joined to `enrichment_tag_definitions` where `curated_alias = 'compilation'` (resolve the tag id by alias at runtime — do not hardcode id 7). Verified live 2026-06-12: 791 comp-tagged titles, 414 of them in the small-cast band — the tag exempts real comps the size gate alone would punish.
4. **On ABSENT (enforced case) → divert:** the slot is **not** inserted into `title_actresses`, FIX 1a and FIX 1b are **skipped for that slot**, the rest of the title's slots promote normally (promotion already tolerates skipped slots — `resolveActressId` returning null), and a review row is enqueued:
   - `enrichment_review_queue` with `reason='guard_cast_mismatch'`, `slug=` the slot's javdb slug, `detail=` JSON `{actressId, actressName, stageName, resolvedVia, nfem, castNames:[...]}`.
   - The existing open-row unique index is `(title_id, reason)`: if two slots of one title divert, the second enqueue is silently ignored — **accepted**; triage of the row re-examines the whole title's cast anyway.
5. **On UNCHECKABLE or gated-out (comp / large cast):** attribute normally, log at WARN with the same detail payload. Never divert what cannot be verified.
6. **Purge exemption:** `EnrichmentReviewQueueRepository.purgeStale()` ages out recoverable reasons after 7 days. `guard_cast_mismatch` rows must be **exempt from purging** (they represent diverted data, not transient fetch noise; silent expiry = silent data loss).
7. **Triage UI (v2 only):** add `guard_cast_mismatch` rendering to `modules/v2/workflow/` — label, detail display, actions = `mark_resolved` (minimum). The human fixes via existing tools (`reassign_title_credit`, draft re-edit, alias add) then resolves the row. A richer "approve-this-bind" inline action is **deferred** (out of scope for this wave). v1 legacy workflow files are **not modified** (LEGACY.md); the unknown reason renders with no actions there, which is acceptable.

**Acceptance criteria:**
- A `fuzzy`/`slug` slot whose actress fails the kanji-presence check on a small non-comp cast: no `title_actresses` row, no staging upsert, no stage_name backfill, one open `guard_cast_mismatch` review row; sibling slots unaffected.
- Same slot on a comp-tagged or 4+-female title: attributes normally, WARN logged.
- `manual`/`canonical`/`alias`/`stage_name`/`create_new`/sentinel slots: never checked, never diverted.
- `guard_cast_mismatch` rows survive `purgeStale()`.
- v2 workflow lists and resolves the new reason.

**Tests (Mockito for promotion service; in-memory SQLite for queue/predicate):**
| Case | Expect |
|---|---|
| fuzzy bind, kanji absent, nfem=1, no comp tag (420-style fixture) | divert + queue row + no FIX 1a/1b |
| slug bind, kanji absent (1059-style fixture) | divert |
| fuzzy bind, kanji present after NFKC/space normalization | attribute |
| fuzzy bind, kanji absent, comp-tagged title | attribute + WARN |
| fuzzy bind, kanji absent, nfem=5 | attribute + WARN |
| manual bind, kanji absent | attribute (exempt) |
| actress with NULL stage_name + no kanji aliases | UNCHECKABLE → attribute + WARN |
| resolved_via NULL (legacy) treated as guarded | divert when absent |
| two slots of one title both divert | one queue row, both slots withheld |
| purgeStale with old guard row | row retained |

**Cost:** medium. **Risk:** medium (comp gating tuned above; routing is soft — nothing destructive).

### Item F — Cast cross-check on the YAML portfolio path

**Problem:** The portfolio loop in `ActressYamlLoader` matches titles **by product code alone**. For an existing title it overwrites enrichment metadata (`enrichTitle`: title/date/notes/grade) and replaces tags; for a missing code it **creates a stub filed under the actress** (`titles.actress_id`). Product codes are reused across eras, so a portfolio entry can silently misfile or stamp wrong metadata onto a *different work* wearing the same code. (Note: this path does **not** write `title_actresses` credit rows — the exposure is filing + metadata, not credits.)

**Design (decisions pinned):**

1. For an **existing** title that has enrichment with `cast_json` present: run `CastPresenceCheck` for the loaded actress. On `ABSENT` (comp-gated exactly as Item B): **skip `enrichTitle` + tag replacement for that entry** and append a structured line to the existing `LoadResult.unresolved` list (`"<code>: cast-mismatch — actress kanji not in enriched cast; skipped"`). On `PRESENT`/`UNCHECKABLE`/gated: proceed unchanged.
2. Titles with no enrichment / no `cast_json`: unchanged (no ground truth to check against).
3. Stub creation (code not in DB) is **not blocked** — there is nothing to check against — but remains visible via the existing `titlesCreated` count.
4. The loader is batch/headless: the load summary **is** the review surface; no review-queue rows from this path.

**Acceptance criteria:** a portfolio entry whose code collides with an enriched different-cast title leaves that title's metadata/tags untouched and is reported in the load output; all other entries behave exactly as today.

**Tests:** loader test with stubbed enrichment rows — mismatch-skip, match-proceed, comp-tagged-proceed, no-enrichment-proceed; `LoadResult.unresolved` content asserted.

**Cost:** small (one call site reusing `CastPresenceCheck`). **Risk:** low (report-and-skip; never deletes).

### Item C — Standing attribution health

**Problem:** The audit was a manual session; new misattributions surface only at the next manual sweep.

**Design (decisions pinned):**

1. **Extract detection into a service.** The query logic of `FindEnrichmentCastMismatchesTool` and `FindSuspectCreditsTool` moves into a shared `AttributionAuditService` (the MCP tools become thin wrappers — no behavior change to the tools).
2. **Sink = NOT the review queue.** `enrichment_review_queue` is title-keyed (`title_id NOT NULL`); these findings are **actress-level aggregates** (mismatch % across a filmography). Forcing them into title rows would spam one row per mismatched title. Instead:
   - New table `attribution_findings` (migration `applyV68`): `actress_id, finding_class ('cast_mismatch'|'suspect_credit'), metric REAL, sample_json TEXT, first_seen_at, last_seen_at, status ('open'|'suppressed'|'resolved')`, unique on `(actress_id, finding_class)`.
   - **Cron:** a third phase in `RevalidationCronScheduler.tick()` (after drain + safety-net), batch-limited, upserting findings: new → `open` + log WARN summary; existing → refresh `last_seen_at`/metric; vanished → mark `resolved`.
   - **Surface:** a new check in the existing utilities health-check framework (`com.organizer3.utilities.health.checks`, pattern: `UnloadedYamlsCheck`) reporting the count of open findings, so it nags on the Utilities screen. A read-only MCP tool `list_attribution_findings` for triage from the maintenance session.
3. **Suppression is data, not a hardcoded FP list.** Human dispositions a finding (rename-chain / comp-confound / verified-ok) → `status='suppressed'` + a `note`. Suppression is **invalidated automatically** if the actress's `stage_name` or staging slug changes after suppression time (premise changed → back to `open`). Seed: the audit's confirmed FPs (1071, 2286, 772, 1084, 1623) are suppressed by a one-time data step at rollout, not by code.
4. Suppressed rename-chain rows double as the **seed list for Item D's** multi-slug backfill.

**Acceptance criteria:** cron tick produces/refreshes findings without manual invocation; suppressed findings stay quiet across runs; a stage_name/slug change reopens them; health check reflects open count; MCP tools unchanged in behavior.

**Tests:** service tests on in-memory SQLite (finding lifecycle: new→open, refresh, vanish→resolved, suppress→quiet, premise-change→reopen); scheduler test asserting third phase invoked with batch limit; health-check unit test.

**Cost:** small-medium (orchestration + one table). **Risk:** low.

### Item D — Multi-slug per actress *(deferred — design-doc task only)*

**Problem:** The single-slug constraint (PK `actress_id`, UNIQUE `javdb_slug` — verified) is the root of the dominant audit false-positive class and breaks other-name discovery. Today's workaround: store the dominant-enriched name's slug, accept the rest look mismatched.

**Direction (pinned for the future design doc, not for implementation now):** a new join table `actress_javdb_slugs(actress_id, javdb_slug UNIQUE, is_primary, source, verified_at)` rather than relaxing the staging PK — this also splits the staging table's two mixed concerns (slug↔actress *mapping* vs per-slug scraped *profile cache*: `raw_path`, `name_variants_json`, `avatar_url`), keeps the migration additive (existing rows seed 1:1, `is_primary=1`), and ends the "merge doesn't migrate staging" gotcha. Every slug consumer (Pass 3 anchor, `backfill_actress_slugs_from_cast` — extended to append, `revalidate_enrichment`, `find_slug_duplicate_actresses`, `CastPresenceCheck`) updates to consider all slugs. Backfill beyond 1:1 seeds from Item C's suppressed rename-chain findings.

**This spec's deliverable for D:** none. The implementation plan must **exclude D** from coding waves; optionally schedule a separate design-doc task after C has accumulated suppression data.

**Related:** `project_rena_kodama_comeback_slug_rebind`, `project_slug_duplicate_detector_tool`.

---

## 4. Sequencing & wave sketch (input to the Opus implementation plan)

Order: **A → E → B (+F) → C**; **D excluded** (design-doc only, later).

Suggested wave decomposition (Opus finalizes; each wave = independently buildable + testable, `./gradlew test` green, no behavior coupling across concurrent agents):

| Wave | Agent task | Depends on |
|---|---|---|
| 1a | Item A (loader bind fix + LoadResult/strict) | — |
| 1b | Item E (migration V67 + AutoLinkResult.via + editor-route write) | — |
| 2 | `CastPresenceCheck` + Item B promotion guard + queue reason + purge exemption | 1b |
| 2-ui | v2 workflow rendering for `guard_cast_mismatch` | 2 (can stub on reason string) |
| 3a | Item F (loader reuse of `CastPresenceCheck`) | 2 |
| 3b | Item C (`AttributionAuditService` + V68 + cron phase + health check + MCP list tool) | — (independent of 2; V68 numbering after V67) |

Cross-cutting requirements for every wave:
- **Regression fixtures from the audit's confirmed cases**: Karen Tojo #7331 (A: must bind, not create), the 420 fuzzy collision and 1059 male-kanji bind (B: must divert), a comp-tagged small-cast title (B: must NOT divert). The audit did the expensive part; encoding it as tests is nearly free and is the house standard.
- House testing rules: repository tests on real in-memory SQLite; service/command tests with Mockito. No untestable shapes.
- Migrations: idempotent `applyVN()` per `SchemaUpgrader` house style; V67 = Item E, V68 = Item C (renumber if another migration lands first).
- Rebuild + restart required before new tools/guards are live (no hot reload); the plan should end with a single rebuild/restart step, not one per wave.

---

## 5. Out of scope / known residual

- **Comp under-listing** — javdb lists only headliners on compilations. Tolerated via the §3.B gate (size + compilation tag), not solved.
- **pykakasi reading noise** — the audit's scan-B transliteration aid is discovery-only and is **not** automated by Item C (only scan-A semantics are). Readings continue to be set from canonical romaji, never pykakasi-literal.
- **Over-credited-titles cleanup** — the inverse problem is deferred per the audit; proper fix is a per-title javdb cast re-fetch tool (audit report SIDE-FINDING).
- **Lower-confidence audit rows** (258, 643, 1176, 1179, 859, 2450, 2822) — still open, unrelated to this tooling.
- **Richer guard-row triage actions** (inline "approve bind" that writes credit + staging) — deferred until the basic guard proves out.
- **v1 legacy UI** — untouched per LEGACY.md; new review reasons appear there without actions.

---

## 6. Review history

- **2026-06-12 draft (Opus):** items A–D, sequencing, appendix.
- **2026-06-12 review (second model pass):** all code claims verified accurate against working tree + live DB. Corrections folded into this v2: `findByStageName` already carries the exactly-one guard (A is a call-swap); promotion is an amplification loop via FIX 1a/1b (B's guard must precede them); pass provenance was unpersisted (new Item E); the portfolio code-reuse door (new Item F — and corrected: that path writes filing+metadata, not `title_actresses` credits); comp gating uses the existing `compilation` enrichment tag (verified: 791 tagged, 414 in the small-cast band); Item C's sink moved off the title-keyed review queue onto a findings table + utilities health check; NFKC normalization required at YAML-load time; suppression persisted as data with premise-change invalidation. §A.8's five open questions are all resolved by the pinned decisions in §3.

---

## 7. References

- `reference/enrichment_audit/ENRICHMENT_ATTRIBUTION_AUDIT.md` — the audit that motivated this.
- Memory: `project_enrichment_attribution_audit`, `project_rena_kodama_comeback_slug_rebind` (1-slug arch limit), `project_kanji_dup_detection` (collision safety for Item A), `reference_cocredit_phantom_detection`, `reference_force_enrich_code_reuse_trap` (Item F).
- Code touchpoints (verified 2026-06-12): `DraftPopulator.autoLinkActress:349–414`, `DraftPopulator.writeCastSlots:315` (gender-filter precedent), `DraftPromotionService.insertTitleActresses:884–950` (incl. FIX 1a/1b), `DraftPromotionService.resolveActressId:958–972` (skip semantics), `ActressYamlLoader.apply:240–263` + portfolio loop ~300–350, `JdbiActressRepository.resolveByName:214–232`, `JdbiActressRepository.findByStageName:200–211`, `FindEnrichmentCastMismatchesTool:93–123`, `FindSuspectCreditsTool`, `RevalidationCronScheduler`, `EnrichmentReviewQueueRepository` (enqueue/purgeStale/unique-open-index), `JavdbStagingRepository.upsertActressSlugOnly`, `SchemaUpgrader.applyV66` (last migration), `com.organizer3.utilities.health.checks.UnloadedYamlsCheck` (health-check pattern), `modules/v2/workflow/` (triage UI), `modules/LEGACY.md`.

---

## Appendix A — Context for an external reviewer

*This appendix is written for a reviewer or implementer who has **not** seen the originating conversation, this codebase's history, or its tribal knowledge. It is intended to be self-contained: read this spec top-to-bottom plus this appendix and you should have everything needed to critique the design or help implement it. Everything below is background; the spec itself is §§1–7.*

### A.1 What the system is

**Organizer3** is a single-user media-library manager for a large collection of Japanese adult videos (JAV) spread across ~10 NAS volumes accessed over SMB. It is a Java application (Javalin web UI + JLine3 shell + an MCP server for agent-driven maintenance), **no Spring** — all dependencies wired manually. Persistence is **SQLite via JDBI** with versioned migrations. There is no multi-user concern; the "user" and the maintenance agent (Claude, via the MCP tools) are the only writers.

The domain object of interest here is the **actress** and her **attribution** — i.e. which actresses are credited on which titles (videos). Getting attribution right is the core data-quality problem this spec addresses.

### A.2 The data model (minimum needed to reason about this spec)

- **`titles`** — one row per work, keyed by a product code (e.g. `IPX-633`, `STAR-829`). Has a `label` (the code prefix family), an optional filing `actress_id`.
- **`actresses`** — one row per performer. Relevant columns:
  - `canonical_name` — the **romaji** display name (e.g. "Karen Tojo"). This is the primary identity key for most lookups.
  - `stage_name` — the **kanji/Japanese** name (e.g. `東条かれん`). This is the form that appears in javdb cast data.
  - `tier` — popularity bucket (GODDESS / SUPERSTAR / POPULAR / LIBRARY / …), used for folder placement and campaign scoping.
  - `name_reading` — kana reading of the kanji.
- **`actress_aliases`** — alternate names (romaji or kanji) that should resolve to the same actress. Many lookups check `canonical_name` **then** `actress_aliases`.
- **`title_actresses`** — the attribution join table (title ↔ actress). **This is the table whose correctness the whole spec is about.**
- **`title_javdb_enrichment`** — per-title scraped metadata from javdb. The critical column is **`cast_json`**: a JSON array of the cast as javdb lists it, each element `{slug, name, gender}` where `slug` is javdb's per-performer identifier, `name` is the **kanji** name, and `gender` is `"F"`/`"M"`/`"U"`. This is ground truth for "who is actually in this title" — modulo the comp confound (A.6).
- **`title_enrichment_tags`** + **`enrichment_tag_definitions`** — javdb tags per enriched title; definition rows carry a `curated_alias` (the row aliased `compilation` marks comps — used by the §3.B gate).
- **`javdb_actress_staging`** — maps a local `actress_id` to **one** javdb `slug` (PK = `actress_id`, UNIQUE on `javdb_slug`) plus a per-slug scraped profile cache. The single-slug constraint is central to Item D.
- **`enrichment_review_queue`** — title-keyed triage rows (`title_id NOT NULL`, free-form `reason` string, `detail` text, partial unique index on open `(title_id, reason)`, staleness purge in `EnrichmentReviewQueueRepository.purgeStale`). Items B's guard rows land here; Item C's actress-level findings deliberately do **not**.
- **`draft_titles` / `draft_actresses` / `draft_title_actresses`** — the enrichment draft layer; `draft_title_actresses.resolution ∈ pick|unresolved|create_new|sentinel:*` records slot outcomes (Item E adds `resolved_via`).

### A.3 Romaji vs kanji — why this is the whole problem

Every actress has two names that must stay in sync: a **romaji** canonical name (how the user reads/searches) and a **kanji** stage name (how javdb and the source files label her). Misattribution happens at the seams between these two namespaces:

- javdb cast is **kanji + slug**. Local identity is keyed on **romaji** canonical. Bridging them is fuzzy (transliteration is many-to-one and reading-dependent).
- Japanese AV performers **frequently rename** across studios (a "rename chain"): one human → several kanji names → several javdb slugs over a career. The DB models her as one actress with one slug, so titles under her other names look mis-attributed even though the credit is correct. **This rename-chain pattern was the single most common false positive in the audit** — distinguishing it from genuine misattribution required per-actress research (Wikipedia 改名 history, birthdate cross-checks, javdb "aka" links). Item D is the structural fix.
- **Product codes are reused across eras.** An old code can be re-issued years later for a different work with a different cast, so attaching filing/metadata "by code alone" (which the YAML portfolio loader does) can silently mis-credit. Item F guards this.

### A.4 How attribution actually happens (the enrichment pipeline)

1. **Scrape** → javdb metadata for a title lands in `title_javdb_enrichment.cast_json` (kanji + slug + gender).
2. **Draft populate** (`DraftPopulator`) → for each **female** cast slot (males/unknowns skipped — the gender filter, our guard precedent), try to link it to a local actress via a **5-pass cascade**:
   - Pass 1: exact match on normalized `canonical_name`
   - Pass 2: exact match on `actress_aliases`
   - Pass 2.5: exact match on kanji `stage_name`
   - Pass 3: **slug-anchored** — match via `javdb_actress_staging.javdb_slug`
   - Pass 4: **curated fuzzy** — a blocking LLM call returns romaji for the kanji, then fuzzy-match that romaji to an actress
   - Pass 5: prefill / give up → leave the slot unresolved for the user
   **Passes 1, 2, 2.5 are kanji/name-exact (inherently in-cast). Passes 3 and 4 are the dangerous ones** — they can bind an actress whose name is **not** in this title's cast (slug reuse, fuzzy romaji collision). This is where the audit's wrong-binds (e.g. a male surname `鮫島` bound to a female actress; `緒川はる` fuzzy-collided onto `笹倉杏`) originated. **Item B** inserts the kanji-presence check at promotion, gated by **Item E**'s provenance.
3. **Draft promote** (`DraftPromotionService.insertTitleActresses`) → user confirms slot resolutions; rows written to `title_actresses`. **No kanji validation today** — and the same method then registers the slug→actress mapping (FIX 1a) and backfills `actresses.stage_name` when empty (FIX 1b), which is the amplification loop described in §2.

Separately, **actress profiles** are authored as YAML files (`src/main/resources/actresses/*.yaml`) and loaded by `ActressYamlLoader`, which binds a profile to an existing actress by **romaji canonical, then alias — never by kanji stage_name** — and **creates a new actress ("phantom") on no match.** When a YAML's romaji ≠ the DB's canonical (common after a rename), this spawns a duplicate. **Item A** adds the kanji fallback; **Item F** guards the portfolio half of the same loader.

### A.5 How the audit was conducted (so a reviewer can reproduce / critique it)

Two complementary **read-only** scans, neither complete alone:

- **Scan A — small-cast cast-mismatch %.** For titles with 1–3 female cast members (`nfem BETWEEN 1 AND 3`, to dodge the comp confound), flag credited actresses whose `stage_name`/`canonical_name`/alias does **not** appear in that title's `cast_json` (via `json_each`). High mismatch % across an actress's titles ⇒ wrong-slug bind, conflation, or rename chain. This is exactly what `find_enrichment_cast_mismatches` does as a standing tool — and what Item C automates.
- **Scan B — canonical↔kanji romaji disagreement.** Transliterate the kanji `stage_name` with **pykakasi** and compare to `canonical_name`; large disagreement ⇒ candidate conflation. **Comp-immune but pykakasi-noisy** (it mangles name-specific readings and surnames), so it must be cross-checked against "own-kanji-in-cast %". Used only for discovery, never as a guard; **not** automated by this spec.

Outcome: 13 candidates → 12 confirmed problems (wrong-kanji binds, conflations, stray co-credits, one rename consolidation) + 1 false positive; plus a large tail of rename-chain false positives correctly *not* acted on. Full method SQL and per-actress dispositions are in `reference/enrichment_audit/ENRICHMENT_ATTRIBUTION_AUDIT.md`.

### A.6 Confounds a reviewer must keep in mind

- **Comp (compilation) under-listing.** javdb lists only headliners on compilation titles, so a legitimately-credited actress can be genuinely absent from `cast_json`. Both Scan A and the over-credit inverse scan false-positive on comps. **Items B/F tolerate this** via the size + compilation-tag gate (§3.B), they do not pretend it away.
- **pykakasi unreliability.** Good enough for discovery, wrong often enough that it must never drive an automated write. Readings are set from canonical romaji, never pykakasi-literal.
- **Rename chains.** As above — a mismatch is *evidence*, not proof, of error; confirming requires external research. The guards therefore **divert to human review or skip-and-report; nothing auto-deletes a credit.**
- **Single-slug arch limit.** Because an actress holds one slug, any per-slug check is blind to her other names until Item D lands. Item C's suppression model absorbs the resulting standing-scan noise in the meantime.

### A.7 Environment & how to inspect (for an implementing agent)

- **Live database:** `~/.organizer3/organizer.db` (SQLite, WAL mode). The `data/*.db` files in the repo are 0-byte stubs — do **not** use them. Safe external read/write requires `PRAGMA busy_timeout`; timestamps in this DB are ISO-8601 microseconds with `Z`.
- **Build/run:** Gradle; `./gradlew installDist` then restart the app to pick up code or newly-added profile YAMLs. There is no hot reload for the loader. Tests: `./gradlew test`.
- **Maintenance surface:** most data fixes happen through **MCP tools** (e.g. `merge_actresses`, `rename_actress`, `set_actress_aliases`, `remove_title_credit`, `reassign_title_credit`, `find_enrichment_cast_mismatches`, `find_suspect_credits`, `revalidate_enrichment`, `backfill_actress_slugs_from_cast`). A change shipped to the running binary requires a rebuild+restart before its tool is callable.
- **Specs to read first:** `spec/FUNCTIONAL_SPEC.md`, `spec/IMPLEMENTATION_NOTES.md`. Sibling proposals worth scanning for house style: `spec/PROPOSAL_RELOCATION_TOOLING.md`, `spec/PROPOSAL_CURATION_COMPLETION.md`.
- **UI boundaries:** `src/main/resources/public/modules/LEGACY.md` — files outside `modules/v2/` and `modules/chrome/` are the protected legacy surface; do not modify without explicit user approval. New triage UI goes in `modules/v2/workflow/`.
- **Testing is mandatory and non-negotiable** in this project: new code must be modularized for testability; repository tests use real in-memory SQLite, command/tool tests use Mockito mocks. Every item in §3 ships with its test matrix.

### A.8 Former open questions — now resolved

The draft's five open questions are all pinned in §3: (1) comp gating = size ≤3 + `compilation` enrichment tag; (2) routing = divert auto-binds, flag-don't-divert human picks and uncheckable slots, never block promotion; (3) Item C sink = findings table + utilities health check (review queue is title-keyed and wrong-shaped for actress-level findings), suppression persisted as data with premise-change invalidation; (4) Item D = join table, `is_primary` flag, additive 1:1 seed + suppression-seeded backfill — deferred to its own design doc; (5) no retroactive scan needed — Item C *is* the standing retroactive scan; guards cover new writes.
