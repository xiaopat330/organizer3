# Translation Phase 6d: Live Curation Editor UX

> **Status: PROPOSED** — drafted 2026-05-06.
>
> Implementation spec for the editor-side review flow originally sketched in
> §11 of `PROPOSAL_TRANSLATION_PHASE6B.md`. Read 6b first for the backend
> contract (`stage_name_suggestion`, fan-out callbacks, resolver lookup
> order). 6d is the user-facing half: signal in flight, auto-fill on
> completion, alias capture on manual re-link.
>
> **This phase replaces the missing "review" surface.** Per Phase 6 design,
> a stage-name suggestion is reviewed *implicitly* — by the user accepting
> the draft Actress that was pre-filled from it. There is intentionally no
> standalone "review unreviewed suggestions" queue: the row is reviewed in
> context, when the user is already making the linking decision. 6d is what
> turns that design into something the user can actually do.
>
> **This area is UX-heavy and likely to revise after first real use.** Ship
> the auto-fill and badge first; treat the alias-capture modal and the
> deferred-queue Tools page as separable follow-ups gated on observed
> behaviour.

---

## 1. Goal

In the curation editor (`title-editor-draft.js`), give the user immediate
signal when a draft Actress has a kanji-only stage name with a translation
in flight, auto-fill the English fields when the LLM result lands, and
prompt to capture aliases when the user manually re-links a draft to an
existing Actress whose canonical_name doesn't already cover the kanji or
romaji.

After 6d ships, the user-visible difference is:

- Cast slots with kanji-only stage names show a "translating…" indicator
  while a stage-name LLM call is pending.
- When the LLM completes, the slot's English first/last fields populate
  automatically (unless the user has started typing).
- Manually re-linking a draft to an existing Actress whose name doesn't
  cover the kanji or pre-filled romaji opens a prompt to capture both as
  aliases of the linked Actress.

---

## 2. Out of scope

- Promoting unreviewed `stage_name_suggestion` rows to `accepted` /
  `rejected` programmatically. Review remains implicit-via-Actress-accept
  per Phase 6 design; the `review_decision` column stays NULL for the
  pre-fill path.
- A standalone batch-review queue UI for `stage_name_suggestion`. The
  deferred-queue Tools page in §6 is a fallback only, built only if §5
  (alias-capture modal) proves disruptive at observed mismatch rates.
- Promoting accepted suggestions into `stage_name_lookup` (Phase 6 §3.2,
  deferred — by design).
- WebSocket / push-based status updates. Polling is sufficient for the
  observed cadence (LLM stage-name calls take ~5s; the editor is open for
  minutes per draft).

---

## 3. Status endpoint

### 3.1 Existing endpoint

`GET /api/translation/stage-name-status?kanji=…` already exists in
`CurationRoutes.java` (registered during 6b). It returns
`{ status: "queued"|"ready"|"missing", romaji?: string }` matching the
contract 6d needs.

The 6d work here is **verification**, not implementation:

- Confirm the existing handler resolves status via the same priority order
  as the resolver: curated lookup → suggestion → pending/in-flight queue
  row → missing.
- Confirm `kanji` is normalised via `TranslationNormalization.normalize`
  before lookup.
- Add a unit/integration test if not already pinned.

