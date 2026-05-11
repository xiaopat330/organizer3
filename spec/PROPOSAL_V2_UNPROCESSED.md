# PROPOSAL: v2 Unprocessed Workbench

**Surface:** Curation → Unprocessed (currently misnamed "Title Editor" in codebase)
**Branch target:** `feature/v2-unprocessed` off `main`
**Status:** Draft — awaiting wave dispatch

---

## 1. Current-State Audit

### 1.1 File purposes

**`modules/title-editor.js` — router + queue owner**
Renders the sidebar queue (`queue-list`) and the static shell (header, duplicate banner,
cover panel, actress section, descriptor, tags panel, actions row). Owns all module state
(`queueRows`, `currentId`, `editorState`, `draftedTitleIds`). On navigation, fetches
`GET /api/unsorted/titles/:id` and `GET /api/drafts/:id` in parallel: if a draft exists
(HTTP 200) it delegates the right pane to `title-editor-draft.js` (hiding the legacy pane);
if not (HTTP 404) it calls `mountNoDraftView` from `title-editor-nodraft.js` to wire the
Enrich button, then renders inline. Also owns the Bulk Enrich sidebar button, preview modal,
and task-center SSE wiring.

**`modules/title-editor-draft.js` — draft-mode pane**
Receives draft data via `mountDraftView(...)` and renders the enriched metadata block
(title in source language, release date, maker, series, rating + grade), the scratch cover
preview with Refetch/Clear actions, the cast-slot list (each slot shows its javdb stage name,
resolution badge, and either a resolved summary + Unlink button, or a picker sub-component),
and a tags panel against `_directTags`. Action buttons: Validate, Promote, Discard, Skip.
Owns the stage-name translation polling loop (30 rounds × 5 s against
`/api/translation/stage-name-status`) and the alias-capture check on pick. Fires
`/api/curation/editor-session-open` telemetry on mount.

**`modules/title-editor-nodraft.js` — no-draft pane**
A thin module. Wires the "Enrich (draft)" button to `POST /api/drafts/:id/populate` with
an elapsed-time counter. On 201/409 success, calls back into the router to reload the detail
(which will then route to draft pane).

### 1.2 User flow

The user opens Curation → Unprocessed. The sidebar loads all eligible titles
(`GET /api/unsorted/titles`), shows a status marker (●=complete / ◐=partial / ○=empty)
and a DRAFT pill for drafted rows. Clicking a row loads it into the right pane.

**No-draft state:** The user sees the title code + folder, existing cover (or drop target),
actress assignment (star primary, remove, typeahead search, create-inline), descriptor input
with live folder-name preview, tag panel, and a Save button. The "Enrich (draft)" button
initiates javdb scraping; on success the pane transitions to draft state. Save
(`PUT /api/unsorted/titles/:id/actresses`) writes actresses + descriptor + intrinsic tags,
then optionally uploads a staged cover (`POST /api/unsorted/titles/:id/cover`). The
"Advance after save" checkbox (persisted in `localStorage`) controls whether the next
incomplete title loads automatically.

**Draft state:** The user sees enriched metadata, a scratch cover (fetched from javdb),
and the cast-slot picker per javdb stage name. Each slot cycles through: unresolved (pick
existing actress, create new via inline form, Skip if multi-actress, assign Sentinel) →
resolved (linked/created/skipped/sentinel). The user toggles intrinsic tags, then Validates
and Promotes (or Discards back to no-draft state). Promote applies all cast resolutions,
creates/links actress records, writes the scratch cover, renames the folder, and removes the
draft.

**Duplicate-mode modifier:** Applies when `detail.duplicate === true`. Cover panel and
actress typeahead lock. Only descriptor save/folder rename proceeds. A banner lists the
other locations; "View duplicate" opens title detail.

### 1.3 Field inventory

