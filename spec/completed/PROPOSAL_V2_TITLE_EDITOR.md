# Title Editor → v2 port: STATUS AUDIT (the port is already SHIPPED)

Status: **NOT A PROPOSAL.** Investigation found the port already exists, complete, on `main`.
Author: architecture/audit pass, 2026-05-28.

> **HEADLINE FINDING.** The task asked for a spec to port the legacy Title Editor to v2. While
> investigating, I found the port is **already fully built and merged to main** as the v2
> **"Unprocessed"** surface: `src/main/resources/public/v2-unprocessed.html` +
> `modules/v2/unprocessed/` (9 sibling modules) + `css/workbench.css`. Its git history shows a
> complete **5-wave vertical-slice** delivery (`e13a2039` → `33361c10`) covering everything the
> legacy editor does, including the draft pipeline. **No new work is proposed.** This document
> records what exists, maps it to the legacy editor for parity, and lists the few residual gaps.

---

## §1 Legacy editor anatomy (the thing that was ported)

### 1.0 The three legacy files

| File | LOC | Role |
|------|-----|------|
| `modules/title-editor.js` | 982 | Shell + queue sidebar + **no-draft editor** + state router + Bulk-Enrich. |
| `modules/title-editor-draft.js` | 1089 | **Draft editor** pane: cast-slot resolver, enrichment metadata, validate/promote/discard. |
| `modules/title-editor-nodraft.js` | 82 | Tiny adapter — wires only the "Enrich (draft)" button (`POST /api/drafts/{id}/populate`). |

`nodraft.js` is **not** a parallel editor; it is an 82-line button-wirer. The actual no-draft editor UI lives inside `title-editor.js` (lines 315–861).

### 1.1 The draft/nodraft divergence — VERDICT: two editors over two data layers

This was the brief's central question ("do they merge?"). They do **not**. Evidence:

- **State router** (`title-editor.js:234-301`): `GET /api/drafts/{id}` → 200 = draft pane (`mountDraftView`), 404 = no-draft pane. Mutually-exclusive DOM regions swapped in place.
- **No-draft mutates LIVE tables immediately**: Save (`:794-861`) calls `PUT /api/unsorted/titles/{id}/actresses` (replaces cast+descriptor+tags, **renames folder on disk**) and `POST /api/unsorted/titles/{id}/cover` (writes into the live folder). Cover is staged **client-side only** until Save (`:713-729`).
- **Draft mutates STAGING rows (`draft_*`) with optimistic concurrency**: every action is server-side-immediate — `PATCH /api/drafts/{id}` with `expectedUpdatedAt` (`title-editor-draft.js:751-808`), cover in a draft scratch area (`:1032-1073`), and only `POST .../promote` (`:944-1000`) writes the draft into live tables/folder.
- **Concurrency models differ**: no-draft uses a client-side dirty guard + `confirm('Discard unsaved changes?')` (`:190,732-739`); draft has no dirty guard (`:188-190` comment) and relies on server `expectedUpdatedAt` 409s.
- **One shared seam**: intrinsic tags. The draft editor deliberately writes tags through the canonical `PUT /api/unsorted/titles/{id}/actresses` endpoint, **not** the draft tables (`title-editor-draft.js:883-897`).

**Conclusion:** two genuinely separate editors over live-vs-staging data, bridged by a shared shell and the shared tag endpoint. The v2 port respected this boundary exactly (see §3.2).

### 1.2 Field inventory

**No-draft editor:** code (RO, click-copy), folder (RO), cover (drop file/URL/paste + lightbox + replace-confirm), cast (typeahead/add/create/primary/remove; ≥1 + exactly-1-primary), descriptor (input + live preview + charset `[A-Za-z0-9 _@#=+,;]`), tags (grouped toggles; implied locked), duplicate state (banner + locked controls + descriptor-only save), Enrich button.

**Draft editor:** cast-slot resolver (search+pick / create-new / skip / sentinel; stage-name translation polling; near-miss + alias-capture modals), scratch cover (refetch/clear), read-only enrichment metadata (title-original, release, maker, series, rating+grade), tags, upstream-changed banner, Validate/Promote/Discard.

