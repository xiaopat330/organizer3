# Proposal: v2 Discovery — UX redesign (Bucket 3)

**Status:** 2026-05-20 — decisions D1–D7 locked; ready to scope Phase A.
**Branch context:** Buckets 1+2 already shipped on `feature/v2-discovery-reskin` (CSS reskin to design tokens; Enrich-tab sidebar→workbench-table+inspector; rate-limit pill; backdrop blur on remaining overlays).
**Scope of this doc:** Bucket 3 — the IA / workflow rethink that Buckets 1+2 deliberately deferred.
**Hard constraint:** Functional parity. Every behavior catalogued in §3 must survive the redesign in some form. The UI itself is open for redesign.

---

## 1 · Why this is a redesign, not another refactor

Buckets 1 and 2 fixed the cosmetic and layout-level problems on the Enrich tab. What they could not fix — without overstepping the deferral — is the **information architecture**:

- One page (`/v2-discovery.html`) hosts **four orthogonal workflows** behind a tab bar (Enrich, Titles, Collections, Queue). Tabs are an in-page mode switch with no URL deep-linking and no left-rail presence, so the v2 navigation language (rail → topbar breadcrumb → main surface) stops working the moment a user is "in Discovery."
- Three of the four tabs (Enrich, Titles, Collections) **share the same primary verb** — "enqueue work for the enrichment backend" — but expose it through three completely different selection mechanics (actress-driven, page+filter-driven, page+filter-driven on multi-cast).
- The fourth tab (Queue) is **observability**, not selection — it watches the work the other three tabs created. It belongs in a different mental category, and its 5-second polling is wasted whenever the user has tabbed away.
- Critical features are buried: the **AI assist** pill, the **rate-limit** pill, the **error-picker** workflow with AI suggestions, the **derive-slug-from-cast** fallback, and the **per-job queue manipulation** controls (priority, pause/resume, requeue) all live in non-discoverable corners.
- Modal stacking is fragile: cover lightbox, title peek, enrich detail, error picker, and (until Bucket 2) the actress inspector all competed for the same overlay layer with bespoke ESC handling. The Bucket 2 audit found defensive AbortController patterns and ESC-shadowing checks that read like scar tissue.

These are not styling problems. They are signs that the surface was **conceived as four screens stitched together** rather than as a single coherent workspace.

This proposal argues for collapsing the four tabs into **one workbench**, with the four current views recast as **modes of one selection-and-action loop**, plus a persistent **observability strip** for the queue.

---

## 2 · Design principles

1. **One mental model: select work → take action → watch it run.** All four current tabs are facets of this loop. The redesign must make the loop visible without hiding any of the existing entry points.
2. **Selection is plural by default.** Today, Enrich operates on one actress at a time; Titles/Collections operate on a checkbox set. The redesign should allow multi-select universally, with single-select degenerating naturally.
3. **The queue is always visible.** Not a tab to switch to. The current page-context tabs hide the consequence of every "Enqueue" click. The redesign promotes queue state to persistent chrome.
4. **Pause/resume and rate-limit are first-class controls.** Hidden in a top-right pill today. They should be as accessible as the primary "Enrich" action because they're invoked just as often during an enrichment session.
5. **Modal-as-last-resort.** Inspectors, side panels, and inline drawers preferred. The only justified modals are the cover lightbox (legitimately attention-grabbing) and confirmation dialogs.
6. **URL is the source of truth.** Every view — actress selected, filter applied, queue item focused — must be linkable. Today, none of it is.
7. **No silent feature regressions.** Every item in §3 must be reachable in the new IA. If something is dropped, it gets called out explicitly in §7 with rationale.
8. **One accent, one surface chrome.** Already enforced by Buckets 1+2; restated here so the redesign doesn't accidentally re-introduce per-mode color identity.

---

## 3 · Parity baseline (what must survive)

Full functional inventory was captured in the design session that preceded this proposal — summarized here for quick reference. **Anything not on this list is fair game to redesign or remove.** Anything on this list must have a corresponding affordance in the new IA.

### 3.1 Selection mechanics (today: three different paradigms)

