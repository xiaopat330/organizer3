# Proposal: Sync Health Tab — consolidate sync surfaces, demote per-volume sync as default

**Status:** Draft 2026-05-07 — for discussion, no implementation yet.
**Origin:** `PROPOSAL_SYNC_RECONCILIATION.md` shipped three sync capabilities (grace-period orphans, coherent multi-volume sync, reconcile-only pass) but their web UI surfaces ended up scattered: coherent sync became a button on Tools → Volumes, reconcile became a small chip on Tools → Library Health. The user has to know two pages to think about library sync state. Per-volume sync remains the visually-prominent action even though the daily/weekly default should be coherent sync.

This proposal consolidates all sync-shaped UI into a single canonical "Sync Health" tab and demotes per-volume sync from the primary action to a targeted-operations tool.

---

## 1. Problem statement

Three concrete pain points after the reconciliation feature shipped:

1. **No canonical sync home.** Coherent sync lives on Volumes; reconcile on Library Health; per-volume sync also on Volumes. A user thinking "is my library consistent?" has to visit two pages.
2. **Per-volume sync is visually the default.** The Volumes page still leads with per-volume `[Sync]` buttons — but the right answer for cross-volume movement is coherent sync. The UI signals the wrong workflow.
3. **Persisted reconcile reports are invisible.** Every coherent sync writes a report row; the user only ever sees the latest count on the Library Health chip. The history exists in the DB but no UI surfaces it.

---

## 2. Design principles

1. **One sync home.** Every sync action and signal lives on one tab. Library Health goes back to being purely about checks. Volumes (if kept) becomes per-volume detail/diagnostics.
2. **Coherent sync is the primary CTA.** Demote per-volume to secondary visual weight. Per-volume stays — it's the right tool for "I dropped 30 titles into queue/" — but it's no longer the eye-catcher.
3. **Build alongside, then consolidate.** Phase A adds Sync Health without removing anything; Phase B removes the now-duplicated UI. Each phase is independently shippable.
4. **No backend rework.** Phase 1–3 of the reconciliation feature already exposed every endpoint we need. One small repository addition (`findLastByTrigger`) is the only backend touch.

---

## 3. Page layout — Sync Health

```
┌─ Sync Health ──────────────────────────────────────┐
│  ┌─ Run sync ─────────────────────────────────┐    │
│  │  ▶ Run coherent sync                       │    │
│  │    Reconciles all volumes. Last run: …     │    │
│  │    Holds the task lock; safe overnight.    │    │
│  └────────────────────────────────────────────┘    │
│                                                    │
│  ┌─ Reconcile signals ────────────────────────┐    │
│  │  Dup live: 0  Pending: 2  Past: 0  Mism: 7 │    │
│  │  [Run reconcile] [Run + sweep]             │    │
│  │  Last reconcile: …                         │    │
│  └────────────────────────────────────────────┘    │
│                                                    │
│  ┌─ Recent reports (last 30) ─────────────────┐    │
│  │  time         trigger         dup pen past mis│  │
│  │  2026-05-07   coherent_sync    0   2   0   7 │  │
│  │  2026-05-04   manual            0   1   0   7 │  │
│  │  …                                         │    │
│  └────────────────────────────────────────────┘    │
│                                                    │
│  ┌─ Per-volume actions ───────────────────────┐    │
│  │  vol-a (mounted) [Sync] [Sync queue]       │    │
│  │  vol-b           [Mount]                   │    │
│  │  …  (smaller buttons, secondary styling)   │    │
│  └────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────┘
```

Visual hierarchy: primary CTA → signals → history → per-volume admin tools.

---

## 4. Phase A — Build alongside (~2 hr Sonnet)

Additive UI. Nothing existing changes; user can switch between old and new surfaces.

### 4.1 Files

**New:**
- `src/main/resources/public/modules/utilities-sync-health.js` — view module mirroring the structure of `utilities-volumes.js`.
- HTML scaffold added to `src/main/resources/public/index.html` under id `tools-sync-health-view`.
- CSS rules in an existing utilities/tools stylesheet (likely `backup.css` or `tools-chrome.css` — match wherever other tool pages live).

**Modified:**
- Tool nav registration (find existing pattern in the chrome JS — `tools-chrome.js` or similar).
- `Application.java` — no changes if we route the existing endpoints; one line if a new route is needed.

### 4.2 Backend additions

- `ReconcileReportRepository.findLastByTrigger(String triggeredBy)` returns the most recent persisted report for a given trigger value (`'coherent_sync'` or `'manual'`). Backed by a query against `reconcile_reports` ordered by `generated_at DESC LIMIT 1`. Empty `Optional` when none exists.
- One web route: `GET /api/reconcile/last?trigger=coherent_sync` — returns the result above. Used by the "Last coherent run: …" timestamp.

