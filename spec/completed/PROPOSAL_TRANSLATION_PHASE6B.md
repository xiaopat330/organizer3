# Translation Phase 6b: Stage-Name Resolver Backend

> **Status: PROPOSED** — drafted 2026-05-05.
>
> Implementation spec for §4 ("Piece B: stage-name resolution in curation") of
> `PROPOSAL_TRANSLATION_PHASE6.md`. Read that proposal first for motivation and
> the wider 6a/6b/6c/6d arc.
>
> 6b ships the backend only: resolver method, fan-out callback, fuzzy matcher.
> No editor UX, no bulk-enrich wiring (those are 6c, 6d). Independently
> shippable: with 6b alone, draft Actresses with kanji stage names get romaji
> pre-fill on the *next* draft populator run after the LLM completes — good
> enough to validate the data path before touching UI.
>
> **This area is fragile** (LLM quality, fuzzy-match recall, callback fan-out
> semantics). Expect to revise after first real trials.

---

## 1. Goal

Wire `DraftPopulator.autoLinkActress` to consult curated + LLM stage-name
sources, and add an asynchronous fan-out so completed LLM translations
back-fill `draft_actresses.english_first_name` / `english_last_name` for every
draft that needs them.

After 6b ships, the user-visible difference is: opening any draft Actress whose
kanji stage name has been seen before will show pre-filled English first/last
fields. New kanji names trigger a background LLM call; the next draft
populator pass (or a re-open) picks up the result.

---

## 2. Out of scope (deferred to 6c / 6d)

- Bulk-enrich integration: `BulkEnrichToDraftTask` does not yet enqueue
  translations for unmatched cast (6c).
- Live editor UX: no "translating…" badge, no auto-fill on open while LLM is
  in flight (6d).
- Re-running the resolver on existing drafts on backfill (would need a sweeper
  similar to `TitleTranslationSweeper`).
- Promoting accepted suggestions into `stage_name_lookup` — by design.

---

## 3. New `TranslationService` method

### 3.1 Signature

```java
/**
 * Resolves a kanji stage name to romaji, escalating to the LLM on miss.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>Curated {@code stage_name_lookup} (highest priority, human-reviewed).
 *   <li>Any {@code stage_name_suggestion} row (accepted OR unreviewed).
 *       Returns the most recent {@code suggested_romaji}.
 *   <li>Miss: enqueue an LLM stage-name translation for {@code kanjiName}
 *       (no-op if a queue row already exists for this kanji+strategy) and
 *       return {@link Optional#empty()}.
 * </ol>
 *
 * <p>Step 2 deliberately uses unreviewed suggestions. The pre-fill is a
 * best-guess to save typing — it is NOT promoted into any canonical store
 * until the user accepts the resulting Actress. {@link #resolveStageName}
 * remains the strict, accepted-only path for read sites that need
 * authority (e.g. catalog rendering).
 *
 * @return romaji guess if available now; empty if queued or kanji is blank.
 */
Optional<String> resolveOrSuggestStageName(String kanjiName);
```

### 3.2 Implementation notes

- Normalize input via `TranslationNormalization.normalize` (NFKC + trim) — same
  as `resolveStageName`. All reads and writes touching `kanji_form` MUST use the
  normalized form to preserve the `UNIQUE(kanji_form, suggested_romaji)`
  invariant on `stage_name_suggestion`.
- Step 2 needs a new repository method:
  `Optional<String> findLatestUsableSuggestion(String normalizedKanji)`.
  Returns:
  - the row's `final_romaji` if `review_decision = 'accepted'` and
    `final_romaji IS NOT NULL` (human-corrected wins); else
  - `suggested_romaji` if `review_decision = 'accepted'` (LLM accepted as-is); else
  - `suggested_romaji` if `review_decision IS NULL` (unreviewed — fine for
    pre-fill); else
  - empty if `review_decision = 'rejected'` (the user said no — never pre-fill
    a rejected guess).

  Order by `id DESC` and take the first usable row. Index hint: the existing
  `idx_sns_kanji` on `kanji_form` covers the lookup.
