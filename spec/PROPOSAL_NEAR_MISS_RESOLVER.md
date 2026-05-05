# Near-Miss Resolver — Pending Kanji Curation Tool

> **Status: PROPOSED** — drafted 2026-05-05.
>
> Builds on `PROPOSAL_TRANSLATION_PHASE6B.md` (resolver, fuzzy matcher, NFKC
> discipline). 6b is a hard prerequisite — the modal opens reading the LLM
> romaji guess from `stage_name_suggestion` and the candidate picker calls
> `ActressFuzzyMatcher`. Without 6b shipped first, this tool has nothing to
> read.
>
> **This supersedes the 6d "alias-capture modal" sketch.** Per-link modal at
> manual-relink time is replaced by an explicit, unified resolver with batch
> cascade.

---

## 1. Goal

When enrichment produces a draft Actress whose kanji `stage_name` doesn't
match any existing canonical or alias, the user resolves the identity through
a single modal. One decision cascades to every other draft using the same
kanji.

**User-visible:** instead of editing each draft Actress in isolation, the
user clicks a "?" widget on any cast slot (or opens "Tools → Pending Kanji"),
sees the LLM romaji guess, picks "alias of an existing Actress" or "new
canonical Actress" with edited names, and saves. All sibling drafts using
the same kanji are updated in one transaction.

---

## 2. Out of scope

- **Identity-merge between existing canonical Actresses.** "These N catalog
  Actresses are actually the same person, fold them into one" is a different
  operation that touches filesystem layout and deserves its own Tools page.
  See §11.
- **Retroactive rewrites of `title_cast` rows on already-published titles.**
  Those rows reference `actresses.id` already — no kanji stub to fix. The
  cascade only touches `draft_actresses`.
- **Full editor-flow alias-capture beyond what this tool offers.** No "you
  just relinked draft X to Actress Y, want to add aliases?" prompt — the user
  comes through this tool when they want alias capture.

---

## 3. Two entry points, one modal

### 3.1 Editor inline widget

Per cast slot in the title editor (`title-editor-draft.js`), an unresolved
kanji name renders a small "?" badge. Clicking opens the modal pre-loaded
with this draft's kanji + suggestion state.

**Placement:** next to the stage-name / actress-picker input row for each
cast slot. One badge per slot — a title with N cast members has up to N
badges, one per unresolved entry. The badge is **suppressed when the
sentinel ("not an actress") option is selected** for that slot — sentinel
slots have no identity to resolve.

A draft cast slot is "unresolved" when:
- `draft_actresses.link_to_existing_id IS NULL`
- AND `draft_actresses.link_to_draft_slug IS NULL` (see §6 for the new column)
- AND `draft_actresses.english_last_name IS NULL`

Last name is the required field (first name is optional — handles mononyms);
absence of last name is the "unfilled" signal in this schema.

The "?" is intentionally subtle — a yellow circle, not a red error — because
unresolved is the normal state for an unfamiliar actress, not a bug.

### 3.2 "Tools → Pending Kanji" page

Aggregate view: every distinct unresolved `draft_actresses.stage_name` across
all drafts, with:
- the kanji form
- LLM romaji guess (or "translating…" if `stage_name_suggestion` is empty)
- count of drafts using this kanji ("appears in 7 drafts")
- oldest-seen date (the earliest `created_at` among the drafts)
- Resolve button

Default sort: count desc (most-frequent kanji first — clearing those
maximizes cascade leverage).

Same modal opens from either entry. State after save is identical.

---

## 4. The modal

### 4.1 Layout

```
┌─ Resolve kanji: 夏目彩春 ────────────────────────┐
│                                                  │
│ Translation: Natsume Iroha   (read-only)         │
│                                                  │
│ ┌─ English name ────────────────────────────┐   │
│ │ First: [Natsume]   Last: [Iroha]          │   │
│ └────────────────────────────────────────────┘   │
│                                                  │
│ ○ Alias of existing Actress                      │
│   ┌───────────────────────────────────────┐      │
│   │ Search: [_______________________]     │      │
│   │                                       │      │
│   │ Suggestions:                          │      │
│   │ • Sarasa Hara    [strong: alias hit]  │      │
│   │ • Iroha Tanaka   [weak: last-name]    │      │
│   └───────────────────────────────────────┘      │
│                                                  │
│ ○ New canonical Actress                          │
│   (uses the English name above; no folder        │
│    is created until the draft is published)      │
│                                                  │
│ This will update 7 drafts using kanji 夏目彩春.    │
│                                                  │
│           [ Cancel ]   [ Save & Cascade ]        │
└──────────────────────────────────────────────────┘
```

