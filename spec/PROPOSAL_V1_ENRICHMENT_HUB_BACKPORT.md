# PROPOSAL — Backport AI Assist + Workflow to the v1 UI ("Enrichment" hub)

**Status:** IN PROGRESS — Phase 0 done (`9933bdf6`); remaining work parallelized into
Track A (AI Assist) ‖ Track B (Workflow) + serial tail. See §5.
**Date:** 2026-05-28
**Branch:** `feature/v1-enrichment-hub-backport`

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
  three subtab views; routes AI Assist's "pending apply" → Workflow tab focus. *(Phase 0
  done; Track B extends it to mount the real Workflow view in place of the placeholder.)*
- `modules/utilities-ai-assist.js` — single-file dashboard (forked from v2 `ai-assist.js`).
  *(Phase 0 prototype done; Track A extends it.)*
- `modules/utilities-workflow/` — multi-file dir mirroring v2's 9 siblings
  (`index, row, actions, cast-anomaly, orphan, recode, slug-conflict,
  stage-name-conflict, utils`), forked + reskinned. *(Track B, new.)*
- CSS — **one file per track to keep the two parallel tracks merge-clean:**
  `css/enrichment-hub.css` (Phase 0; `.ehub-` + `.aia1-` — owned by **Track A** from here),
  and a **separate** `css/workflow-tools.css` (`.wf1-` — owned by **Track B**). New class
  prefixes (`.ehub-`, `.aia1-`, `.wf1-`) so v1 rules never collide with the v2
  `.aia-`/`.wf-` files in the shared `/css/` dir.
- **Re-home** existing `utilities-enrichment-review.js` view as the Review subtab
  (no logic change). *(Phase 0 done.)*
- Edits to `index.html` (button + view container + CSS links) and `action.js`
  (nav wiring). *(Phase 0 did the button/nav; remaining index.html edits — the Workflow
  subview mount + `workflow-tools.css` link — are **Track B-owned**.)*

### 4.1 File-ownership matrix (prevents the two tracks from colliding)

| File | Track A (AI Assist) | Track B (Workflow) |
|------|---------------------|--------------------|
| `modules/utilities-ai-assist.js` | **owns** | — |
| `css/enrichment-hub.css` | **owns** (`.aia1-` additions) | — |
| `modules/utilities-workflow/*` | — | **owns** (new dir) |
| `css/workflow-tools.css` | — | **owns** (new file) |
| `modules/utilities-enrichment-hub.js` | — | **owns** (Workflow-tab mount) |
| `index.html` (Workflow subview + CSS link) | — | **owns** |

The AI-Assist→Workflow deep-link is the ONE shared concern; it is deferred to the
serial integration step (§5, Track C-glue), not built inside either track.

## 5. Plan (parallelized into two tracks + a serial tail)

Phase 0 is **done** (foundation + de-risk). The remaining work splits into two
independent tracks that run **concurrently in separate git worktrees** (one Sonnet
agent each), then collapse into a short serial tail. The §4.1 ownership matrix keeps
them merge-clean; the one shared concern (cross-screen deep-link) is deferred to the tail.

### Phase 0 — Foundation + thin prototype slice — ✅ DONE
- Hub shell (new Tools button, section-tabs AI Assist · Workflow · Review), Review
  re-home (runtime reparent), AI Assist prototype stat-card row.
- Verified live; commit `9933bdf6` on `feature/v1-enrichment-hub-backport`.
- De-risk outcomes: token map holds (only `--surface-2` gap), v1 design fit confirmed,
  reparent quirk isolated to the Review re-home.

### ▶ Track A — AI Assist (full) — *parallel; ~1 day*
Owns `utilities-ai-assist.js` + `.aia1-` CSS in `enrichment-hub.css`. Extends the Phase 0
prototype to full parity:
- Queue preview; recently-processed activity feed (`since=` HWM polling, pause/clear).
- Sweeper on/off toggle; **Apply all agreed** (202 + `/status` progress polling);
  batch-progress pass pills; KPIs.
- Code-links → `openTitleDetail(byCode)`. Stop all polling on hide.
- **Does NOT** wire the "pending apply → Workflow tab" deep-link (tail step) — for now it
  may no-op or switch to the Workflow tab without focus.
- **Exit:** functional + visual parity with v2 AI Assist, v1-styled, no leaked intervals.

### ▶ Track B — Workflow (full) — *parallel; ~3–5 days (critical path)*
Owns the new `modules/utilities-workflow/` dir, `css/workflow-tools.css`, the hub's
Workflow-tab mount (`utilities-enrichment-hub.js`), and the Workflow `index.html` edits.
Internally **sequential** vertical slices (each builds on the prior):
- **B1 — Table & basics:** rows from `/workflow/rows` with state derivation
  (queued/fetching/ambiguous/judging/split_decision/partial_vote/no_verdict/
  other_intervention), code pill, actress chips, cover thumb, candidate thumbs + Pick
  buttons, the **two-robot judge visualization**, header KPIs, **Queue all ambiguous**,
  **Apply all agreed**, 3s polling, and the `?focus` scroll-highlight *hook* (consumes a
  focus id handed in by the hub).
- **B2 — Inline reason panels:** cast_anomaly (add-alias), orphan (mark moved / two-click
  confirm delete), recode (dry-run preview → commit), slug_conflict (claimant/incumbent
  info), stage_name_conflict (info), actress_rename (dismiss).
- **B3 — Overflow ⋮ menu + extras:** reason-gated actions (mark resolved / accept gap /
  override-slug input / dismiss / refresh) + lightbox for covers/candidates.
- Replaces the hub's "Coming in Phase 2" placeholder with the real mount.
- **Exit:** all 9 reasons & states behave as in v2, v1-styled.

### ◇ Serial tail (after both tracks merge)
- **C-glue — cross-screen deep-link (~15 min):** wire AI Assist's "pending apply" badge →
  switch to the Workflow subtab and focus/scroll-highlight the target row (uses Track B's
  `?focus` hook). The only file both tracks would have touched; isolated here on purpose.
- **Phase 3 — Parity QA + polish:** 12-item Workflow smoke-test (v2 memory checklist) +
  AI Assist feature list, v1 vs v2 side-by-side; design-language review; polling-cleanup
  on tab/hub switches; Playwright safety net (AI Assist coverage may start once Track A
  merges).
- **Phase 4 — Integration & cleanup:** docs (USAGE/spec) + memory; optional lightweight
  assist-queue surface in v1 (no status bar); confirm existing Review untouched/reachable.

### 5.1 Merge & worktree mechanics
- Two worktrees off `feature/v1-enrichment-hub-backport` (or two sub-branches), one per
  track. Each Sonnet agent runs `installDist` in its own tree; the user restarts to verify.
- Merge order: **Track A first** (smaller, no hub edits), then **Track B** (carries the hub
  + index.html edits). Then the C-glue + QA on the integrated branch.
- Critical path ≈ Track B (~3–5 days); Track A finishes inside that window. Net wall-clock
  ~3–5 days vs ~5–7 serial.

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
