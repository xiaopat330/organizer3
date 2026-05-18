# AI Picker Assist — Phase 2 Implementation Plan

Companion to `spec/PROPOSAL_AI_PICKER_ASSIST.md`. Phase 2 = surface AI suggestions in the existing picker UI. Human still confirms; no auto-apply (that's Phase 3).

**Estimated total**: ~half day with parallel dispatch.

## Decisions locked in
- Banner placement: above the candidate strip
- Pending state: show "AI assist pending" banner with refresh link
- Refresh endpoint (Track B): include
- Test coverage: Playwright UI smoke test

## Convention markers
- 🟢 = Sonnet-clean (mechanical, well-specified)
- 🟡 = Sonnet with Opus review of policy logic
- 🔴 = Opus (judgement calls / cross-cutting)

## Where Phase 2 plugs in

The picker UI lives at `src/main/resources/public/modules/v2/discovery/enrich.js` (rendering at `renderErrorPickerContent`, candidate cards at `buildErrorCandidateCard`). Candidate data arrives through `JavdbEnrichmentActionService.getErrorsForActress → FailedJobSummary.reviewDetail` (JSON string of `enrichment_review_queue.detail`). AI suggestions are already populated by Phase 1's sweeper on the same `enrichment_review_queue` row (`ai_suggestion_slug` / `_confidence` / `_reason` / `_at`); they are currently invisible to the UI.

The work is to thread these four columns through to the client and render them as (a) a one-line banner above the candidate strip and (b) a visual highlight on the suggested candidate card.

## Phase 2 explicit non-goals
- No auto-apply (Phase 3)
- No "always pre-confirm" behavior — user clicks remain mandatory
- No new picker modes (`mode=suggest` stays purely advisory in this phase)
- No backfill of suggestions on already-resolved rows (Phase 4)

---

## Wave 1 — Server-side surfacing (2 parallel tracks)

### Track A 🟢 — Extend the failed-jobs payload

**File**: `src/main/java/com/organizer3/javdb/enrichment/EnrichmentQueue.java` (and its query implementation in the same package), plus `JavdbEnrichmentActionService.FailedJobSummary` record.

- Add four optional fields to `FailedJobSummary` (back-compat ctor preserved):
  - `String aiSuggestionSlug`
  - `String aiSuggestionConfidence` (the outcome: `agreed | phi4_only | gemma_only | conflict | both_abstain | error`, or null)
  - `String aiSuggestionReason`
  - `String aiSuggestionAt` (ISO-8601 string)
- Extend the SQL behind `listFailedWithReviewQueue` to select these columns from `enrichment_review_queue`.
- The action-service mapping (`JavdbEnrichmentActionService.getErrorsForActress` lines ~83-96) just passes them through.
- Tests: one in-memory SQLite test asserting that a queue row with populated `ai_suggestion_*` columns surfaces in the failed-job summary.

**~70 LOC. Sonnet in 30 minutes.**

### Track B 🟢 — `/api/utilities/review-queue/{id}` lightweight refresh endpoint (defensive)

The existing picker can stale out if the user opens it before the sweeper has filled the suggestion. To keep the UI simple we add an optional refresh endpoint that returns the four new fields for a single review-queue id.

**File**: `src/main/java/com/organizer3/web/routes/UtilitiesRoutes.java`

- `GET /api/utilities/review-queue/{id}/ai-suggestion` → returns `{slug, confidence, reason, at}` or 404 if no row.
- Read-only; no policy. Pure projection of the four columns.
- Tests for: row exists with suggestion, row exists without suggestion, row not found.

**~50 LOC. Sonnet in 20 minutes.** If you want to skip this entirely and rely on full-page refresh, mark Track B optional — it costs nothing to keep but adds nothing if the page is regenerated frequently. Default: include it. Sonnet should ask Opus before omitting.

---

## Wave 2 — Client-side rendering (1 track, depends on Wave 1.A)

### Track C 🟡 — Picker UI: banner + card highlight

**File**: `src/main/resources/public/modules/v2/discovery/enrich.js`

**Insertion points** (line numbers approximate as of current main):
- `renderErrorPickerContent` (~line 569): insert banner element between the header and the candidate cards.
- `buildErrorCandidateCard` (~line 639): add `.er-candidate-card-ai-pick` class when the candidate's slug matches `aiSuggestionSlug`.

**Job-object plumbing**: when the panel renders, the job already has the four new fields available via the failed-jobs payload (Track A). Read them off `job.aiSuggestionSlug` etc.

#### Banner content (one line, no side panel)

| outcome | banner text | banner color |
|---|---|---|
| `agreed` | "AI suggests: {slug} (both models agreed) — {reason}" | green/positive |
| `phi4_only` | "AI suggests: {slug} (phi4 only) — {reason}" | yellow/caution |
| `gemma_only` | "AI suggests: {slug} (gemma only) — {reason}" | yellow/caution |
| `conflict` | "AI couldn't pick — phi4 and gemma3 disagreed" | gray/neutral |
| `both_abstain` | "AI abstained — both models couldn't pick" | gray/neutral |
| `error` | (no banner; render nothing) | n/a |
| null (no suggestion yet) | "AI assist pending" + small refresh link calling Track B endpoint | gray |

Banner has a small dismiss "×" that hides it for the current view (no persistence — Phase 2 stays stateless).

#### Card highlight

- The candidate card whose `slug` matches `aiSuggestionSlug` gets `.er-candidate-card-ai-pick` (subtle border + a small "AI pick" pill in the corner of the card).
- Hovering the pill shows the reason text in a native `title` tooltip.
- DO NOT auto-scroll or auto-focus the suggested card — just highlight.

#### CSS

Add styles in the closest existing stylesheet — likely `src/main/resources/public/css/v2.css` or whichever sheet defines `.er-candidate-card`. Search for `.er-candidate-card` and add siblings:
- `.er-picker-ai-banner` (base + per-outcome modifier classes)
- `.er-candidate-card-ai-pick` and `.er-candidate-card-ai-pick .er-ai-pick-pill`

Keep palette consistent with the design-system tokens (see [[project_design_system]] in memory).

#### Tests

- A Playwright UI smoke test under `src/test/java/com/organizer3/playwright/` matching the existing ui-tagged pattern: open the picker on a fixture row with an `agreed` AI suggestion → assert the banner is visible with the right text, AND the suggested candidate card has the highlight class.
- One negative test: row without AI suggestion → no banner element, no highlighted card.

**~200 LOC (mostly JS + CSS). Sonnet in 1 hour. Opus reviews the outcome→banner mapping and palette choices.**

---

## Wave 3 — Manual smoke (Track D)

### Track D — Human-driven verification

- Confirm `enrichment.assist.mode: shadow` still in `organizer-config.yaml` and sweeper has produced a healthy sample of suggestions (already true from Phase 1 smoke).
- Hard refresh the picker page; open an `agreed`-suggestion title → banner reads "AI suggests …", suggested card highlighted.
- Open a `conflict` title → banner reads "AI couldn't pick", no card highlighted.
- Open a row known to have no suggestion yet (rare unless sweeper paused) → banner reads "AI assist pending".
- Manually pick a candidate. Confirm the existing resolve flow is unchanged.

**Human-driven. ~20 minutes.**

---

## Schedule

```
HOUR 1
  Wave 1 parallel: [A] [B]              (2 Sonnets, ~30m max)

HOUR 2
  Wave 2: [C]                           (Sonnet, ~1h) + Opus review

HOUR 3
  Wave 3: [D] human smoke               ~20m
```

**Total: ~3-4 hours wall-clock, ~85% Sonnet LOC, ~15% Opus oversight (banner mapping, palette).**

---

## Out-of-scope for Phase 2 (don't let scope creep in)

- Auto-apply with delay → Phase 3
- Backfill on already-resolved rows → Phase 4
- TranslationWorker migration to orchestrator → Phase 4
- A separate "AI Picks" inbox or dashboard view → Phase 4+
- Bulk-confirm-all-agreed action → Phase 3 (it's auto-apply with the timer skipped)

---

## Exit criteria for Phase 2

1. Picker page renders the AI banner with the correct copy for each of the 6 outcome states (or "pending"/"error" cases).
2. Suggested candidate card is visually distinguishable from siblings.
3. Existing picker flow (clicking a candidate to resolve) is unchanged and continues to work.
4. New tests passing; existing tests passing.
5. Spot-check 5 live rows in the UI: banner copy + highlight match the underlying `ai_suggestion_*` columns.

Once all five hold, Phase 2 is done.