### 4.2 States

- **Translating…** — `stage_name_suggestion` for this kanji is empty (LLM
  in flight). All form fields **disabled**, "Save" disabled, header reads
  "Translating…". Modal polls `/api/translation/stage-name-status?kanji=…`
  every 2 s. Once `status: "ready"` lands, fields enable and pre-fill.
  **If the status endpoint returns `"missing"` on first poll** (no
  suggestion row, no queue row — should be rare; would only happen if a
  draft was inserted via a path that bypassed `DraftPopulator`), the modal
  calls `resolveOrSuggestStageName(kanji)` once to enqueue, then continues
  polling. Without this guard the modal would sit in Translating… forever.
- **Ready** — the normal active state shown above.

Names are short — translation typically completes in <5 s — so disabling is
preferable to a half-active modal where pre-fill races against typing.

### 4.3 Suggestion picker confidence labels

Candidates come from a new sibling method `ActressFuzzyMatcher.findCandidates(romaji)`
that returns **every** rule hit with its rule label, distinct from 6b's
`match(romaji)` which returns at-most-one for auto-link. The picker wants
to show weak/ambiguous hits and let the user disambiguate; auto-link skips
on the same ambiguity. Both surface modes share the same underlying repo
queries.

| Match rule | Label |
|---|---|
| Exact `resolveByName` (canonical or alias) hit | `strong: exact` |
| Reversal hit (`Yuma Asami` → `Asami Yuma`) | `strong: reversed` |
| Punctuation-stripped hit | `strong: punct-norm` |
| Last-name-only single-token hit | `weak: last-name` |

Plus free-text search at the top — calls `ActressRepository.resolveByName`
with the user's typed string and renders any hits.

If the matcher returns >1 candidate for a weak rule, show all with the
weak label. The user picks; we don't auto-select on weak hits.

### 4.4 Outcomes

**Outcome A — Alias of existing Actress X:**

1. `INSERT OR IGNORE` into `actress_aliases` (table has
   `PRIMARY KEY (actress_id, alias_name)` so duplicates are silently
   skipped):
   - `(actress_id=X, alias_name=NORMALIZE(kanji))` — kanji form
   - `(actress_id=X, alias_name=llm_romaji)` — translator output
   - `(actress_id=X, alias_name=composeName(user_first, user_last))` — the
     user-edited form, only if it differs from `llm_romaji`
   - `composeName(first, last)` mirrors
     `DraftPromotionService.buildCanonicalName`: returns
     `"{first} {last}".trim()` when both are present, just `last` when
     `first` is blank (mononym), preserving the project's existing
     name-shape convention.
   - All forms have lookup value; the canonical English name on X is
     preserved unchanged.
2. Cascade UPDATE on `draft_actresses`:
   ```sql
   UPDATE draft_actresses
      SET link_to_existing_id  = :xId,
          english_first_name   = :userFirst,
          english_last_name    = :userLast,
          updated_at           = :now
    WHERE stage_name           = :normalizedKanji
      AND link_to_existing_id  IS NULL
      AND link_to_draft_slug   IS NULL
   ```
   `:userFirst` may be NULL (mononym); `:userLast` is the required field.

**Outcome B — New canonical Actress:**

The "primary" draft is one we designate as canonical. Selection rule:
- Editor entry point: the slug of the cast slot the user opened the modal
  from.
- Tools-page entry point: auto-pick the oldest surviving draft (lowest
  `created_at`) for this kanji. Invisible to the user — every sibling
  resolves to the same `actresses.id` post-promotion regardless of which
  one was elected primary, so the choice is a database-internal pointer.