- Step 3 enqueues using the existing translation queue with:
  - `strategyKey = "label_basic"` (matches Phase 5 stage-name capture path).
    Note: the worker's stage-name suggestion hook is gated on
    `looksLikeStageName(sourceText)` — a heuristic on the input shape, not on
    the strategy id. Any strategy that returns clean romaji works; we pick
    `label_basic` for cache-key parity with Phase 5 and per the §3.2
    measurement results in `PROPOSAL_TRANSLATION_PHASE6.md`.
  - `callbackKind = null`, `callbackId = null`. The fan-out is triggered by
    the *completion* of any stage-name-shaped translation, not by the
    callback fields on the originating request (see §4.3). Setting them is
    harmless but redundant.
- The worker's existing stage-name write path (`TranslationWorker.java:316`)
  already creates `stage_name_suggestion` rows on LLM completion. 6b adds the
  callback fan-out on top; it does NOT change the write path itself.
- **Normalize the source text BEFORE enqueueing.** The worker calls
  `stageNameSuggestionRepo.recordSuggestion(row.sourceText(), englishText, now)`
  using the raw source text from the queue row. If the kanji is not normalized
  before enqueue, the suggestion table accumulates rows with full-width or
  punctuation-variant `kanji_form` values that the read path
  (`findLatestUsableSuggestion(normalized)`) will silently miss. **Discipline:
  always pass `TranslationNormalization.normalize(kanji)` as the source text
  when enqueueing stage-name translations.** This is a 6b discipline; the
  worker is not changed.
- **Idempotency: queue has NO dedup today.** Confirmed in
  `JdbiTranslationQueueRepository.enqueue` (line 43) — a plain `INSERT`, no
  unique constraint. Two back-to-back `resolveOrSuggestStageName` calls for
  the same kanji would produce two queue rows and two LLM calls. 6b MUST
  add a dedup check before insert. Two acceptable approaches:
  1. Check-then-insert in a single transaction: `SELECT 1 FROM translation_queue
     WHERE strategy_id = ? AND source_text = ? AND status IN ('pending',
     'in_flight') LIMIT 1`. Skip insert on hit. Acceptable race window for a
     local single-writer setup.
  2. Add a partial unique index on `(strategy_id, source_text)` filtered to
     `status IN ('pending','in_flight')` and let `INSERT OR IGNORE` handle it.
     Cleaner but is a schema change.

  Recommend approach 1 for 6b — no schema change, change is local to the
  enqueue helper. If we discover real contention later, promote to approach 2
  via a migration. Implement as a new helper `enqueueIfAbsent(...)` on
  `TranslationQueueRepository`; the existing `enqueue` stays for callers that
  legitimately want duplicate rows.

---

## 4. CallbackDispatcher fan-out

### 4.1 New callback kind

Register `"stage_name_suggestion"` with a handler that fans out to all
`draft_actresses` rows whose kanji stage name matches:

```java
// pseudocode for the registered handler
void onStageNameSuggestion(Long suggestionRowId, String englishText) {
    // englishText is the LLM romaji result.
    // suggestionRowId is the stage_name_suggestion.id.
    StageNameSuggestionRow row = stageNameSuggestionRepo.findById(suggestionRowId);
    if (row == null) return;  // suggestion deleted; cache write already happened upstream

    String[] parts = splitRomaji(englishText);  // see §4.2
    String first = parts[0];
    String last  = parts[1];  // may be null for single-token results

    int updated = draftActressRepo.fillEnglishNameByKanji(
        row.kanjiForm(), first, last);
    log.info("stage_name_suggestion fan-out: kanji='{}' romaji='{}' updated {} draft_actresses",
        row.kanjiForm(), englishText, updated);
}
```