---

## §2 Backend API map (all pre-existing — confirms no backend was needed)

`src/main/java/com/organizer3/web/routes/`.

### No-draft (Unsorted) — `UnsortedEditorRoutes.java`
| Action | Method + path | Line |
|--------|---------------|------|
| Queue list | `GET /api/unsorted/titles` | `:39` |
| Load title | `GET /api/unsorted/titles/{id}` | `:41` |
| Save cast+descriptor+tags | `PUT /api/unsorted/titles/{id}/actresses` — `{actresses[],primary,descriptor,tags[]}` (dup: `{descriptor}`) | `:50` |
| Actress typeahead | `GET /api/unsorted/actresses/search?q=&limit=` | `:104` |
| Save cover (file or `{url}`) | `POST /api/unsorted/titles/{id}/cover` | `:111` |

### Shared
| Action | Method + path | Route |
|--------|---------------|-------|
| Tag catalog | `GET /api/tags` | `TitleRoutes:126` |
| Library tag-state / save / enrichment-tags | `GET/PUT /api/titles/{code}/tags*`, `GET .../enrichment-tags` | `TitleTagEditorRoutes:55,80,142` |

### Draft — `DraftRoutes.java`
`GET /api/drafts`, `GET /api/drafts/{id}`, `POST .../populate`, `PATCH /api/drafts/{id}` (`expectedUpdatedAt`), `POST .../validate`, `POST .../promote`, `DELETE /api/drafts/{id}`, `GET/DELETE .../cover`, `POST .../cover/refetch`, `POST /api/drafts/bulk-enrich/preview`; plus `GET /api/translation/stage-name-status` (`TranslationRoutes`) and `POST /api/curation/*` (near-miss / alias-capture / editor-session — `CurationRoutes`).

### Gaps (for reference) — library (sorted) titles have NO edit endpoint for cover/code/title-text/cast/label; only tags (`PUT /api/titles/{code}/tags`, already on v2). So "edit cover/cast/code/label from a v2 *library* detail page" remains impossible without new backend — but that is **not** what the editor does. The editor is unsorted-volume-scoped (`UnsortedEditorService:40,67`).

---

## §3 What is shipped on v2

### 3.1 Files (all on `main`)

```
src/main/resources/public/v2-unprocessed.html          # page entry → mountUnprocessed(#app-body)
src/main/resources/public/modules/v2/unprocessed/
  index.js        # mount entry: shell, KPI strip, Bulk-Enrich button, queue
  state.js        # state factory createState() + buildEditorState()  ← state-factory convention
  queue.js        # sidebar queue list + status markers + filter
  editor.js       # no-draft editor pane + duplicate mode + Save flow
  cover-pane.js   # cover drop/paste/URL staging + scratch-cover (draft)
  cast-pane.js    # cast picker (search/create/skip/sentinel) + stage-name translation polling
  actress-pane.js # no-draft actress typeahead/add/primary/remove
  tags-pane.js    # tag toggle grid (implied locked)
  draft.js        # draft pane: metadata, Validate/Promote/Discard, cast scaffolding
src/main/resources/public/css/workbench.css            # `.un-wb*` styling (DS-token based)
```

It is wired into the v2 rail nav (links present in `v2-actresses.html`, `v2-avstars.html`, `v2-actress-detail.html`, etc., alongside `v2-workflow.html`).

### 3.2 Architecture matches repo conventions and legacy structure

- **Sibling-module split** (`project_deferred_js_splits`): 9 files instead of one monolith. ✔
- **State factory** (`feedback_state_factory_pattern`): `state.js` `createState()` instead of module-level `let`s. ✔ (and since this was new code, no Playwright-before-refactor obligation applied).
- **Draft/no-draft boundary preserved**: `draft.js` is a separate sub-surface, mirroring the legacy `title-editor.js` ↔ `title-editor-draft.js` split. ✔
- **DS tokens**, not legacy `css/title-editor.css`: styling in `css/workbench.css` with `.un-wb*` classes. ✔ (matches the v2 tag-editor precedent of not loading legacy CSS).