| Field | API endpoint (save) | Draft behavior | Validation |
|---|---|---|---|
| Actress assignment | `PUT /api/unsorted/titles/:id/actresses` — `{actresses, primary, descriptor, tags}` | Cast picker resolves per javdb slot; no direct actress assignment | ≥1 actress, ≥1 primary (non-dup); blocked in dup mode |
| Descriptor | Same endpoint, `descriptor` field | N/A — not in draft tables | Regex `/^[A-Za-z0-9 _@#=+,;]*$/`; blank allowed |
| Intrinsic tags | Same endpoint, `tags` field; in draft mode: same endpoint with just `{tags}` | Editable in both states; direct tags only (implied tags are read-only) | No validation |
| Cover (upload) | `POST /api/unsorted/titles/:id/cover` multipart | `GET /api/drafts/:id/cover`; refetch: `POST /api/drafts/:id/cover/refetch`; clear: `DELETE /api/drafts/:id/cover` | JPEG/PNG/WebP/GIF; ≤max bytes; blocked in dup mode |
| Cover (URL) | `POST /api/unsorted/titles/:id/cover` JSON `{url}` | (Refetch handles re-fetching from stored enrichment URL) | Valid HTTP URL |
| Cast slot resolution | `PATCH /api/drafts/:id` — `{expectedUpdatedAt, castResolutions[], newActresses[]}` | Draft-only; resolution values: `pick`, `create_new`, `skip`, `sentinel:N` | `javdbSlug` + `resolution` required; last name required for create_new |

### 1.4 Endpoint inventory

```
GET    /api/unsorted/titles                     → [{titleId, code, folderName, actressCount, hasCover, complete}]
GET    /api/unsorted/titles/:id                 → {detail, descriptor, hasCover, coverFilename, duplicate, otherLocations, directTags, labelImpliedTags, enrichmentImpliedTags}
PUT    /api/unsorted/titles/:id/actresses       → {actressIds, primaryActressId, folderRenamed, folderPath?}
         body (normal): {actresses:[{id?|newName}], primary:{id?|newName}, descriptor?, tags?}
         body (dup):    {descriptor?}
         body (tags only): {tags:[...]}  ← used by draft pane intrinsic-tag save
POST   /api/unsorted/titles/:id/cover          multipart or JSON {url}
GET    /api/unsorted/actresses/search?q=&limit= → [{id, canonicalName, stageName, matchedAlias, coverUrl, titleCount, tier, isSentinel}]

GET    /api/drafts                              → [{titleId, code, updatedAt}]
POST   /api/drafts/:id/populate                → 201/{draftTitleId} | 409 | 404 | 422 | 502
GET    /api/drafts/:id                         → full draft aggregate (see DraftRoutes javadoc)
PATCH  /api/drafts/:id                         → {updatedAt} | 404 | 409 | 400
DELETE /api/drafts/:id                         → 204 | 404
GET    /api/drafts/:id/cover                   → image/jpeg
POST   /api/drafts/:id/cover/refetch           → 200 | 422 | 502
DELETE /api/drafts/:id/cover                   → 204
POST   /api/drafts/:id/validate                → {ok, errors[]}
POST   /api/drafts/:id/promote                 → {titleId} | 404 | 409 | 422 | 500
POST   /api/drafts/bulk-enrich/preview         body:{titleIds[]} → {eligibleCount, alreadyDrafted, alreadyCurated, eligibleIds[]}

GET    /api/tags                               → [{category, label, tags:[{name,description}]}]
GET    /api/actresses?sentinel=true&limit=     → sentinel actress list
GET    /api/actresses/:id                      → actress detail (for alias-capture check)
GET    /api/titles?code=&limit=                → title lookup (for duplicate "View" link)
GET    /api/translation/stage-name-status?kanji= → {status:'ready'|'queued'|'missing', romaji?}
POST   /api/curation/editor-session-open       body:{titleId}   → 204 (telemetry)
POST   /api/utilities/tasks/enrichment.bulk_enrich_to_draft/run → {runId}
GET    /api/utilities/runs/:runId/events       SSE stream
```