| Today | What user does | Output |
|---|---|---|
| Enrich tab | Select one actress; click ▶ Enrich | All her unenriched titles queued |
| Titles tab | Filter by recent/pool + code, paginate, checkbox-multi-select | Selected titles queued |
| Collections tab | Filter by code (always source=collection), checkbox-multi-select | Selected multi-cast titles queued |

### 3.2 Per-actress drill-downs (today: 4 sub-tabs inside Enrich inspector)

- **Titles:** Filtered (tag facets, rating thresholds) list of her enriched/unenriched titles with re-enrich and detail-modal actions.
- **Profile:** javdb slug, fetched-at, avatar, social handles, name variants; actions: re-fetch profile, derive-slug-from-cast (for missing-slug case), download avatar.
- **Conflicts:** Titles where she's credited locally but absent from the enriched javdb cast — with cover-image zoom.
- **Errors:** Failed jobs grouped by error class (ambiguous / no-match / fetch-failed / etc.); per-row Retry; for ambiguous: inline picker with candidate cards + AI suggestion banner + Pick/None-of-these resolutions.

### 3.3 Queue observability + manipulation

- Live list of every pending/in-flight/failed/paused job (5 s polling).
- Per-job actions: ⇑/↑/↓/⇓ priority, ⏸/▶ pause/resume, ↺ requeue (failed only).
- Status taxonomy: in-flight, pending, paused, failed-resolvable (ambiguous/cast-anomaly/sentinel), failed-dead-end (not-found/orphaned), failed-transient (fetch-failed/no-slug).
- ETA estimate for top-8-in-queue, hidden when rate-limit pause is active.
- Cross-links: failed→Review row, actress link → Enrich tab focus, code link → cover lightbox.

### 3.4 Global controls

- **Rate-limit pill** — burst-break vs. rate-limited states, force-resume button.
- **AI assist pill** — off / running / error states with start + retry.
- **Cover lightbox** — invoked from at least 4 places (peek modal, conflict badges, picker candidates, queue rows).
- **Title peek modal** — code-cell click anywhere; shows cover, cast, grade, tags, NAS paths.

### 3.5 Implicit workflows (must remain ergonomic)

1. Enrich one actress end-to-end.
2. Drain a Collections backlog via filtered multi-select.
3. Respond to a rate-limit event (force-resume or wait).
4. Triage failed enrichments via the error picker.
5. Re-enqueue or detail-inspect a single title.
6. Derive a missing profile slug from cast data.

---

## 4 · Proposed IA — "Discovery Workbench"

A single-page workspace organized into three persistent regions plus a transient inspector. **No top-level tabs.**