The fan-out write:

```sql
UPDATE draft_actresses
   SET english_first_name = :first,
       english_last_name  = :last,
       updated_at         = :now
 WHERE stage_name = :kanji
   AND english_first_name IS NULL
   AND english_last_name  IS NULL
   AND link_to_existing_id IS NULL
```

The `link_to_existing_id IS NULL` clause skips drafts that have already been
linked to a real Actress (the user picked one manually before the LLM
finished — don't overwrite that decision). The `english_first_name IS NULL`
clause is the human-edit guard, mirroring the existing dispatcher pattern.

**Normalization invariant:** the WHERE comparison `stage_name = :kanji` is a
strict TEXT equality. `stage_name_suggestion.kanji_form` is normalized (NFKC)
in 6b's read/write discipline. `draft_actresses.stage_name`, however, is
written from `entry.name()` in `DraftPopulator` — raw javdb output, not
normalized. To make the fan-out hit reliably:

- **Normalize at write time** in `DraftPopulator.writeCastSlots`: store
  `TranslationNormalization.normalize(entry.name())` in
  `draft_actresses.stage_name`. Display impact is limited to NFKC
  canonicalization (full-width digits/punctuation collapse to half-width;
  CJK characters are unaffected for any normal stage name).
- **Migration for existing rows:** add a one-shot in `SchemaUpgrader` that
  rewrites `draft_actresses.stage_name` to its NFKC form, and uniquely
  re-resolves any duplicates that collapse together (rare; can be a fail-loud
  log + leave alone if it happens).

If we wanted to avoid touching display/storage, the alternative is to register
a SQLite app-defined function `nfkc()` and write
`WHERE nfkc(stage_name) = :kanji`. Rejected — adds runtime overhead on every
fan-out and an indirection we'd have to remember at every read site.

### 4.2 `splitRomaji` rules

Input is the LLM-returned romaji. Output is `(first, last)`. Rules:

- Trim, collapse whitespace.
- Single token → `(token, null)`. Per proposal §4.2 "last-name-only match if
  the LLM result is a single token" — but for *pre-fill* the safest default is
  to put the single token in first-name and leave last-name null. The fuzzy
  matcher (§5) still runs against the full string.
- Two tokens → `(token0, token1)`. The proposal does NOT specify Western vs
  Japanese order; go with LLM output order on the assumption gemma4 emits
  "Given Family" (Western order). Document as an open question to revisit
  after first batch of real outputs.
- Three+ tokens → `(token0, join(rest, " "))`. Rare; logged at DEBUG.

Lives in `ActressFuzzyMatcher` as a static helper alongside the matcher
(both manipulate the same romaji shape).

### 4.3 Worker callback-id back-fill

Today the worker writes the `stage_name_suggestion` row at the end of LLM
completion. The dispatcher then runs with `callbackId = ?`.

Concrete plumbing:

1. The worker, when handling a stage-name strategy completion, persists the
   `stage_name_suggestion` row first and gets back the new `id`.
2. It then calls `dispatcher.dispatch("stage_name_suggestion", suggestionId,
   romaji)`, regardless of what `callbackKind` was on the originating
   `TranslationRequest`. This makes the fan-out unconditional on stage-name
   completions — even completions originally enqueued without a callback (e.g.
   from a future bulk-enrich path) trigger the same fan-out.
3. The originating request's `callbackKind` field becomes informational for
   stage-name strategies. Document this in `CallbackDispatcher`'s class
   javadoc.

This design choice is what makes the per-row-key vs per-kanji-key mismatch
disappear: the request can be anonymous; the completion always knows the
kanji and the suggestion row id, so the fan-out is naturally per-kanji.

---

## 5. `ActressFuzzyMatcher`

### 5.1 Purpose

Given the LLM romaji guess, find any `Actress` already in the catalog whose
stored names match under common transformations. If a match is found,
`DraftPopulator` should set `link_to_existing_id` instead of creating a draft
with a romaji guess.

### 5.2 Match candidates (in order)

All passes go through `ActressRepository.resolveByName`, which already
searches both `actresses.canonical_name` AND `actress_aliases.alias_name`
case-insensitively. Aliases are first-class match targets — this is the
mechanism that handles renamed actresses like Sarasa Hara (see §5.6).

1. **Exact** match on `resolveByName(romaji)` (covers canonical + aliases).
2. **First+last reversal**: `"Yuma Asami"` → try `"Asami Yuma"` via
   `resolveByName`.
3. **Punctuation normalization**: strip `-`, `,`; collapse internal
   whitespace; re-try via `resolveByName`.
4. **Last-name-only match** if the romaji is a single token: search any
   Actress whose last token of `canonical_name` OR any alias's last token
   matches. See §5.3.

Match levels are tried in order; first hit wins. Returns
`Optional<MatchResult>` with the matched Actress id and the rule that hit
(useful for logging confidence — rule 4 is much weaker than rule 1).

### 5.3 Repository support

Passes 1–3 reuse the existing `ActressRepository.resolveByName` — no new
method needed for the canonical/alias side.

Pass 4 needs:

- `List<Actress> findByLastTokenCi(String lastToken)` — return any Actress
  whose `canonical_name` OR alias `alias_name` ends with `lastToken`
  (case-insensitive, after whitespace split). Limit to a small N (10). The
  matcher decides what to do with ambiguity (skip if >1 hit — pre-fill is
  meant to be cheap; we'd rather under-link than mis-link).

This is the only new repo method 6b adds.

### 5.4 Where it lives

`com.organizer3.translation.ActressFuzzyMatcher`. Thin wrapper around
`ActressRepository`, no DI of its own beyond the repo. Pure functions where
possible (the splitting, reversal, normalization steps are static). The DB
calls are the only side effect.

Why not under `enrichment/` or `repository/`: Phase 6c will call this from the
draft populator and Phase 6d may call it from a route. Translation owns the
romaji-shaped data model; this is the read-side counterpart to
`StageNameSuggestionRepository`.

### 5.5 Tests

`ActressFuzzyMatcherTest` — corpus-based, real in-memory SQLite, fixtures
seeded from a small slice of `reference/actresses/` covering:

- Exact match on canonical_name.
- **Exact match on alias_name — the Hara Sarasa control case.** Real
  fixture: actress with canonical_name "Sarasa Hara" and aliases including
  "Natsume Iroha", 夏目彩春, 原 更紗 (per
  `reference/actresses/hara_sarasa/hara_sarasa.yaml`). Assert all of these
  resolve to the same Actress id:
  - LLM romaji "Natsume Iroha" → Sarasa Hara (alias hit, current identity).
  - Reversed "Iroha Natsume" → Sarasa Hara (reversal pass via alias).
  - Direct kanji 夏目彩春 (no LLM needed, but verifies the Pass 1 alias path).
- Reversal: "Yuma Asami" finds "Asami Yuma" (both via canonical and via
  alias).
- Punctuation: "Mei-Sa" finds "Meisa".
- Last-name-only single-token match (canonical and alias).
- Last-name-only ambiguity (2 hits) returns empty (under-link, not mis-link).
- No match returns empty.

The Hara Sarasa case is the controlling test for the renamed-actress path —
if the matcher regresses on her, the §5.6 scenario is broken.

### 5.6 Renamed-actress case (the Sarasa Hara problem)

**Real example (control case for unit testing):** Hara Sarasa,
`reference/actresses/hara_sarasa/hara_sarasa.yaml`. DB canonical_name is
"Sarasa Hara" (her 2007–2013 romanized identity). She returned in 2013
under a new identity: kanji 夏目彩春, romaji "Natsume Iroha". The same DB
record has aliases for both eras (夏目彩春, Natsume Iroha, 原 更紗). New
titles from her current career arrive bearing 夏目彩春.

**Path through 6b:**

1. `DraftPopulator.autoLinkActress` Pass 1 (canonical) and Pass 2 (alias)
   both check the kanji 夏目彩春 directly — they hit IF that kanji is already
   stored as an alias of Sarasa Hara. (Aliases support kanji entries.)
2. If no kanji alias exists yet: 6b's `resolveOrSuggestStageName(夏目彩春)`
   eventually returns "Natsume Iroha" (LLM romaji).
3. `ActressFuzzyMatcher.match("Natsume Iroha")` calls `resolveByName` →
   hits the alias_name row → returns Sarasa Hara's actress id. Draft links
   correctly.

**This works automatically IF either the kanji or the romaji of the current
identity is already stored somewhere (canonical or alias) for Sarasa Hara.**
The Hara Sarasa fixture is rich enough to assert all three pre-conditions
in unit tests (kanji-as-alias hit, romaji-as-alias hit, romaji-reversal
hit).

**The unsolvable case:** First-ever sighting of a current identity for
which no kanji or romaji alias has been seeded yet. Nothing in the system
bridges the new kanji back to the archival actress. The fuzzy matcher
pre-fills first/last name from the LLM guess and the user either
(a) accepts and unknowingly creates a duplicate Actress, or
(b) recognizes the mistake and manually re-links to the existing record.

This requires industry knowledge our system does not have. **6b cannot
auto-solve this; it can only make it less bad once a single human
correction is captured.** The capture mechanism — adding the kanji + romaji
as aliases when the user manually re-links — is a 6d concern (see §7.6).
For 6b alone, the user's one-time manual fix per renamed actress is the
only path forward; subsequent titles bearing that kanji auto-link via
alias.

A purely manual UI ("identity-merge" / "alias-add" tool) is also a
reasonable fallback if the editor-flow capture in 6d turns out to be
fragile or ambiguous in practice. Flag for revisit after first trials.

---

## 6. `DraftPopulator` integration

Extend `autoLinkActress` (`DraftPopulator.java:290`) with passes 4 and 5 after
the existing slug-anchored pass 3:

```java
// Pass 4: curated stage-name lookup (synchronous)
Optional<String> romaji = translationService.resolveOrSuggestStageName(entry.name());
if (romaji.isPresent()) {
    Optional<Actress> fuzzy = fuzzyMatcher.match(romaji.get());
    if (fuzzy.isPresent() && !fuzzy.get().isRejected()) {
        return fuzzy.get().getId();
    }
    // Pass 5a: fuzzy-match miss but romaji is in hand → pre-fill the draft.
    // The DraftActress write happens in writeCastSlots; pass the romaji
    // up via a side channel (return value extension — see below).
}
// Pass 5b: resolveOrSuggestStageName returned empty → enqueued, no romaji yet.
// Returns null; the fan-out callback will fill english_*_name later.
return null;
```

**Return type change:** `autoLinkActress` currently returns `Long` (the linked
Actress id, or null). To carry the romaji guess through to `writeCastSlots`,
either:

- Widen the return to `record AutoLinkResult(Long actressId, String englishFirst, String englishLast)`,
  or
- Have `autoLinkActress` write the english_*_name directly via an upsert helper
  on `DraftActressRepository`.

The record approach is cleaner for testing — `autoLinkActress` stays a pure
read function and the persistence stays in `writeCastSlots`. Use that.

**`writeCastSlots`** is updated to populate `englishFirstName` and
`englishLastName` on the `DraftActress` builder when the result carries them.

**Order of writes vs fan-out:** when 6b triggers an LLM enqueue from inside
`autoLinkActress`, it returns `(null, null, null)`. The draft gets persisted
with kanji-only stage_name, no english fields. Later the LLM completes; the
fan-out updates this draft (and any siblings). Re-opening the draft in the
editor then shows the pre-fill. The new `enqueueIfAbsent` helper (§3.2) is
what prevents duplicate LLM calls when `autoLinkActress` runs repeatedly for
the same kanji before the first completion lands.

---

## 7. Open questions / known fragility

These are flagged for revision after first trials:

1. **First+last token order.** §4.2 assumes gemma4:e4b emits "Given Family"
   order. Validate against the first 50 real LLM outputs; flip the split if
   wrong. Cheap to revise — single helper.
2. **Last-name-only ambiguity policy.** §5.2 rule 4 says "skip if >1 hit." May
   be too strict for common surnames (`Tanaka`, `Suzuki`). Alternative: surface
   all candidates in 6d's UI and let the user pick. For 6b, conservative skip
   is correct.
3. **Fan-out window.** The fan-out only fills drafts where
   `english_first_name IS NULL`. If the user has typed a partial first name
   into a draft Actress before the LLM completes, the LLM result is silently
   dropped on the floor. Acceptable for 6b; the suggestion row still exists
   for any future draft.
4. **Cache-hit dispatch.** The worker's current dispatch (`TranslationWorker.java:333`)
   runs `callbackDispatcher.dispatch(row.callbackKind(), row.callbackId(),
   englishText)` only on the live-completion path; the cache-hit branch
   short-circuits. For 6b's fan-out to work on subsequent identical-source
   enqueues (e.g. after a strategy-version bump that invalidates and re-runs),
   the cache-hit path must also fire the dispatcher. Verify and patch if
   missing — small change, but easy to overlook.
5. **Renamed-actress first-sighting (Sarasa Hara case).** See §5.6. 6b cannot
   bridge a brand-new kanji-identity to its archival actress without prior
   alias data. Realistic mitigations (6d-territory):
   - Editor-flow alias capture: when the user manually re-links a draft
     Actress to an existing Actress whose canonical/aliases differ from the
     draft's stage_name + romaji, prompt to add both as aliases.
   - Standalone "identity-merge" UI: list draft Actresses adjacent to existing
     Actresses with similar romaji and let the user merge with one click.

   **It is plausible that the best 6b can do is the first trivial case (kanji
   or romaji is already a known alias) and we accept that the rest is
   user-driven via 6d's UI. Don't promise more.**
6. **Existing draft_actresses kanji values.** The normalization-on-write
   migration in §4.1 only fixes data going forward and the one-shot rewrite
   in `SchemaUpgrader`. If any external code path writes to
   `draft_actresses.stage_name` outside `DraftPopulator`, those rows will
   silently fall out of fan-out coverage. Audit with a grep before declaring
   6b done.

---

## 8. Tests

- `TranslationServiceImplTest`:
  - `resolveOrSuggestStageName` returns curated romaji synchronously (curated
    hit).
  - Returns latest unreviewed suggestion when curated misses (suggestion hit).
  - Returns empty AND enqueues when both miss (queue assertion via repository).
  - Idempotent enqueue: two back-to-back misses produce one queue row.
  - NFKC normalization is applied (full-width input matches half-width row).
- `CallbackDispatcherTest` (or new `StageNameSuggestionCallbackTest`):
  - Fan-out updates all matching kanji drafts with `english_first_name IS NULL`.
  - Skips drafts where `english_first_name` is non-null (human-edit guard).
  - Skips drafts where `link_to_existing_id` is non-null (already-linked
    guard).
  - Single-token LLM result writes to first only; last is null.
  - Suggestion row deleted between request and callback → no-op, no exception.
- `ActressFuzzyMatcherTest`: see §5.5.
- `DraftPopulatorTest`:
  - Pass 4 hit (curated romaji + fuzzy Actress match) → returns existing
    Actress id.
  - Pass 5a (curated romaji + fuzzy miss) → DraftActress persisted with
    english_first_name/last_name set, link_to_existing_id null.
  - Pass 5b (curated miss → enqueue) → DraftActress persisted with kanji
    stage_name only, no english fields, link_to_existing_id null.
  - End-to-end test exercising the full path including a worker tick that
    completes the LLM translation and re-querying the draft to verify
    fan-out fill (real in-memory SQLite, mocked OllamaAdapter returning a
    canned romaji).

---

## 9. Files touched (estimate)

**New:**
- `src/main/java/com/organizer3/translation/ActressFuzzyMatcher.java`
- `src/test/java/com/organizer3/translation/ActressFuzzyMatcherTest.java`

**Modified:**
- `src/main/java/com/organizer3/translation/TranslationService.java`
  (interface +1 method)
- `src/main/java/com/organizer3/translation/TranslationServiceImpl.java`
  (impl + enqueue helper)
- `src/main/java/com/organizer3/translation/repository/StageNameSuggestionRepository.java`
  + JDBI impl (new `findLatestUsableSuggestion` query; possibly `findById`)
- `src/main/java/com/organizer3/repository/ActressRepository.java` + JDBI impl
  (new `findByLastTokenCi` for fuzzy matcher pass 4; UNION canonical + aliases)
- `src/main/java/com/organizer3/translation/repository/DraftActressRepository`
  (or wherever `draft_actresses` lives — `fillEnglishNameByKanji` query)
- `src/main/java/com/organizer3/translation/TranslationWorker.java`
  (stage-name completion always dispatches via `"stage_name_suggestion"` kind
  with the new suggestion row id)
- `src/main/java/com/organizer3/translation/CallbackDispatcher.java`
  (registration of new kind in wiring)
- `src/main/java/com/organizer3/javdb/draft/DraftPopulator.java`
  (`autoLinkActress` returns `AutoLinkResult`; `writeCastSlots` propagates
  english fields; injection of `TranslationService` + `ActressFuzzyMatcher`)
- `src/main/java/com/organizer3/Application.java`
  (wire `ActressFuzzyMatcher`; pass into `DraftPopulator`)

**No schema changes.** `draft_actresses` already has `english_first_name` and
`english_last_name`; `stage_name_suggestion` already has `id` (Long PK).

---

## 10. Sketch — Phase 6c (bulk enrichment integration)

> Light sketch only. Promote to its own spec (`PROPOSAL_TRANSLATION_PHASE6C.md`)
> after 6b ships and the actual surfaces are settled.

**Goal:** wire `BulkEnrichToDraftTask` to trigger LLM stage-name translations
for unmatched kanji cast entries during bulk runs, so drafts populate over
time without per-draft user action.

**What needs to happen:**

- Bulk enrich already iterates drafts via `BulkEnrichToDraftTask`. Each draft
  flows through `DraftPopulator`. With 6b in place, `autoLinkActress` already
  enqueues LLM translations on cast misses — so 6c may be **almost free**:
  the bulk task already triggers the same path 6b instrumented for live
  curation.
- The thing 6b doesn't do that 6c needs: ensure the bulk task does NOT block
  on LLM. Today's `autoLinkActress` is synchronous up to the enqueue (which
  is fast); the LLM call itself runs in the worker thread. So bulk runs
  complete at their normal speed; drafts persist with kanji-only stage_name
  for unmatched cast; fan-out fills them later.
- **Sanity check before declaring 6c "free":** confirm bulk run does not
  trip queue depth limits or starve the worker. At ~30 s/title for prose
  but ~5 s for label_basic stage-names, a 100-draft bulk with ~3 cast/draft
  could enqueue 300 stage-name translations → ~25 min of background work.
  Acceptable, but add an INFO log on bulk enrich completion that reports
  how many translations were enqueued.

**What might still need code in 6c:**

- A "bulk-enrich progress" surface that surfaces "N stage-name translations
  pending" alongside the bulk-enrich completion message — so the user knows
  drafts are still incoming and not to immediately review.
- An idempotent re-run path: if the user re-runs bulk enrich on the same
  drafts before LLM completion, `enqueueIfAbsent` (§3.2) prevents
  duplicates. Verify under bulk-scale test.

**Tests sketch:**
- `BulkEnrichToDraftTaskTest` — drift-resistant assertion that unmatched
  kanji cast entries result in queued translation requests.
- Volume sanity test: bulk-enrich N drafts with M unmatched cast each;
  assert ≤ N×M queue rows (no duplicates).

**Out of scope for 6c:** real-time UI feedback; that's 6d.

---

## 11. Sketch — Phase 6d (live curation editor UX)

> Light sketch only. Promote to its own spec (`PROPOSAL_TRANSLATION_PHASE6D.md`)
> after 6b/6c ship.

**Goal:** in the curation editor, give the user immediate signal when a
draft Actress has a kanji-only stage_name with a translation in flight, and
auto-fill the english fields when the LLM result lands. **Plus**: provide
the alias-capture flow that addresses the Sarasa Hara unsolvable-case
(§5.6) — when the user manually re-links a draft to an existing Actress,
prompt to add the kanji + romaji as aliases.

**Pieces:**

1. **"Translating…" badge.** Per-draft-Actress indicator in
   `title-editor-draft.js` cast slot rendering. Shown when:
   - `english_first_name` is null AND
   - a `stage_name_suggestion` row exists for this kanji (in flight or
     usable) OR a `translation_queue` row is pending for this source text.

   Backend exposes a small endpoint `GET /api/translation/stage-name-status?kanji=…`
   returning `{ status: "queued" | "ready" | "missing", romaji: string? }`.
   Polling on a slow timer (every 5–10 s while editor is open and at least
   one draft Actress is in the pending state) is fine — no need for
   websockets.

2. **Auto-fill on completion.** When polling reports `status: "ready"` and
   the local draft is still unsaved, populate first/last name input fields
   with the romaji (split per §4.2 rules). Show a small "filled by
   translation — accept or edit" cue. User can dismiss or edit before
   saving.

3. **Alias-capture on manual re-link (the Sarasa Hara fix).** When the user
   clicks "link to existing Actress" and picks an Actress whose canonical_name
   and aliases do NOT include the draft's `stage_name` (kanji) or its
   pre-filled romaji guess, surface a modal:

   > You linked this draft to "Sarasa Hara". Add 夏目彩春 and "Natsume Iroha"
   > as aliases of Sarasa Hara so future titles auto-link?
   >
   > [Add both aliases]  [Add kanji only]  [Add romaji only]  [Skip]

   On accept, write to `actress_aliases`. Existing alias-management routes
   probably cover this; verify before designing fresh routes.

4. **Standalone identity-merge UI (fallback).** If the editor-flow modal
   turns out to be too easy to dismiss / too easy to miss / too noisy to be
   useful, provide a separate Tools page that lists draft Actresses adjacent
   to existing Actresses with similar romaji and lets the user merge
   one-at-a-time. Lower priority — only build if 6d.3 turns out fragile in
   real use.

**Open questions for 6d (don't try to answer now):**
- Polling cadence vs websocket — start with polling.
- Badge visual design — defer to implementation.
- Whether the alias-capture modal is per-link or batched at draft-save time.
- Whether to suppress auto-fill when the user has started typing (probably
  yes — never overwrite typing).

**Tests sketch:**
- Playwright pin around: open editor with an unmatched kanji draft, mock LLM
  completion, assert auto-fill fires and fields update.
- Playwright pin around the alias-capture modal flow.
- Backend test on the status endpoint (queued / ready / missing branches).

**Risk:** 6d is the most UX-heavy of the 6 phases and the one most likely
to require revision after first user trials. Ship 6b and 6c first; let
real usage surface what 6d actually needs.