---

## 2. v2 Surface Decision

**Page location:** `/v2-unprocessed.html`. Rail entry under the **Cleanup** section, first item (above Duplicates), with a queue-like icon. Rail badge showing pending count.

**Layout:** Master/detail — queue list left (fixed ~300px), editor right. Mirrors the Duplicates workbench pattern. The workbench mode spec (DESIGN_SYSTEM.md §4.2) prescribes persistent selection + inspector; for this surface the "inspector" IS the right-pane editor, always open.

The editor pane is **single unified UI** — not two separate panes routed by draft state. Sections adapt:
- **Header:** code (copyable) + folder + status badge (DRAFT or DUPLICATE) + primary action cluster.
- **Cover section:** always present; shows either legacy drop-target + file upload or draft scratch cover with Refetch/Clear.
- **Metadata section:** hidden in no-draft; shows enriched title/date/maker/series/rating in draft.
- **Upstream-changed banner:** visible only when `draft.upstreamChanged && !dismissed`.
- **Cast section:** hidden in no-draft (actress assignment via typeahead instead); shows cast slots in draft.
- **Actress assignment:** visible in no-draft only (typeahead + primary star + remove).
- **Descriptor row:** visible in both states (non-dup only).
- **Tags panel:** always present; exact same tri-state chip pattern.
- **Action row:** context-sensitive buttons (Save/Skip/Advance in no-draft; Validate/Promote/Discard/Skip in draft; Enrich-draft button when no-draft and not dup).

**No modals.** Inline panels only. Alias-Capture and Near-Miss are external modal modules — reuse them unchanged.

---

## 3. Functional Scope

All features listed below reach parity. Nothing deferred.

**Fields and actions:**
- Queue list: status markers (●/◐/○), DRAFT pill, "Show complete" toggle, bulk-enrich button.
- Code copy-to-clipboard click handler.
- Actress typeahead (search + create-inline + already-added guard + keyboard nav + match highlighting). Duplicate-mode: locked.
- Actress primary-star toggle and remove (≥1 guard).
- Descriptor input with live folder-name preview and regex validation.
- Cover: drag-drop, clipboard paste (image + URL), URL-stage, replace-confirm guard. Duplicate-mode: locked.
- Tag panel: tri-state (direct / label-implied=red / enrichment-implied=red), per-category color palette.
- Save flow: actress + descriptor + tags PUT, optional cover POST, advance-after-save (localStorage).
- Duplicate-mode: banner with other-locations list, "View" button → `openTitleDetail`.
- Enrich button with elapsed-time counter, transitions to draft on success.
- Draft metadata block (title, release date, maker, series, rating + grade badge).
- Scratch cover preview (Refetch, Clear). Upstream-changed banner (Discard / Dismiss).
- Cast slots: stage name, javdb slug, resolution badge, linked-actress avatar for pick.
- Picker: search existing (typeahead), create-new inline form (last/first), Skip (≥2 slots), Sentinel dropdown (0 or ≥2 slots).
- Stage-name translation polling (30×5 s), auto-fill, dirty-slot guard, autofill-cue.
- `PATCH /api/drafts/:id` with optimistic-lock token; 409 → reload.
- Near-Miss modal (`?` badge on unresolved slots) — mount `near-miss-modal.js` unchanged.
- Alias-capture check after pick — call `openAliasCaptureModal` unchanged.
- Intrinsic tags in draft mode: save via `PUT /api/unsorted/titles/:id/actresses {tags}`.
- Validate, Promote (with pre-flight), Discard (confirm), Skip.
- `editor-session-open` telemetry POST on draft mount.
- Bulk Enrich: sidebar button (count of visible incomplete), preview modal (exclusion breakdown), confirm → task run, task-center SSE.