If the primary draft is later deleted before promotion, sibling drafts'
`link_to_draft_slug` becomes dangling (the FK is `ON DELETE SET NULL`, see
§6). At promotion, sibling drafts with NULL `link_to_draft_slug` AND
non-NULL `english_last_name` are re-elected: oldest surviving sibling
becomes the new primary, others link to it via `link_to_draft_slug`. This
re-election logic lives in `DraftPromotionService` and is invisible to the
user.

1. Update the primary draft:
   ```sql
   UPDATE draft_actresses
      SET english_first_name = :userFirst,
          english_last_name  = :userLast,
          updated_at         = :now
    WHERE javdb_slug         = :primarySlug
   ```
2. Cascade siblings:
   ```sql
   UPDATE draft_actresses
      SET link_to_draft_slug = :primarySlug,
          english_first_name = :userFirst,
          english_last_name  = :userLast,
          updated_at         = :now
    WHERE stage_name         = :normalizedKanji
      AND javdb_slug         <> :primarySlug
      AND link_to_existing_id IS NULL
      AND link_to_draft_slug  IS NULL
   ```

No `actresses` row is created until promotion. No alias rows are captured
during the draft phase — at promotion time, `DraftPromotionService` rebuilds
the alias set from existing data:

- Kanji → `draft_actresses.stage_name`
- LLM romaji → `stage_name_suggestion.suggested_romaji` keyed by `kanji_form`
- User-edited romaji → `english_first_name + " " + english_last_name`

Each form distinct from `canonical_name` becomes an `actress_aliases` row on
the newly-materialized actress.

---

## 5. Cascade scope, formally

In both outcomes the WHERE clause is:

```
stage_name           = NORMALIZE(kanji)
AND link_to_existing_id IS NULL
AND link_to_draft_slug  IS NULL
```

The two NULL guards prevent re-overwriting a draft that's already been
resolved (by a prior near-miss save, manual edit, or 6b's narrowed auto
fan-out — see §12). `english_last_name` is *not* in the WHERE — the
near-miss tool's identity decision overrides any prior cosmetic English-name
fill. The cascade is **all unresolved drafts everywhere**, not scoped to a
bulk-enrich run — by user decision (c)#2.

The confirm step in the modal MUST show the count: `SELECT COUNT(*) ...`
before the user clicks Save. If the count is 0 (e.g., all sibling drafts
were resolved while the modal was open), show "no drafts to update" and
still allow the save (which will only insert aliases for outcome A or
update the primary draft for outcome B).

---

## 6. Schema changes

One new column. No new tables — alias storage piggy-backs on existing
`actress_aliases` (outcome A) or is rebuilt at promotion from existing draft
fields + `stage_name_suggestion` (outcome B); see §4.4.

```sql
ALTER TABLE draft_actresses
  ADD COLUMN link_to_draft_slug TEXT
  REFERENCES draft_actresses(javdb_slug) ON DELETE SET NULL;
```

Nullable. Set on sibling drafts of a "new canonical" group; null otherwise.
`ON DELETE SET NULL` because losing the primary draft shouldn't force-delete
its siblings — promotion's re-election logic (§4.4 outcome B) handles
recovery from a dangling pointer.

At promotion (`DraftPromotionService.insertNewActresses`), siblings are
detected by `link_to_draft_slug IS NOT NULL`, deferred until the primary
materializes, then linked via the primary's new `actresses.id`. If the
primary is missing, re-elect the oldest sibling (per §4.4).

Migration is one `applyVN()` in `SchemaUpgrader`, idempotent (SQLite doesn't
support `ADD COLUMN IF NOT EXISTS`, so check `PRAGMA table_info(draft_actresses)`
first).

---

## 7. Backend endpoints

### 7.1 Read