### 3.3 Delivery history — 5 vertical waves (the brief's §6 ask, already done)

```
e13a2039  wave 1 — page chrome + queue list
8a5e05b7  wave 2 — no-draft editor + duplicate mode
90c3c8b4  wave 3 — draft metadata + scratch cover + cast scaffolding
07a7ce2a  wave 4 — cast picker + translation polling
33361c10  wave 5 — bulk enrich + parity QA
```

Each wave was an independently-merge-able vertical slice — exactly the structure the brief requested. Notably, the draft editor (which this audit would have recommended deferring as a moving target) was in fact ported in waves 3–4.

---

## §4 Parity check (legacy → v2)

| Capability | Legacy | v2 `unprocessed/` | Status |
|------------|--------|-------------------|--------|
| Queue sidebar + status markers + filter | `title-editor.js` | `queue.js` | ✔ |
| No-draft cover (drop/URL/paste/lightbox/replace) | `title-editor.js:667-729` | `cover-pane.js` | ✔ |
| No-draft cast typeahead + validation | `title-editor.js:517-661` | `actress-pane.js` | ✔ |
| Descriptor + live preview + charset | `title-editor.js:741-774` | `editor.js` | ✔ |
| Tag toggle grid (implied locked) | `title-editor.js:374-414` | `tags-pane.js` | ✔ |
| Save (PUT actresses + POST cover) + advance | `title-editor.js:794-861` | `editor.js` | ✔ |
| Duplicate mode | `title-editor.js:416-439` | `editor.js` | ✔ |
| Enrich → draft | `title-editor-nodraft.js` | `editor.js`/`draft.js` | ✔ |
| Draft metadata block | `title-editor-draft.js:301-321` | `draft.js` | ✔ |
| Draft cast resolver + sentinel/skip | `title-editor-draft.js:485-682` | `cast-pane.js` | ✔ |
| Stage-name translation polling | `title-editor-draft.js:100-203` | `cast-pane.js` | ✔ |
| Validate / Promote / Discard | `title-editor-draft.js:914-1022` | `draft.js` | ✔ |
| Bulk-Enrich + SSE task pill | `title-editor.js:868-982` | `index.js` (wave 5) | ✔ |

---

## §5 Residual gaps / open questions (the only actionable items)

1. **Tests.** No test file was found for the `unprocessed/` JS modules (`find src/test -iname "*unprocessed*"` → empty). Per the repo's non-negotiable testing rule (`feedback_testing`), worth confirming whether logic in `state.js`/`editor.js` (`canSave`/`isDirty`/descriptor validation) has coverage, possibly under a different filename, or whether a Playwright/JS-unit gap exists. **Recommend: verify coverage; add tests for the save-eligibility + descriptor-charset logic if absent.**
2. **Legacy retirement.** Both surfaces are live in parallel (legacy Tools→Curation→Unprocessed `action.js:237` and v2 `v2-unprocessed.html`). Mirrors the deliberate parallel-deployment decision for the Workflow/Enrichment surfaces (`project_v2_enrichment_workflow`, "Wave 3 legacy removal deferred by user"). Legacy `title-editor*.js` removal is a future housekeeping step, not part of this port.
3. **Out-of-scope surfaces (unchanged, correctly so).** v2 actress-detail edits only profile/aliases (`PUT /api/actresses/{id}/aliases`, `.../stage-name`); v2 avstars is the parallel `av_*` schema. Neither shares code with this editor; both are separate follow-on efforts if ever wanted. v2 library title-detail edits only tags (already shipped via `v2/title-tag-editor.js`).
4. **Correcting the brief's premise.** There is no general "double-click a library title row → edit cover/code/cast/label" modal in the legacy UI, and none could exist without new backend (library titles have no such endpoints — §2 Gaps). The rich editor is the **unsorted-volume** workbench, now fully on v2.

---

## Recommendation

**No build work to spec.** The Title Editor port is complete on v2. The only follow-ups are: (1) confirm/add JS test coverage for `unprocessed/`, and (2) eventually retire the legacy `title-editor*.js` files once the parallel-deployment period ends — both housekeeping, both gated on user decision.