**UX improvements aligned with v2 design system:**
- KPI strip above the editor empty state: "N pending · M with drafts · K complete."
- Status pills using v2 `.pill`/`.badge` primitives and `humanizeEnumLabel` from `v2/enrichment/utils.js` for resolution labels.
- `--dis-empty` state message when queue is empty.
- Action buttons use v2 `.btn` primitive family (`.btn-primary`, `.btn-danger`, `.btn-secondary`, `.btn-sm`).
- Tag chips use v2 `.chip`/`.chip-active` primitives; category palette preserved via data attributes.
- Status messages as inline dismissible banners, not floating overlays.

---

## 4. Architecture

### 4.1 Module structure

```
modules/v2/unprocessed/
  index.js       mount entry — creates state, wires sidebar + editor, loadQueue
  state.js       createState() factory — shared mutable object
  queue.js       renderQueue(), statusMarker(), nextIdAfter(), bulk-enrich logic
  editor.js      renderEditor() dispatcher — adapts sections by draft/duplicate/empty
  cover-pane.js  cover drop-target, paste, URL-stage, preview; draft scratch cover
  cast-pane.js   renderCastSlots(), buildPicker(), patchResolution(), polling loop
  actress-pane.js typeahead search, add/remove/primary, renderActresses()
  tags-pane.js   renderTagPanel() tri-state; saveIntrinsicTags()
  draft.js       mountDraft()/unmountDraft() state transition; validate/promote/discard
```

HTML page: `v2-unprocessed.html` — standard chrome shell (rail with Unprocessed active
under Cleanup, topbar, status bar), single `<div id="unprocessed-root">` as mount target.

### 4.2 State shape

```js
createState() → {
  // Queue
  queueRows:          [],         // [{titleId, code, folderName, actressCount, hasCover, complete}]
  draftedTitleIds:    new Set(),
  showComplete:       false,
  currentId:          null,
  // Editor
  detail:             null,       // GET /api/unsorted/titles/:id response
  draft:              null,       // GET /api/drafts/:id response (or null)
  isDraftMode:        false,
  // No-draft editor sub-state
  editorState:        null,       // {actresses, descriptor, directTags, labelImpliedTags, enrichmentImpliedTags, coverStaged, coverDirty, hasExistingCover, initial*}
  // Tags
  tagsCatalog:        null,
  // Stage-name polling
  pollTimers:         new Map(),  // javdbSlug → timeoutId
  dirtySlots:         new Set(),
  suppressInput:      new Set(),
  // Sentinels cache
  sentinelsCache:     null,
  // Bulk enrich
  bulkPlan:           null,
}
```

### 4.3 Primitive reuse

- **`v2/title-tag-editor.js`** — NOT reused. It operates on sorted titles via `/api/titles/{code}/tag-state` + `/api/titles/{code}/tags` and is a modal. Unprocessed needs an inline panel against different endpoints. Share tag-chip CSS primitives only.
- **`near-miss-modal.js`** — imported unchanged from modules root (permitted per LEGACY.md).
- **`alias-capture-modal.js`** — imported unchanged.
- **`task-center.js`** — imported for bulk-enrich SSE.
- **`title-detail.js`** — `openTitleDetail` imported for duplicate "View" link.
- **`utils.js`** — `esc` imported as usual.

### 4.4 CSS strategy

Do NOT port `title-editor.css` (~1000 LOC). Rebuild against design-system tokens
(`base.css`, `primitives.css`, `workbench.css`). Add a small `unprocessed.css` only for:
- Cover panel aspect-ratio + drag/drop states.
- Cast-slot card layout + resolution badge palette.
- Stage-name translation badges (`.sn-translating-badge`, `.sn-autofill-cue`).
- Descriptor preview color.
- Duplicate-mode lock overlay.

Tag chip per-category color palette adapts to v2 `--accent`/`--warn`/`--ok`/`--error`
tokens; any category-specific colors that deviate add a CSS custom property rather than a
new token.

---

## 5. Wave Plan

