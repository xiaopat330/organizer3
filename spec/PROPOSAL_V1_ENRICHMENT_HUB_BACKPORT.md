# PROPOSAL — Backport AI Assist + Workflow to the v1 UI ("Enrichment" hub)

**Status:** PLAN (no implementation started)
**Date:** 2026-05-28
**Owner:** (TBD)

## 1. Goal

The v2 **AI Assist** and **Workflow** screens are excellent, but v1 users have no
access to them and v2 is still beta. Backport both into the legacy v1 UI with:

1. **Functional parity** with the v2 originals.
2. **v1 design language** — styling, components, and interaction idioms must match
   the v1 Tools section, not look like v2 transplanted.

## 2. Decisions (locked)

- **Placement:** a new **top-level "Enrichment" Tools button** (peer to Health,
  Sources, Curation, …) opening a section-tabs screen with **three subtabs:
  AI Assist · Workflow · Review**.
- **Existing v1 Enrichment Review** *coexists* — it is re-homed as the **Review**
  subtab inside the hub. Its module/logic is **not modified** (LEGACY.md: read-only
  by default).
- **Backend: zero changes.** Every endpoint both v2 screens use is already mounted
  and UI-agnostic (returns plain JSON). The v1 Enrichment Review already consumes
  the same `/api/utilities/enrichment-review/*` family. This is a pure frontend job.
- **No command palette, no status bar** in v1 — those v2 chrome features are dropped,
  not ported.

## 3. Key findings from the surface audit

### 3.1 Backend is fully shared & UI-agnostic (no work)
Endpoints consumed by the two screens, all already live:

- **AI Assist** (`AiAssistDashboardRoutes`): `GET /api/enrichment/assist/dashboard`,
  `/queue-preview`, `/recent?since=`, `/sweeper`, `/batch-progress`,
  `POST /sweeper/start`, `/sweeper/stop`, `POST /apply-agreed`,
  `GET /apply-agreed/status`.
- **Workflow** (`WorkflowRoutes`): `GET /api/enrichment/workflow/rows?limit=`,
  `POST /{id}/ai-assist`, `POST /ai-assist-all`, `GET /ai-assist-status`.
- **Shared review/resolution** (`UtilitiesRoutes`): `GET /api/utilities/enrichment-review/queue`,
  `POST /{id}/resolve|pick|refresh|force-enrich|confirm-orphan-delete`,
  `POST /api/utilities/title/{id}/recode`, `POST /api/triage/cast-anomaly/{id}/add-alias`.

All return the same DTO shape regardless of caller; only the **presentation**
(table vs modal, v2 vs v1) differs.

### 3.2 v1 integration points
- **Nav:** add a tool button + view per the v1 pattern in `modules/action.js`
  (DOM refs ~ll.22–56; click wiring ~ll.427–458; `hideAllToolViews()`), a view
  container in `index.html`, and CSS. Section-tabs use the existing
  `.tools-section-header` / `.tools-section-tab` classes.
- **Lifecycle:** v1 tools export `showXView()` / `hideXView()`; lazy-load on show;
  **stop polling on hide** (both screens poll).
- **Title-detail navigation:** v1 has **no** `/v2-title-detail.html` page. Titles open
  in-app via `openTitleDetail(titleData)` (dynamic import from `title-detail.js`,
  the pattern `search.js` uses). Every code-link in the new screens must fetch the
  title by code, then call `openTitleDetail`. The AI Assist "pending apply" link
  (v2 → `/v2-workflow.html?focus=`) becomes an in-hub switch to the **Workflow**
  subtab with the same focus/scroll-highlight behavior.

### 3.3 Design-token map (one real gap)
v1 `base.css` already defines: `--accent`, `--accent-fg`, `--ok`, `--warn`,
`--error`, `--text`, `--text-dim`, `--text-faint`, `--bg-panel`, `--font-mono`,
`--radius`, `--radius-card`. **Only gap:** `--surface-2` (v2-only) → map to
`--bg-panel` (or `--bg-hover`). Some workflow.css colors are hardcoded hex
(judge orange `#f59e0b`, state green/amber tints) — keep or re-tint to taste; not
blocking.

### 3.4 Honest scope framing
The v2 modules **do not separate logic from presentation** — `ai-assist.js` embeds
332 lines of inline CSS; every workflow module renders HTML inline with hardcoded
`.wf-*` / `.aia-*` classes and inline SVGs. The real operation is:
**fork the modules → rename classes to v1 prefixes → swap tokens → rewire deep-links
→ drop v2 chrome assumptions → move CSS into v1 stylesheet files.** This is
mechanical but voluminous, especially for Workflow.

### 3.5 Effort calibration
Not hours. Realistic: **AI Assist ≈ 1 day; Workflow ≈ 3–5 days** (9 modules, 6 inline
reason panels, two-robot judge viz, override-slug input, recode dry-run preview,
two-click destructive confirms, reason-gated ⋮ menu, lightbox); **QA/polish ≈ 1 day.**