- `GET /api/translation/stage-name-status?kanji=…`
  - Returns `{ status: "queued"|"ready"|"missing", romaji?: string }`.
  - `queued`: a `translation_queue` row exists, `stage_name_suggestion`
    does not yet.
  - `ready`: `stage_name_suggestion` exists; `romaji` is the latest
    usable form (per 6b's `findLatestUsableSuggestion`).
  - `missing`: neither — caller should re-trigger via
    `resolveOrSuggestStageName`.
- `GET /api/curation/pending-kanji`
  - Returns `[{kanji, count, oldestSeen, suggestion: { status, romaji? }}]`
    for the Tools page.
  - Aggregation: `SELECT stage_name, COUNT(*), MIN(created_at) FROM
    draft_actresses WHERE link_to_existing_id IS NULL AND
    link_to_draft_slug IS NULL AND english_last_name IS NULL GROUP BY
    stage_name`. (Last-name guard, not first-name — mononyms are valid.)
  - Suggestion fields: left-join `stage_name_suggestion` by
    `kanji_form = stage_name`.
  - Resolved kanji (every draft using them now has a link or a last name)
    drop out of the aggregate naturally — they no longer match the WHERE.
- `GET /api/curation/fuzzy-candidates?romaji=…`
  - Returns the matcher's candidates with confidence labels for the picker.

### 7.2 Write

- `POST /api/curation/near-miss/resolve`
  - Body: `{ kanji, primarySlug?, outcome: "alias"|"canonical",
    aliasOfActressId?, englishFirst, englishLast, llmRomaji }`
  - `primarySlug` is required for outcome `canonical`; ignored for `alias`.
  - All writes happen in one transaction. Returns
    `{ updatedDrafts, insertedAliases }`.

---

## 8. Frontend modules

**New:**
- `src/main/resources/public/modules/near-miss-modal.js` — the modal
  component. Pure UI; takes `{ kanji, primarySlug? }` to mount and emits a
  resolve event. State machine: translating → ready → saving → done.
- `src/main/resources/public/modules/utilities-pending-kanji.js` — the
  Tools page (list + Resolve button → opens near-miss-modal).

**Modified:**
- `title-editor-draft.js` — render the "?" badge on unresolved cast slots,
  open the modal on click. Subscribe to a `near-miss-resolved` event so
  the slot re-renders with the new English name without a full page reload.
- `index.html` — Tools menu entry "Pending Kanji"; container div for the
  Tools page.

The modal is a single component imported by both entry points. It exposes
`mount(container, { kanji, primarySlug? })` / `unmount()`.

---

## 9. Tests

**Backend:**
- `NearMissResolveRouteTest`:
  - Outcome A (alias) writes 3 alias rows when all forms differ; 1 row when
    user-edited matches LLM; idempotent on re-save.
  - Outcome A cascades only unresolved drafts; respects both NULL guards.
  - Outcome B sets `link_to_draft_slug` on siblings; primary stays with NULL
    `link_to_draft_slug`; populates `draft_actress_aliases`.
  - Cascade count returned matches actual UPDATE row count.
  - NFKC mismatch (full-width vs half-width kanji) — assert normalization
    on both sides of the WHERE.
- `DraftPromotionServiceTest`:
  - Sibling drafts with `link_to_draft_slug` resolve to the primary's
    newly-materialized `actresses.id` after promotion, in one batch.
  - On primary promotion, `actress_aliases` rows are rebuilt from
    `stage_name` (kanji), `stage_name_suggestion.suggested_romaji`, and
    `english_first_name + " " + english_last_name` — each form distinct
    from `canonical_name`.
  - Sibling drafts without their primary in the promotion batch fail loud
    (data integrity error — should not happen in practice).
- `PendingKanjiRouteTest`: aggregation correctness on a small fixture.

**Frontend:**
- Playwright pin: open editor with a draft containing 2 unresolved kanji,
  click "?" on slot 1, save outcome A (alias of existing X), assert both
  slots' English names update and a third draft (different title, same
  kanji) also reflects the link.
- Playwright pin: Tools → Pending Kanji shows the count, sorted desc;
  resolve clears that row and updates the count.

---

## 10. Files touched (estimate)

**New:**
- `spec/PROPOSAL_NEAR_MISS_RESOLVER.md` (this file)
- `src/main/java/com/organizer3/curation/NearMissResolveService.java`
- `src/main/java/com/organizer3/web/routes/CurationRoutes.java` (or extend
  existing curation routes)
- `src/main/resources/public/modules/near-miss-modal.js`
- `src/main/resources/public/modules/utilities-pending-kanji.js`
- Tests for each.