### Wave 1 — Page chrome + queue list (read-only) `M`
Deliverable: `v2-unprocessed.html` with full rail/chrome, `unprocessed/index.js` and
`queue.js`, sidebar fully functional (load queue, render rows with status markers, DRAFT
pills, "Show complete" toggle, empty state). Bulk-enrich button renders and shows count;
modal + task wiring deferred. Editor area shows empty-state message only.

Dependencies: none (backend endpoints already exist).

### Wave 2 — No-draft editor + duplicate mode `L`
Deliverable: `editor.js`, `actress-pane.js`, `cover-pane.js`, `tags-pane.js`. No-draft
pane fully operational: actress typeahead (search, create-inline, primary-star, remove,
duplicate-mode lock), descriptor with preview + validation, cover (drop/paste/URL, replace
guard, duplicate-mode lock), tag tri-state panel, Save flow (PUT actresses + POST cover),
advance-after-save localStorage preference. Duplicate banner + "View" link. Code copy-to-
clipboard. Enrich button with elapsed-time counter and transition hook (calls `loadDetail`
on success; draft rendering handled in Wave 3).

Dependencies: Wave 1 (queue list must exist so navigation works).

### Wave 3 — Draft metadata + scratch cover + cast scaffolding `M`
Deliverable: `draft.js`, `cast-pane.js` (render-only: slot headers, resolution badges,
resolved-slot display + Unlink button). `editor.js` detects `isDraftMode` and shows
metadata block (title/date/maker/series/rating/grade), upstream-changed banner, scratch
cover preview with Refetch + Clear. Cast slots render but picker is read-only placeholder.
Validate/Promote/Discard/Skip action buttons wired (validate endpoint, promote flow, discard
with confirm). `editor-session-open` telemetry. Intrinsic tag save in draft mode.

Dependencies: Wave 2 (editor scaffolding).

### Wave 4 — Cast picker + translation polling `M`
Deliverable: full `buildPicker()` — search typeahead, create-new inline form (last/first),
Skip button (≥2), Sentinel dropdown with lazy fetch. `patchResolution()` with optimistic-
lock token and 409-reload semantics. Stage-name translation polling loop (`startPollForSlot`,
`applyAutoFill`, dirty-slot guard). Near-Miss `?`-badge → `mountNearMissModal`. Alias-
capture check after pick → `openAliasCaptureModal`. `near-miss-resolved` window event
listener to reload draft.

Dependencies: Wave 3 (cast slot scaffolding must exist).

### Wave 5 — Bulk Enrich modal + functional-parity QA `S`
Deliverable: bulk-enrich confirm modal (preview POST, exclusion breakdown, confirm → task
run, SSE progress via `task-center.js`). KPI strip. Functional-parity manual QA pass against
the checklist in §7.

Dependencies: Wave 1 (bulk-enrich button).

---

## 6. Risks and Open Questions

**Risks:**

1. **Draft↔no-draft transition correctness.** The pane must cleanly re-render (no stale
   polling timers, no orphaned DOM listeners) when Enrich succeeds (no-draft → draft) or
   Discard completes (draft → no-draft). `unmountDraft()` must stop all polling timers and
   clear dirty-slot tracking, mirroring legacy `unmountDraftView()`.

2. **Actress typeahead Z-index in workbench layout.** The legacy pane uses `position:absolute`
   dropdowns; in a `flex` workbench layout these may clip. Verify in Wave 2.

3. **Bulk-enrich task SSE teardown.** If the user navigates away mid-run, the `EventSource`
   must be closed. The legacy code does this on `run.ended` but not on unmount. Add an
   unmount cleanup in `index.js`.

4. **Tag chip category palette.** The per-category background colors in `title-editor.css`
   are bespoke (7 categories × custom palette). The v2 rebuild should preserve them as CSS
   custom properties, not hard-code them against design-system tokens that may change.

**Open questions for user before Wave 1:**

- **Rail section placement:** Confirm Unprocessed sits under "Cleanup" (with Duplicates,
  Merge, No-Match, Trash) rather than a new "Catalog" sub-section.
- **Rail badge:** Should the badge count pending (non-complete) titles, or total eligible?