If the existing handler diverges from the contract (e.g. doesn't check
in-flight queue rows, or doesn't normalise input), extend it in place
rather than introducing a parallel endpoint. The 6d frontend depends on
the contract above; do not relocate the route between Routes classes
purely for organisational reasons — that is churn.

### 3.2 Polling concerns

None at the route level. The polling cadence is per-editor-session and
naturally bounded (§4.1). Response payload is tiny.

---

## 4. Editor wiring

### 4.1 Polling loop

In `title-editor-draft.js`, when the draft view mounts and renders cast
slots, scan for slots that satisfy **both**:

- `englishFirstName` and `englishLastName` are null/blank
- `stageName` (the kanji form) is non-blank and contains JP characters
  (reuse a small client-side `hasJpChar(s)` helper — same character class
  as the now-removed server-side regex)

For each such slot, kick off a polling loop:

1. Immediately call `GET /api/translation/stage-name-status?kanji=<s>`.
2. If `ready` → fill (§4.2) and stop polling for this slot.
3. If `queued` → schedule next poll in 5 seconds.
4. If `missing` → stop polling for this slot. (The editor does not
   speculatively trigger translations; that's the resolver's job during
   `DraftPopulator` and bulk enrich. If `missing` is returned, no work is
   in flight.)

Cap at 30 polls per slot (~2.5 minutes) as a safety; LLM stage-name calls
average ~5 s, and 30 polls covers the long tail without burning resources
if the worker stalls. After cap, stop polling silently.

Stop all polling when the editor unmounts (`unmountDraftView`).

### 4.2 Auto-fill

When status flips to `ready` and the slot is still un-edited:

- Split `romaji` into first/last using the same rules as
  `ActressFuzzyMatcher.splitRomaji`:
  - 1 token → first = null, last = the token (single-name actresses).
  - 2 tokens → first = tokens[0], last = tokens[1].
  - 3+ tokens → first = tokens[0], last = remaining tokens joined by
    spaces.
  - Empty/null → both null.

  Port these rules to a small JS helper (`splitRomaji(s)`) co-located with
  the editor module. The canonical implementation stays in
  `ActressFuzzyMatcher` server-side; the JS port mirrors it. Do not
  round-trip — the rules are stable and trivial.
- Populate the slot's first/last input fields.
- Show a subtle "filled by translation — accept or edit" cue near the
  slot. Visual is impl-defined; an italic tag or a faded badge that
  disappears on first edit is fine.

**Suppress auto-fill when the user has typed.** Track per-slot whether
either input has received an `input` event since the slot was rendered.
If so, do not overwrite. The polling loop continues to run (so the cue
can still appear if the user clears the field), but `value =` writes are
gated on the dirty flag.

### 4.3 In-flight badge

While a slot is in `queued` state, render a small "translating…" indicator
near the empty English-name fields. The badge replaces / suppresses the
empty-field placeholder so the user can see *why* the field is empty
rather than wondering if javdb returned no romaji.

Badge disappears on transition to `ready` (replaced by the auto-fill cue)
or `missing` (silent — no in-flight work).

---

## 5. Alias-capture on manual re-link

### 5.1 Trigger

In `title-editor-draft.js`, the existing "link to existing Actress" flow
calls `patchResolution(slug, 'pick', { linkToExistingId: id }, idx)`
(currently at line ~356). After that PATCH succeeds, fire a check:

- Fetch the linked Actress's canonical_name + aliases. There's an existing
  endpoint shape — `GET /api/actresses/:id` (verify; if it doesn't return
  aliases inline, add an aliases field or use a parallel
  `/api/actresses/:id/aliases` GET).
- Compute mismatch:
  - `kanjiNeedsAlias` = the slot's `stageName` (kanji) is non-blank AND
    not equal to canonical_name AND not present in aliases.
  - `romajiNeedsAlias` = the slot's pre-filled or current English first +
    last (concatenated with a space) is non-blank AND not equal to
    canonical_name AND not present in aliases.
- If neither flag is true, do nothing — the link doesn't open new alias
  ground. **This silent gate is the key to keeping per-link viable.** Most
  links are to Actresses whose name already matches; the modal only fires
  on genuine alias-capture opportunities.
- If either flag is true, open the modal (§5.2).

### 5.2 Modal

Re-use the modal styling pattern from `near-miss-modal.css` /
`near-miss-modal.js` for visual consistency.

Content:

> You linked this draft to **Sora Aoi**.
>
> Add the following as aliases of Sora Aoi so future titles auto-link?
>
> - 蒼井そら *(kanji)*
> - Sora Aoi *(romaji from translation)*
>
> [Add both]  [Add kanji only]  [Add romaji only]  [Skip]

Show only the rows that flagged in §5.1. If only kanji needs adding, drop
the romaji row and the "Add romaji only" button (and re-label "Add both"
→ "Add alias"). Same for the inverse.

### 5.3 Submission

On any "Add …" button:

- Call `PUT /api/actresses/:id/aliases` (already exists) with the merged
  alias list. The existing route accepts a full alias list and replaces;
  we read-modify-write to add ours.
  - Verify the route's exact contract before implementing — if it
    upserts-only, simpler. If replace-semantics, the read-modify-write
    needs care to not race with concurrent edits. Single-user app, low
    risk in practice.
- Modal dismisses. No success toast — the alias is captured silently;
  future auto-links are the user-visible signal that it worked.

On "Skip":

- Modal dismisses. No state is recorded. **Important:** "Skip" is per-link,
  not per-session — it does not suppress the modal for subsequent links in
  the same editor session. (See §6 for how repeat-skips graduate to a
  deferred queue.)

### 5.4 Mismatch-rate observation

This is the spec's main fragility point. The recommendation rests on the
assumption that "links to existing Actress whose name doesn't cover the
draft's kanji or romaji" is rare — typical curation links to Actresses
whose canonical_name already matches. If real usage produces the modal on
most links during draft review, §5 is too disruptive and §6's
deferred-queue surface graduates from fallback to primary.

**Add an INFO log on each modal trigger and dismissal** (per editor
session, log the count). After the first week of use, count modal-fires
per draft-edit-session. Decision rule:

- ≤ 1 modal fire per draft-edit-session on average → §5 is fine, §6
  remains optional.
- ≥ 2 per session → build §6 and route the modals through it instead.

---

## 6. Deferred-queue Tools page (fallback)

> Build only if §5.4 measurement says the per-link modal is too noisy.

A standalone Tools page that lists draft Actresses recently linked to an
existing Actress *without* an alias being captured for the draft's kanji
or romaji. Grouped by linked Actress; the user works through them in
batches at their own pace.

Implementation defers to that decision point. Sketch:

- New table `pending_alias_captures (draft_actress_id, linked_actress_id,
  kanji_text, romaji_text, created_at)` populated on every "Skip" in §5.3
  (and *only* when §6 is built — until then "Skip" does nothing).
- `GET /api/curation/pending-alias-captures` lists pending rows grouped by
  `linked_actress_id`.
- `POST /api/curation/pending-alias-captures/:id/resolve` with
  `{action: "add_kanji" | "add_romaji" | "add_both" | "drop"}` applies the
  alias write and removes the row.
- Tools page UI: list of groups, each with the proposed aliases and the
  same four buttons as the modal.

The schema change is the only commitment 6d makes to enabling §6 cheaply
later — the column shape lets us start populating on §5's Skip without a
migration when §6 lands.

**Open question (defer to §6 implementation):** whether to populate the
`pending_alias_captures` row on Skip even before §6 ships, or whether to
gate the write on a runtime flag. Probably: ship §5 with Skip = pure
no-op; add the populate when §6 is built. Avoids accumulating rows the UI
can't surface.

---

## 7. Open questions

The questions left unresolved by the §11 sketch:

| Question | Decision |
|---|---|
| Polling cadence vs websocket | Polling, 5 s interval, capped at 30 polls per slot. WebSocket revisit only if observed user-perceived latency complaints. |
| Badge visual design | Defer to implementation. Italic tag or faded badge; remove on first edit. |
| Alias modal: per-link or batched at draft-save | Per-link, gated on actual mismatch (§5.1). Deferred-queue Tools page (§6) is the escape hatch if observed mismatch rates make per-link too noisy. |
| Suppress auto-fill when user has started typing | Yes (§4.2). Per-slot dirty flag tracked from `input` events. |

---

## 8. Independence and ordering

Slices, in shipping order:

1. **Slice A — Status endpoint verification** (§3). The endpoint already
   exists from 6b in `CurationRoutes.java`. Verify contract + add test
   coverage if missing. Likely a no-op or small adjustment.
2. **Slice B — Polling + auto-fill + badge** (§4). Depends on A. After
   this ships, draft Actresses with kanji-only stage names auto-fill in
   the editor. The "review surface" the dashboard counter implies
   effectively exists at this point — implicit via Actress-accept.
3. **Slice C — Alias-capture modal** (§5). Depends on B (and on the
   existing aliases route). Independently testable; if modal feels wrong
   in trial, can be reverted without affecting A or B.
4. **Slice D — Deferred-queue Tools page** (§6). Conditional on §5.4
   measurement. Schema change + new routes + new Tools tab.

Slices A and B are the load-bearing ones. C is the user-experience
improvement that addresses the Sarasa Hara unsolvable-case. D is a
contingency.

---

## 9. Tests

### Backend

- `CurationRoutesTest.stageNameStatus_curatedHit_returnsReady`
- `CurationRoutesTest.stageNameStatus_suggestionHit_returnsReady`
- `CurationRoutesTest.stageNameStatus_pendingQueue_returnsQueued`
- `CurationRoutesTest.stageNameStatus_noState_returnsMissing`

### Frontend

Playwright pins (the editor is too dynamic for unit-style coverage):

- Open editor with an unmatched kanji draft, mock the queue → status
  endpoint sequence, assert badge appears then auto-fill fires.
- Open editor, type into the first/last fields before status flips to
  `ready`, assert auto-fill does not overwrite.
- Click "link to existing" on a slot whose linked Actress doesn't cover
  the kanji + romaji, assert modal opens with both rows.
- Click "link to existing" on a slot whose linked Actress *does* cover
  the kanji, assert modal does not open.
- Each modal button (`Add both` / `Add kanji only` / `Add romaji only` /
  `Skip`) writes the expected aliases (or doesn't) and dismisses.

---

## 10. Risk

The spec acknowledges 6d is the most UX-heavy of the 6 phases and the
most likely to require revision. Mitigations:

- Slice A and B carry the load-bearing value (auto-fill = the implicit
  review). C and D are improvements layered on top.
- §5.4 has an explicit measurement-based escape valve to §6 if per-link
  modals turn out wrong.
- All slices are individually revertable without regressing the
  predecessors.

The thing that *can't* be undone cheaply is the schema change for §6
(`pending_alias_captures` table). But that's only built if §5.4 measures
demand, and at that point the design is informed by real data.