```
┌─────────────────────────────────────────────────────────────────────┐
│ Topbar:  Discovery   [global controls strip]                       │
│ ────────────────────────────────────────────────────────────────── │
│ Pivot strip:  Actresses · Titles · Collections    [search/filters] │
│ ────────────────────────────────────────────────────────────────── │
│ ┌──────────────────────────────────┐  ┌──────────────────────────┐ │
│ │                                  │  │                          │ │
│ │  Selection table (main surface)  │  │  Inspector (right side)  │ │
│ │  rows of whatever pivot          │  │  contents driven by      │ │
│ │  is active                       │  │  selected row(s)         │ │
│ │                                  │  │                          │ │
│ │                                  │  │                          │ │
│ └──────────────────────────────────┘  └──────────────────────────┘ │
│ ────────────────────────────────────────────────────────────────── │
│ Queue dock (persistent, collapsible): live job ticker + controls   │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.1 The three regions

**A. Global controls strip (top, never scrolls).**
Houses the **rate-limit pill**, the **AI assist pill**, the **pause-all queue toggle**, and the **"What's new in the queue"** badge (`+N jobs added in last 60s`). Replaces the current header buttons plus the rate-limit banner. Always visible regardless of what the user is doing.

**B. Pivot strip + main surface.**
The pivot strip is a **three-way toggle**: *Actresses* | *Titles* | *Collections*. Selecting a pivot changes:
- The columns and row identity of the main table.
- The default filter set in the context strip (right of the pivot toggle).
- The inspector contents.

The pivot is **not** a tab in the old sense — it's a query lens. The same workbench skeleton stays in place; only what's loaded changes.

**C. Inspector (right side, ~360–420 px).**
Always-present right panel that:
- When **0 rows selected:** shows a contextual hint + Queue summary + recent-activity feed.
- When **1 row selected:** shows the rich detail view (for Actresses pivot: the current 4-sub-tab content — Titles / Profile / Conflicts / Errors).
- When **N>1 rows selected:** shows a *bulk action* panel — counts, eligibility summary, primary actions (Enqueue selected, Pause matching queue jobs, etc.).

**D. Queue dock (persistent bottom strip).**
Always visible, collapsible to a 32 px ticker. Expanded form shows the current queue table (the existing Queue tab content). Collapsed form shows: live count of in-flight/pending/failed, top-1 currently-running job, ETA-to-empty. Queue dock survives pivot changes — you can be triaging Collections while watching jobs land. The 5 s poll runs only when the dock is **expanded or in-flight count > 0**, fixing the "polling stops when you tab away" bug today.

### 4.2 Why this collapses cleanly to the current four tabs

| Today | New IA mapping |
|---|---|
| Enrich tab | Pivot = *Actresses*. Main table = actress list. Inspector = current sub-tabs. |
| Titles tab | Pivot = *Titles*. Main table = unenriched titles (recent + pool source as a filter chip). Inspector = title detail (today's "peek modal" inlined). |
| Collections tab | Pivot = *Collections* (multi-cast titles only). Main table = same shape as Titles. Inspector = title detail + cast-eligibility breakdown. |
| Queue tab | Queue dock (bottom strip). |

The four-tabs-as-modes problem becomes "three pivots + one persistent dock" — three of which share a workbench shape and one of which is observability.

### 4.3 Selection semantics (uniform across pivots)

- **Click a row:** select-one + open inspector.
- **Cmd/Ctrl-click:** add to selection.
- **Shift-click:** range select.
- **Header checkbox:** select all visible.
- **Esc:** clear selection.

Bulk inspector renders the moment selection > 1, so the user never wonders "where do I act on this multi-select." Today's footer "Enqueue {N}" pattern goes away — the action lives in the inspector where the user is already looking.

For the Actresses pivot, multi-select unlocks a new bulk action that's *not possible today*: "Enrich titles for these 5 actresses." Today this requires 5 separate click-to-select-then-Enrich sequences.

### 4.4 What replaces the current modals

- **Cover lightbox** — keeps existing modal form (legitimate full-screen attention). Already gets backdrop blur from Bucket 2.
- **Title peek modal** — folded into the inspector when pivot is Titles or Collections; folded into a row-expand drawer when pivot is Actresses (because the inspector is showing actress data). Eliminates one of the four overlay systems.
- **Enrich detail modal** (per-title enrichment snapshot) — folded into the inspector as a "Detail" sub-tab when a single title is selected within the Actress→Titles sub-tab. Eliminates a second overlay.
- **Error picker** (with candidate cards + AI suggestion) — folded into the inspector when the user clicks a failed row in Errors. Inline drawer rather than nested overlay. Eliminates a third overlay.

After redesign: one true modal (cover lightbox) + the standard confirmation dialog pattern. Three overlay systems removed.

### 4.5 URL design

- `/v2/discovery` → default pivot (Actresses), no selection.
- `/v2/discovery?pivot=titles&pool=qnap&filter=ABP-` → deep-link to filtered Titles pivot.
- `/v2/discovery?pivot=actresses&id=842&panel=errors` → actress 842 selected with Errors sub-panel.
- `/v2/discovery?queue=open` → workbench with queue dock expanded.

URL drives state on load; state updates URL via `history.replaceState` to avoid back-button pollution on filter/selection churn. Selection of a specific item *does* push history so back-button is intuitive ("back" returns to the prior selection).

---

## 5 · Workflow walkthroughs (in the new IA)

For each of the six workflows from §3.5, sketched as click sequences. Bold = action items that are net-new affordances.

### 5.1 Enrich one actress
Pivot defaults to Actresses → click row → inspector opens with Titles sub-tab → click ▶ Enrich → toast "Enqueued 14" appears in Queue dock → Queue dock auto-expands briefly to show the new jobs (collapses again after 4 s). Same click count as today; the queue feedback is more honest.

### 5.2 Drain a Collections backlog
Switch pivot to Collections → filters strip shows code/label search + tag/rating chips → checkbox-multi-select → bulk inspector shows "12 selected · 8 will chain profile fetch · 4 sentinel" → click Enqueue Selected. **Net-new:** the eligibility breakdown is shown in the bulk inspector *before* enqueueing, not buried per-row.

### 5.3 Respond to a rate-limit event
Global controls strip shows ⚠ Rate limited ×3 · resumes 14:32. Click ▶ Force Resume → confirmation toast. Same as today, but the pill is in a fixed top-strip location that doesn't drift between tabs.

### 5.4 Triage failed enrichments
Pivot = Actresses → select actress → Errors sub-tab in inspector → click an ambiguous row → inline drawer expands within the inspector showing candidate cards + AI suggestion banner + cover comparison → click Pick this. **Net-new:** no separate picker modal; the drawer is part of the inspector so other context (cover, cast, profile) stays visible.

### 5.5 Re-enqueue or inspect a single title
Pivot = Titles → click code in any row → inspector shows title detail + cast + paths + Re-enrich button. **Net-new:** no peek modal — same data, inline. Re-enrich is one click away from the row.

### 5.6 Derive missing profile slug from cast
Pivot = Actresses → select actress with missing profile → Profile sub-tab → if 404: "Find Slug from Titles" button visible → click → derivation runs → on tie, candidate table shown inline; on success, slug populated. Same flow as today; just lives inside the inspector instead of a modal.

---

## 6 · What gets dropped or transformed

| Today | Status in redesign | Rationale |
|---|---|---|
| 4-tab top bar | **Removed** | Replaced by pivot strip + queue dock. |
| Enrich tab's left-sidebar actress list | **Already replaced** (Bucket 2) | Now workbench table. |
| Title-peek modal | **Folded into inspector** | One less overlay system. |
| Per-title enrichment detail modal | **Folded into inspector** | One less overlay system. |
| Error picker modal | **Folded into inspector drawer** | One less overlay system. |
| Per-tab polling (10 s actress, 5 s queue) | **Replaced** by a single queue-dock-driven poll | Polling tied to dock visibility + in-flight presence, not active tab. |
| Floating footer "Enqueue N" bar | **Removed** | Action moves into bulk inspector. |
| Top-right AI-assist pill placement | **Promoted** to global controls strip | More prominent because it's invoked frequently. |
| Rate-limit banner / pill drift between tabs | **Fixed** | Always in same strip location. |
| Per-tab sticky filter state | **Replaced** by URL params | Same UX, but linkable. |
| Hardcoded tier-threshold heuristic on client | **Kept as-is** | Out of scope (data flow problem, not UX). |

Nothing on the §3 parity list is dropped without a mapping above.

---

## 7 · Decisions (locked 2026-05-20)

| ID | Decision | Resolution |
|---|---|---|
| **D1** | Queue dock default state | **Collapsed.** 32 px ticker showing in-flight count + top job + ETA; auto-expands briefly on new enqueue activity. |
| **D2** | Pivot vs. separate URLs | **Pivots on one page.** Single `/v2/discovery` route with pivot strip; URL param drives `pivot=actresses\|titles\|collections`. Queue dock persists across pivot changes. |
| **D3** | Inspector width | **Resizable 320–520 px** with user-draggable divider. |
| **D4** | Multi-actress bulk enrich | **Yes — enabled.** Cmd/Ctrl-click + Shift-click multi-select on Actresses pivot; bulk inspector shows aggregate counts + single Enqueue button fanning out across selected actresses. |
| **D5** | AI Assist pill placement | **Top global controls strip,** adjacent to the rate-limit pill. Same visibility regardless of pivot or dock state. |
| **D6** | Cutover strategy | **Coexist briefly.** Ship redesign at `/v2/discovery` (new URL); leave `/v2-discovery.html` running through smoke-test; delete legacy once confident (~1 week). |
| **D7** | Scope ceiling | **+small accompanying features.** Permitted: (a) multi-actress bulk enrich [per D4], (b) eligibility-summary preview in bulk inspector before enqueueing, (c) URL deep-link param parsing. No other net-new features in this build. |

---

## 8 · Implementation phasing (sketch — not commitments)

Buckets 1+2 are done on `feature/v2-discovery-reskin`. Bucket 3 (this proposal) is the natural follow-on; the staging below assumes the same long-lived branch.

**Phase A — Skeleton (medium, ~1–2 sessions)**
- New page route + URL state plumbing.
- Workbench layout: global strip + pivot strip + main table shell + inspector shell + queue dock shell.
- Pivot switch wires through to existing data-loading functions (no rewrite of fetch logic).
- Queue dock collapsible chrome with dummy content.
- No new features yet; no migration of existing tabs.

**Phase B — Pivot port (large, ~2–3 sessions)**
- Port Actresses tab content into Actresses pivot + inspector (largely reuses Bucket 2 work).
- Port Titles tab into Titles pivot.
- Port Collections tab into Collections pivot.
- Port Queue tab into expanded queue dock.
- Three overlays (peek / detail / picker) fold into inspector.

**Phase C — Net-new affordances (small, ~1 session)**
- Multi-select on Actresses pivot (if D4 = yes).
- Eligibility breakdown in bulk inspector (if D7 = +features).
- URL deep-link parameter parsing.

**Phase D — Cutover (small, ~half session)**
- Remove or compat-shim `/v2-discovery.html` (per D6).
- Documentation updates: `spec/USAGE.md`, screenshots in `reference/ui-mockups/`.

Total estimate: **4–6 working sessions on the existing feature branch.** Significantly larger than Bucket 1 (one session) and Bucket 2 (one session) combined. Smoke-test gates at the end of each phase.

---

## 9 · Risks and unknowns

1. **Multi-select on the Actresses pivot interacts with the inspector's heavy sub-tabs.** When 5 actresses are selected, the inspector can't show 5 Profile/Conflicts/Errors panels. The proposal handles this with a "bulk action mode" inspector that's different content entirely — but the transition between single-select inspector and bulk-select inspector needs UX care to not feel jarring.
2. **Queue dock + inspector both want the right-side or bottom region.** If a user has the dock expanded and a row selected, the inspector + dock + table compete for screen real estate on smaller windows. Proposed handling: dock collapses automatically when inspector is open AND viewport < some width. Needs a real screen-size audit before committing.
3. **URL-as-state may collide with existing v2 navigation patterns.** The v2 design system doesn't yet have a strong URL-state convention. We'd be the first surface to lean on it heavily; risk of inconsistency with later surfaces that adopt different conventions.
4. **The "polling tied to dock visibility" change is a behavior change.** Today, queue polling stops when you tab to Enrich. After redesign, it depends on dock state + in-flight count. Need to verify no edge cases where the dock collapses too aggressively and the user misses important state changes.
5. **Fragility in error-picker AI suggestion plumbing.** The functional inventory flagged six AI-confidence states (`agreed`, `phi4_only`, `gemma_only`, `conflict`, `both_abstain`, `error`). Folding this into an inline drawer instead of a modal means the drawer's rendering has to handle all six states cleanly in less screen space. Worth a UX sketch before committing.
6. **Cmd-click standard on Mac, Ctrl-click on Windows/Linux.** The frontend already has at least one platform-detection helper for keyboard shortcuts. Make sure multi-select uses it consistently.

---

## 10 · Open questions deliberately not answered here

- Studio-level enrichment is out of scope (already deferred per Translation Phase 6 closure).
- Sync between Discovery and the Translation queue / Enrichment Review pages is unchanged; deep-links between them keep working.
- The MCP-layer endpoints in §3 are *not redesigned* by this proposal. If we want to consolidate endpoints (e.g., merge `actresses/{id}/titles` + `actresses/{id}/tag-facets` into one call), that's a separate proposal.
- The AI Picker Assist feature (already shipped) participates in Errors sub-tab as today; no changes to its model/dispatch logic.

---

## 11 · Next step

D1–D7 are locked. Phase A (skeleton + URL plumbing) is ready to dispatch. Further questions will be raised as implementation surfaces them.