## 4. File plan (new, v1-namespaced to avoid clashing with v2 assets)

- `modules/utilities-enrichment-hub.js` — hub shell: section-tabs + show/hide of the
  three subtab views; routes AI Assist's "pending apply" → Workflow tab focus.
- `modules/utilities-ai-assist.js` — single-file dashboard (forked from v2 `ai-assist.js`).
- `modules/utilities-workflow/` — multi-file dir mirroring v2's 9 siblings
  (`index, row, actions, cast-anomaly, orphan, recode, slug-conflict,
  stage-name-conflict, utils`), forked + reskinned.
- `css/enrichment-hub.css` (+ `css/ai-assist-tools.css`, `css/workflow-tools.css`
  or one combined file). **New class prefixes** (`.ehub-`, `.aia1-`, `.wf1-`) so v1
  rules never collide with the v2 `.aia-`/`.wf-` files in the shared `/css/` dir.
- **Re-home** existing `utilities-enrichment-review.js` view as the Review subtab
  (no logic change).
- Edits to `index.html` (button + view container + CSS links) and `action.js`
  (nav wiring).

## 5. Phased plan

### Phase 0 — Foundation + thin prototype slice (de-risk)
- Build the **Enrichment hub shell**: new Tools button, view container, section-tabs
  (AI Assist · Workflow · Review), and re-home the existing Review view as the third
  tab (verifies the hub plumbing with a known-good screen).
- **Prototype slice:** skin **only** the AI Assist top stat-card row (Queue / Processed
  meter / outcome donut) in v1 idiom. Validates the token map, design-language fit,
  and effort calibration in ~1 hour.
- **Exit criteria:** hub navigable; Review works inside it; stat-card row looks native
  to v1. Surprises here change the plan cheaply.

### Phase 1 — AI Assist (full)
- Port the full dashboard: queue preview, recently-processed activity feed (with
  `since=` HWM polling, pause/clear), sweeper on/off toggle, **Apply all agreed**
  (202 + `/status` progress polling), batch-progress pass pills, KPIs.
- Rewire code-links → `openTitleDetail(byCode)`; "pending apply" → Workflow subtab focus.
- Stop all polling on hide.
- **Exit criteria:** functional + visual parity with v2 AI Assist, v1-styled.

### Phase 2 — Workflow (core, vertical slices)
- **2a — Table & basics:** rows from `/workflow/rows` with state derivation
  (queued/fetching/ambiguous/judging/split_decision/partial_vote/no_verdict/
  other_intervention), code pill, actress chips, cover thumb, candidate thumbs + Pick
  buttons, the **two-robot judge visualization**, header KPIs, **Queue all ambiguous**,
  **Apply all agreed**, 3s polling, `?focus`/deep-link scroll-highlight.
- **2b — Inline reason panels:** cast_anomaly (add-alias), orphan (mark moved / two-click
  confirm delete), recode (dry-run preview → commit), slug_conflict (claimant/incumbent
  info), stage_name_conflict (info), actress_rename (dismiss).
- **2c — Overflow ⋮ menu + extras:** reason-gated actions (mark resolved / accept gap /
  override-slug input / dismiss / refresh) + lightbox for covers/candidates.
- **Exit criteria:** all 9 reasons & states behave as in v2, v1-styled.

### Phase 3 — Parity QA + polish
- Run the 12-item Workflow smoke-test (see v2 memory checklist) + the AI Assist feature
  list, v1 vs v2 side-by-side.
- Design-language review against v1 (buttons, tables, chips, pills, empty states).
- Polling-cleanup verification on tab/hub switches (no leaked intervals).
- Playwright safety-net coverage (repo convention for JS surfaces).

### Phase 4 — Integration & cleanup
- Docs: note the hub in USAGE/spec; update memory.
- Optional: surface assist-queue depth somewhere lightweight in v1 (no status bar).
- Confirm existing Review untouched and reachable.

## 6. Risks / watch-items
- **Volume, not novelty** — Workflow's 9 modules are the bulk; budget accordingly.
- **Judge viz + per-candidate coloring** is the most intricate visual port.
- **Class-collision hygiene** — keep new v1 prefixes distinct from v2 `.wf-`/`.aia-`.
- **Concurrent v1+v2 use** — sweeper toggle / apply-all-agreed mutate global state;
  backend is the single source of truth and both poll, so no client-side optimism that
  could desync. (Non-blocking.)
- **Static-asset gotcha** — CSS/JS changes need `installDist` **then app restart**, and a
  browser hard-reload; running `installDist` against the live app corrupts its jar view.

## 7. Non-goals
- No backend changes. No command palette / status bar. No modification of the existing
  v1 Enrichment Review logic. No touching v2 assets.
