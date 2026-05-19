# Translation Phase 6: Wire Up Existing Translation Infrastructure

> **Status: PROPOSED** — drafted 2026-05-04.

The translation service (PRs #37-40, shipped 2026-05-03) built end-to-end pipes for two
high-value translations — actress stage names and original titles — but neither pipe has an
automatic consumer. `resolveStageName` has zero callers outside the translation package, and
`title_original_en` only ever populates when a user manually clicks "Bulk submit" on the
Translation Tools page. This phase wires up both consumers in the places where they actually
matter.

---

## 1. Motivation

Two concrete user-facing pain points, both addressable with code we've already paid for:

- **Curation editor: actress linking is the unfilled gap.** Tools → Curation → Unprocessed
  pulls fielded data from javdb enrichment into a draft title, but if the scraped cast name is
  Japanese kanji and no existing `Actress` matches by `stage_name`/`slug`, the user is left
  with a kanji-only stage name. Today they must manually search the catalog or hand-create a
  new Actress. The translation service can convert the kanji to a romaji guess, retry the
  match, and (on miss) pre-fill new-Actress fields. The LLM is sometimes wrong, but a
  one-phoneme-off pre-fill is far cheaper to correct than a blank field.
- **Original titles stay Japanese forever.** `title_original` is captured during enrichment;
  `title_original_en` is plumbed through CallbackDispatcher; the queue, worker, and cache all
  exist. Nothing automatically asks for the translation. A simple background sweeper closes
  this loop without changing any user-facing flow.

Non-goals:

- No new translation strategies for studio names — explicitly deferred (per §6.2 of the
  original translation proposal).
- No re-translation of existing `title_original_en` values — the cache is treated as
  authoritative; rerunning is the user's existing manual path.
- No auto-promotion of LLM stage-name guesses into the curated `stage_name_lookup`. Curated
  data stays human-reviewed; the LLM cache lives in `stage_name_suggestion`.

---

## 2. Scope

**In scope:**
- Background `title_original` translation sweeper.
- Stage-name resolution invoked from the curation editor (live + bulk enrichment paths).
- Fuzzy Actress match against the romaji result so common ordering/case variants hit.
- New-Actress draft pre-fill (first/last) on Actress-match miss.
- Translation Tools page: stat showing remaining `title_original` translations.

**Out of scope:**
- Studio-name lookup (`maker_en` / `publisher_en`).
- Re-translation of stale `_en` fields when `stage_name_lookup` gets a curated correction.
  Treat as a follow-up if drift becomes visible.
- Auto-creating Actress entities purely from an LLM guess. Pre-fill is for the user to
  approve.

---

## 3. Piece A: background title-translation sweeper

### 3.1 Behavior

A background loop runs every N minutes (proposed default: 5 min). Each tick:

1. `SELECT title_id, title_original FROM title_javdb_enrichment WHERE title_original IS NOT NULL AND (title_original_en IS NULL OR title_original_en = '') LIMIT BATCH_SIZE`.
2. For each row, submit a `TranslationRequest` with the title strategy, callback target
   `title_javdb_enrichment.title_original_en`, and the title's id as the join key.
3. CallbackDispatcher already updates the column on success — no new write path.
4. Cache hits return immediately (free); misses go through the queue at the worker's natural
   pace (~30s/title on gemma4:e4b).

The sweeper itself is stateless. It does not track "in-flight" rows — the translation queue
already deduplicates by (strategy, source). Resubmitting the same `title_original` is cheap
and idempotent.

### 3.2 Strategy selection

**Resolved (2026-05-04, see `reference/translation_poc/PHASE6_TITLE_REPORT.md`):** use
existing `label_basic` strategy. Spot-check on 21 real titles found:

- `label_basic` and `prose` were tied for latency (~23.6 s/call on gemma4:e4b).
- `label_basic` had the best sanitization fidelity — `prose` slipped on `中出し` →
  "Live-In Soapland" (an invented translation). `label_basic` preserved "internal
  ejaculation."
- A candidate `title`-tuned prompt was 35% slower with no measurable quality benefit; the
  model ignored its "preserve names verbatim" instruction anyway.

No new strategy version is needed. The sweeper submits with `strategyKey="label_basic"` and
inherits the existing prompt, version, and qwen2.5 tier-2 fallback. Bonus: any titles
already translated via the manual bulk-submit endpoint used `label_basic` and will be cache
hits.

### 3.3 Throttling and pause

Ollama is local — no external rate limits. CPU is the only constraint. Initial design:
unconditional run. Add a `pauseSweeper` flag exposed on the Tools UI if the user notices
contention with live work. (Translation Service health gating still applies — the sweeper
inherits the worker's pause-when-unhealthy behavior automatically.)

### 3.4 Operational surface

- New stat on Tools → Translation: "Title translations pending: N" (the sweeper's query
  count).
- INFO log on each tick: batch size submitted, cache-hit count.
- Sweeper interval configurable via `organizer-config.yaml` (with sane default).

### 3.5 Volume sanity

At ~30 s/title and a single worker, every 1000 titles ≈ 8h of background work. Acceptable for
an offline sweep. The visible "remaining" count on the Tools page sets correct user
expectation.

### 3.6 Tests

- `TitleTranslationSweeperTest` (real in-memory SQLite): seeds enrichment rows, runs one tick,
  asserts requests submitted to a mock TranslationService, asserts already-translated rows
  are skipped.
- Edge cases: null `title_original`, empty `title_original`, already-set `title_original_en`,
  rows where translation queue is paused.

---

## 4. Piece B: stage-name resolution in curation

### 4.1 Behavior

When the curation editor (or bulk enrichment) processes a draft title and finds a kanji cast
entry with no Actress match:

1. **Curated check (synchronous, fast):** call `resolveStageName(kanji)`. If hit, use the
   romaji to retry the Actress match.
2. **LLM submission (async, slow):** if curated-miss, submit a translation request via a new
   method on `TranslationService` that:
   - Returns `Optional<String>` immediately if a `stage_name_suggestion` row already exists
     for this kanji (cache).
   - Otherwise enqueues an LLM call, persists the result to `stage_name_suggestion`, and
     returns `Optional.empty()` for now.
3. **Async fill:** when the LLM result arrives, the editor surface receives the romaji via
   the existing CallbackDispatcher pattern (same channel as `title_original_en` updates).
4. **Match against existing Actresses:** with the romaji in hand, run a fuzzy match against
   the Actress catalog (see §4.2).
5. **On Actress-match miss:** pre-fill the draft Actress's first-name and last-name fields
   with the romaji split on whitespace. The user reviews and either accepts (creating an
   Actress) or corrects.

### 4.2 Fuzzy Actress match

Today's `CastMatcher` only works against names already on a known `Actress`. For Phase 6 we
need the inverse: given a romaji string, find any Actress whose stored names match under
common transformations. Match candidates (in order):

1. Exact case-insensitive match on `stage_name` or `canonical_name`.
2. First+last reversal: "Yuma Asami" ↔ "Asami Yuma".
3. Hyphen / comma / extra-whitespace normalization.
4. Last-name-only match if the LLM result is a single token.

The match function lives in a new `ActressFuzzyMatcher` class, unit-tested with the realistic
corpus from `reference/actresses/`.

### 4.3 Suggestion-vs-canonical discipline

`stage_name_lookup` is curated, human-reviewed, and seeded from actress YAMLs. **The LLM
never writes here.** All LLM output goes to `stage_name_suggestion`, which the resolver
already consults as a tier-2 source.

When the user accepts a draft Actress whose name was pre-filled from an LLM suggestion, the
new Actress is created normally with that name. The `stage_name_suggestion` row is left
intact (with its `accepted_at` timestamp updated, mirroring Phase 5 plumbing). It does **not**
graduate to `stage_name_lookup` — that remains a YAML-driven seed.

This preserves the invariant: `stage_name_lookup` is correct; `stage_name_suggestion` is a
best-guess cache.

### 4.4 Bulk enrichment path

Bulk Enrich already iterates through unprocessed drafts. For each draft, after the existing
field population:

- For every kanji cast entry that didn't match an Actress, enqueue an LLM stage-name
  translation request (no-op if cached).
- Do not block the bulk run waiting for LLM. Drafts persist; pre-fill arrives later via
  callback.

When the user later opens that draft in the curation editor, the romaji is already cached
and the Actress match runs synchronously.

### 4.5 Live editor UX

Open question — defer detailed design to the implementation PR. Initial direction:

- Show a "translating…" badge on unmatched kanji cast entries while the LLM is in flight.
- Populate first/last name fields and (if matched) auto-link the Actress when the callback
  fires.
- User can dismiss the suggestion or edit before saving.

### 4.6 Tests

- `TranslationServiceImplTest`: extend with new "translate-on-miss-with-suggestion-cache"
  path. Real in-memory SQLite with mocked OllamaAdapter.
- `ActressFuzzyMatcherTest`: corpus-based — build a small fixture Actress catalog, assert
  every documented variant resolves.
- `BulkEnrichmentTest`: drift-resistant assertion that unmatched kanji cast entries trigger a
  translation request.
- Curation-editor integration: at least one Playwright pin around the async fill path,
  written before refactor (per the testing-consistency doctrine).

---

## 5. Risks and mitigations

| Risk | Mitigation |
|---|---|
| LLM produces a wrong-but-canonical-looking name; user accepts without scrutiny. | Pre-fill is visually distinct (e.g., italic / "suggested" label) until user touches the field. Explicit save action required to create Actress. |
| LLM creates a phantom Actress that drifts from real catalog. | Auto-creation is gated on user save. Match step runs against full Actress catalog with fuzzy rules — phantom rate should be low. |
| Background sweeper saturates Ollama and starves live translations. | Single worker is shared; live requests are submitted to the same queue and processed FIFO. If contention is observed, add `pauseSweeperWhileEditing` flag. |
| Title-translation strategy choice is wrong and produces garbage. | 1-hour spot-check before implementation. Cache makes recovery cheap — bump strategy version, sweeper repopulates over time. |
| Fuzzy matcher has too many false positives (LLM "Yuma Asami" matches an unrelated "Yumi Asakawa"). | Strict-mode by default — exact + reversal + obvious normalization only. Soft fuzzy (last-name-only, edit distance) gated behind an explicit flag, default off. Accept higher miss rate as the safer default. |

---

## 6. Phasing

- **Phase 6a — title-translation sweeper.** Single PR. Includes strategy spot-check, sweeper
  loop, Tools UI stat, tests. Zero impact on existing flows. Validates background-translation
  patterns under sustained load before Phase 6b.
- **Phase 6b — stage-name resolver backend.** Single PR. Adds `resolveOrSuggestStageName`,
  fuzzy matcher, `stage_name_suggestion` write path on LLM completion. No editor UX changes
  yet — tested via direct service calls.
- **Phase 6c — bulk enrichment integration.** Single PR. Wires 6b into bulk-enrich. Drafts
  populate over time; user sees results next time they open the draft.
- **Phase 6d — live curation editor UX.** Single PR. Async fill, suggestion badges, accept
  flow. Playwright pin first.

Each sub-phase is independently shippable and independently revertible.

---

## 7. Decisions deferred to implementation

- Sweeper interval default (5 min is a guess; tune based on observed throughput).
- Fuzzy-match strictness defaults (start strict; loosen if recall is too low).
- Live-editor UX details (badge style, dismissal affordance, undo).