---

## 7. Test Plan

### Existing backend tests (no new tests needed for the port)

The backend is already well-tested. Wave agents should reference these when verifying
regression-free behavior:

- `UnsortedEditorServiceTest` — actress replace, duplicate save, search.
- `JdbiUnsortedEditorRepositoryTest` — SQL predicates for eligible list.
- `DraftRoutesTest` — populate, get, patch, delete, validate, promote HTTP contracts.
- `DraftPatchServiceTest` — cast resolution + optimistic lock.
- `DraftPromotionServiceTest` — promote + pre-flight failure paths.
- `DraftPopulatorTest` — javdb scrape → draft creation.
- `BulkEnrichToDraftTaskTest` — bulk enrich task correctness.

### Manual QA checklist (functional parity gate for Wave 5)

**Queue:**
- [ ] All three status markers (●/◐/○) render correctly.
- [ ] DRAFT pill appears on rows with active drafts.
- [ ] "Show complete" toggle hides/shows completed rows.
- [ ] Bulk-enrich button count matches visible incomplete rows.

**No-draft editor:**
- [ ] Actress typeahead: search by name, alias match shown, create-new flow, already-added guard, keyboard nav (↑↓/Enter/Escape).
- [ ] Primary-star toggle; hint message shown when no primary.
- [ ] Remove blocked when only 1 actress.
- [ ] Descriptor validation: invalid chars show red border + error preview.
- [ ] Folder-name preview updates live: `{primary} - {desc} ({code})`.
- [ ] Cover: file drop, URL paste, image clipboard paste, replace-confirm guard.
- [ ] Tag tri-state: direct (toggleable), label-implied (red, disabled), enrichment-implied (red, disabled).
- [ ] Save: actress + descriptor + tags saved; cover uploaded if staged; folder renamed if primary changed; advance-after-save respected.
- [ ] Unsaved-changes guard fires on navigation away when dirty.
- [ ] Advance-after-save localStorage persists across reload.

**Duplicate mode:**
- [ ] Actress typeahead disabled; cover panel locked; descriptor save still proceeds.
- [ ] Duplicate banner shows other locations.
- [ ] "View duplicate" opens title detail modal.

**Enrich (draft) transition:**
- [ ] Button shows elapsed-time counter while running.
- [ ] 201 → pane transitions to draft state.
- [ ] 422 → error message; button re-enables.

**Draft state:**
- [ ] Metadata block: title, date, maker, series visible; rating + grade badge when present.
- [ ] Upstream-changed banner visible when flag set; Dismiss hides it; Discard delegates.
- [ ] Scratch cover: preview loads; Refetch re-fetches; Clear removes.
- [ ] Cast slots: stage name, javdb slug, resolution badge in correct color.
- [ ] Resolved slot shows linked-actress avatar + canonical name (when pick).
- [ ] Unlink returns slot to unresolved state.
- [ ] Picker: search existing, create-new (last required / first optional), Skip (only ≥2 slots), Sentinel (0 or ≥2 slots).
- [ ] Stage-name translation: "translating…" badge; auto-fill fires when ready; dirty input prevents auto-fill.
- [ ] `?` badge on unresolved slots with stage name → opens Near-Miss modal.
- [ ] Alias-capture modal opens after pick when names differ.
- [ ] 409 on PATCH → reloads draft cleanly.
- [ ] Intrinsic tags saved via PUT actresses endpoint (tags-only body).
- [ ] Validate → status line shows errors or "Ready to promote."
- [ ] Promote: validate + promote; on success queue reloads and empty state shows.
- [ ] Discard: confirm dialog; on confirm → returns to no-draft state.
- [ ] `editor-session-open` POST fires on mount (verify in Network tab).

**Bulk Enrich:**
- [ ] Preview modal shows eligible/excluded breakdown.
- [ ] Confirm starts task; task-center shows progress via SSE.
- [ ] Queue auto-refreshes after task ends.