**Modified:**
- `src/main/java/com/organizer3/db/SchemaUpgrader.java` — new applyVN()
- `src/main/java/com/organizer3/db/SchemaInitializer.java` — fresh-schema
  parity (`link_to_draft_slug` column)
- `src/main/java/com/organizer3/javdb/draft/DraftPromotionService.java` —
  sibling resolution via `link_to_draft_slug`; alias rebuild from
  `stage_name` + `stage_name_suggestion` + draft English fields
- `src/main/java/com/organizer3/repository/DraftActressRepository.java` +
  jdbi impl — new accessors
- `src/main/resources/public/modules/title-editor-draft.js` — "?" badge
- `src/main/resources/public/index.html` — Tools menu + page container
- `src/main/java/com/organizer3/Application.java` — wire service + routes

---

## 11. Open questions / known fragility

1. **What if the user picks a primary slug for outcome B but the kanji also
   appears on a sibling that's in a different bulk-enrich state (e.g.,
   already promoted)?** The cascade WHERE excludes promoted drafts (they're
   no longer in `draft_actresses`), but the user might *expect* the just-
   resolved alias to also retroactively re-link the promoted Actress's
   titles. It won't. Surface in confirm-step text: "N drafts updated; M
   already-published titles bear this kanji and won't be changed."
2. **Confidence-label tuning.** First batch of weak-rule hits will likely
   be too noisy or too quiet. Plan to revise label thresholds (e.g.,
   require the last-name to be ≥ 4 chars to count as "weak: last-name") in
   v2.
3. **Translation-status polling cost.** With many concurrent modals open,
   the 2 s poll multiplies. For Phase 1, single-modal-at-a-time is
   acceptable; if the Tools page later renders multiple "translating…" rows
   inline, switch to a batched
   `/api/translation/stage-name-status?kanjis[]=…` endpoint.
4. **Identity-merge for existing canonical Actresses.** Deferred. When/if
   built, it lives in a separate Tools page where filesystem-rename
   implications are explicit. The near-miss tool deliberately does not
   touch existing canonical records beyond inserting alias rows.
5. **What if the user resolves the same kanji twice with conflicting
   choices?** First resolve cascades; the second resolve sees zero
   unresolved drafts (because all already have `link_to_existing_id` or
   `link_to_draft_slug` set). The second resolve's outcome silently wins
   only on the kanji's alias rows / draft_actress_aliases, not on already-
   resolved drafts. Show "no drafts to update" in the confirm; this is
   correct behavior, not a bug.
6. **`link_to_draft_slug` introduces a foreign-key cycle within the same
   table.** SQLite handles self-FKs fine; `ON DELETE SET NULL` is set in
   the migration (§6) so a deleted primary doesn't take its siblings with
   it.

---

## 12. 6b dependency — narrow the auto fan-out

The Phase 6b spec (`PROPOSAL_TRANSLATION_PHASE6B.md` §4.1) defines a
`CallbackDispatcher` fan-out that fills `english_first_name/english_last_name`
on every matching kanji draft when the LLM completes — without making any
identity decision. With the near-miss tool in place, that broad fan-out is
**actively harmful**: a draft with English fields filled but no
`link_to_existing_id` and no `link_to_draft_slug` will silently promote as
`create_new` and build a brand-new canonical Actress *per draft*. Same
kanji across 7 drafts → 7 duplicate Actresses, each with separate folders.

**Required change to 6b before this spec ships.** Tighten the auto fan-out's
WHERE clause:

```sql
UPDATE draft_actresses
   SET english_first_name = :first,
       english_last_name  = :last,
       updated_at         = :now
 WHERE stage_name           = :kanji
   AND english_last_name    IS NULL
   AND link_to_existing_id IS NOT NULL    -- ADDED: only fill already-linked drafts
```

Cosmetic-only fill on already-linked drafts. For unlinked drafts, the
near-miss tool is the sole path to setting English fields *and* identity.
This eliminates the duplicate-Actress risk and makes the relationship
between 6b and this tool clean: 6b fills, near-miss decides.

If 6b ships first with the broad fan-out, fix it in this spec's
implementation PR.