Everything else reuses existing endpoints:
- `POST /api/reconcile/run` — already wired
- `GET /api/reconcile/recent?limit=30` — already wired
- `POST /api/utilities/tasks/volume.sync_coherent/run` — already wired
- Existing per-volume sync/mount/unmount endpoints used by `utilities-volumes.js`

### 4.3 Logic to duplicate (not extract)

The reconcile chip currently in `utilities-library-health.js` (`loadLatestReconcile`, `renderReconcile`, `runReconcile`, etc.) duplicates into the new file. Phase B removes the Library Health version, so extracting now is churn. Each version is ~80 LOC.

Per-volume action rendering — also duplicate from `utilities-volumes.js`. The visual treatment differs (smaller, secondary) so a shared helper would have to take styling parameters; cleaner to copy the network calls and state subscription into the new file with a tighter render. Phase B does NOT remove the Volumes page (per-volume action remains there with full UI), so a slightly trimmed copy in Sync Health is correct.

### 4.4 Tests

- Backend: `JdbiReconcileReportRepositoryTest` gets a new test for `findLastByTrigger` (multiple reports with mixed triggers, assert the right one is returned).
- Frontend: no JS test framework in use; manual smoke check via dev server is the project convention.

### 4.5 Sonnet brief checklist

- Pre-flight audit: list all `/api/*` endpoints currently used by `utilities-volumes.js` and the reconcile chip in `utilities-library-health.js`.
- Confirm the tool-nav registration pattern (one place, not three).
- Anti-stall: commit logically as you go (scaffold → render → actions → backend), don't over-explore at the end.

---

## 5. Phase B — Consolidate (~1.5 hr Sonnet)

Pure UI cleanup. No backend changes.

### 5.1 Changes

- `utilities-library-health.js` — remove the entire reconcile chip section (HTML scaffold elements, JS functions `loadLatestReconcile` / `renderReconcile` / `runReconcile` / `wireReconcileButtons` / `setReconcileNum`, CSS rules `.lh-reconcile*`).
- `utilities-volumes.js` — remove the "Coherent sync (all volumes)" button and its handler (`confirmAndStartCoherentSync`). The Volumes page becomes purely per-volume operations and diagnostics.
- `index.html` — remove the orphaned reconcile panel scaffold from the Library Health view.
- Cross-references: any text mentioning "Tools → Volumes" for sync (MCP tool descriptions, shell command help) updated to "Tools → Sync Health".
- Optional: small "Open Sync Health" link in the Volumes page header for users who land there expecting sync.

### 5.2 What stays on the old surfaces

- **Volumes page:** per-volume sync, mount/unmount, last-synced badge, "stale locations" badge + clean action, all the per-volume diagnostics. The page is now per-volume detail rather than the sync home.
- **Library Health page:** all check rows, the "Scan library" button, recompute ratings. Reconcile is no longer there.

### 5.3 Tests

Manual verification:
- Sync Health: coherent sync button, reconcile run, sweep, recent reports table, per-volume actions.
- Volumes: per-volume sync still works, no "Coherent sync" button.
- Library Health: renders without reconcile chip; all checks still functional.

### 5.4 Sonnet brief checklist

- Pre-flight: grep for all references to the removed elements (HTML ids, function names, CSS classes) and confirm nothing else depends on them.
- Anti-stall: tight scope — UI removal only, no new behavior.

---

## 6. Sequencing

1. Phase A → review and use for a day or two before Phase B. The fallback surfaces (old Volumes coherent button, Library Health reconcile chip) remain functional.
2. Phase B → cleanup. Run after confidence in Phase A.
3. Don't combine: tighter scope per dispatch, lower stall risk (recent dispatches stalled when scope crept).

---

## 7. Open questions

1. **Should "Run + sweep" stay a button on Sync Health, or move to a Recent-reports row action?** Today it's the second button on the reconcile signals card. Argument for keeping: discoverable. Argument for moving: most users won't sweep most of the time; clutters the primary action area. Lean keep for now.
2. **Per-volume action list inline or behind an "Advanced" expander?** Lean inline (single user; hiding controls protects no one), but with secondary styling. Re-evaluate after using the page.
3. **Recent reports table — pagination needed?** 30 rows is enough for most use; coherent sync is weekly at most. If usage grows, add a "view all" route.

---

## 8. Future work (separate proposal)

`PROPOSAL_SYNC_HEALTH_DRILLDOWN.md` — the next step, where each reconcile signal card becomes clickable and renders a detail panel with actionable per-row controls. Depends on this proposal landing first.
