# Proposal: Cast Anomaly Triage — "Add as Alias" Inline Action

**Status:** Draft 2026-05-02 — design stub. Queue for after the actress-hygiene PR (`STAGE_NAME_PROMOTION` + `YAML_ALIAS_MIRROR`) lands; revisit then to size residual pain.

**Origin:** Mai Hanano session 2026-05-02. Older actresses with multiple stage-name eras (career rebrandings) surface as `cast_anomaly` rows even when the cover is obviously her — javdb returns the era-correct stage name (e.g. `黒木麻衣`) but the matcher has no alias linking it to our `actresses.id`. The Bundled hygiene PR fixes the ~73 cases where the alt is already in YAML; this proposal addresses the residual tail where the alt is genuinely new (we've never seen it before).

---

## 1. Problem

`cast_anomaly` review-queue rows mean: javdb returned a slug for the title, the cast was fetched, but `CastMatcher.match()` couldn't link any cast entry to any of the title's linked actresses.

Today's review-queue UI surfaces these rows but the inline actions don't fit the typical resolution. The user identifies the actress visually (cover match) and wants to say *"yes that's her — please remember this name as an alias."* No such one-click action exists. Workarounds:

- Manual `set_actress_aliases` MCP call (out-of-band, not discoverable in UI)
- "Override slug" — wrong action; the slug is correct, the alias is missing

After an alias is added by any means, the existing `EnrichmentRunner.recoverCastAnomaliesAfterMatcherFix()` discharges open rows automatically. The recovery mechanism is built; only the UI affordance is missing.

---

## 2. Proposed change

Extend the existing review-queue UI (`utilities-enrichment-review.js`) with one new inline action on `cast_anomaly` rows:

**"Add `<javdb name>` as alias for `<our actress canonical_name>`"** — one click, server-side:

1. Insert into `actress_aliases` (additive; no removal; same conflict-handling as YAML mirror — let `find_alias_conflicts` flag).
2. Run `recoverCastAnomaliesAfterMatcherFix()` against this single row + any sibling open rows referencing the same name.
3. UI removes resolved rows from the list, refreshes the count.

The row card already shows: cover, code, the actress link, the unmatched cast names. The new action is a button per unmatched cast entry.

Multi-actress titles: if multiple actresses are linked AND multiple cast names are unmatched, render one button per (actress, name) pair. User picks the right pairing.

---

## 3. Out of scope

- Auto-detection of "this is the same actress" via cover-hash or face recognition.
- Cross-actress alias proposals ("did you mean to link this title to a different actress?"). That's the No-Match Triage's domain.
- Rejecting a cast entry as "not actually our actress" — that's a different action (cast slot SKIP, lives in the Draft Mode editor).

---

## 4. Effort

**~1 session.**

- Backend: 1 new route `POST /api/triage/cast-anomaly/:id/add-alias` accepting `{actressId, aliasName}`. Reuses `actress_aliases` repo + the existing recovery sweep.
- Frontend: extend `utilities-enrichment-review.js` row renderer for `reason='cast_anomaly'` to render the button per unmatched cast pairing.
- Tests: route + service unit test covering happy path, conflict-flag-but-still-insert, no-op when alias already exists, recovery-sweep chained.

---

## 5. Acceptance criteria

- [ ] `cast_anomaly` row card shows an "Add as alias for <actress>" button per unmatched cast entry.
- [ ] Clicking inserts the alias, fires the recovery sweep, and visually removes the row.
- [ ] Sibling rows whose cast contains the same name auto-discharge in the same sweep.
- [ ] Conflict with another actress's canonical/alias is surfaced (toast + `find_alias_conflicts` row), but the alias is still inserted.
- [ ] Existing No-Match Triage UI is unaffected.

---

## 6. Sequencing

After:
- `STAGE_NAME_PROMOTION` + `YAML_ALIAS_MIRROR` PR (closes the YAML-known cases)
- Post-merge `backfill_yaml_aliases` run (auto-discharges Mai Hanano's ~40 rows + others)

Then re-measure: how many `cast_anomaly` rows remain in the queue after the backfill recovery sweep settles? If <20, this proposal might be worth deferring further. If still 100+, ship it.
