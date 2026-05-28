# Proposal: Sync Health Drilldown — actionable detail panels for reconcile signals

**Status:** Draft 2026-05-07 — future work; not yet ready for dispatch.
**Depends on:** `PROPOSAL_SYNC_HEALTH_TAB.md` must land first.
**Origin:** The reconcile signals on Sync Health are inert numbers today — counts only, no way to act on them inline. The detail data is already plumbed (`/api/reconcile/run` populates detail lists when `verbose=true`) but the web UI doesn't render it. Each signal has a natural inline resolution flow that would turn the panel from decorative to actionable.

---

## 1. Problem statement

After Sync Health Tab ships, the reconcile signals card looks like:

```
Dup live: 7   Pending grace: 12   Past grace: 0   Mismatches: 21
[Run reconcile]  [Run + sweep]
```

These four numbers tell the user something is off, but resolving any of them requires:
- Switching to MCP / shell to get details (`reconcile --verbose`).
- Cross-referencing with other tools (`find_misnamed_folders_for_actress`, per-volume sync).
- Manually deciding which volume to sync to clear a duplicate.

A direct UI affordance — click a signal, see the offending rows, act on them — would close the loop entirely inside the web app.

---

## 2. Design principles

1. **Click a count, see the rows.** Each of the four cards becomes clickable; clicking opens a detail panel below or beside the signals row, populated with the verbose detail list.
2. **Inline resolution where possible.** Each signal type has a well-defined action that resolves it; expose that action per-row.
3. **Re-use existing tools.** Don't reinvent — call existing MCP/repo functionality (e.g., per-volume sync, `find_misnamed_folders_for_actress`) rather than building parallel paths.
4. **No new persistence.** Resolution actions write through existing endpoints; the next reconcile run reflects the cleared state.

---

## 3. Per-signal detail UI

### 3.1 Duplicate live locations

**Detail row:**
```
Title code        Volume A        Volume B        Action
ABP-001           vol-a queue     vol-b popular   [Trust vol-b]  [Trust vol-a]  [Open title]
```

**Action: "Trust vol-X"** — runs a partition sync of the *other* volume's matching partition, which will mark the orphaned source row stale. Backed by the existing per-volume sync endpoint.

**Cognition:** Duplicate live = a successful cross-volume move where the source wasn't synced. "Trust vol-X" tells the system "the file lives at vol-X now; refresh the other side to confirm it's gone there."

### 3.2 Pending grace

**Detail row:**
```
Title code   Volume   Path                Days stale    Action
ABP-010      vol-a    /queue/ABP-010      14            [Sync vol-a]  [Open title]
```

**Action: "Sync vol-a"** — runs a sync of that volume. If the folder really is gone, the row stays stale (now possibly closer to past-grace). If the folder reappeared in the meantime, the row clears stale.

**Cognition:** Pending grace = "we marked this stale on a previous sync; if you've since moved the file back or the volume was just temporarily missing, sync it again to confirm."

### 3.3 Past grace stragglers

**Detail row:**
```
Title code   Volume   Path                Days stale    Action
ABP-020      vol-a    /queue/ABP-020      120           [Sweep this row]  [Open title]
```

**Action: "Sweep this row"** — deletes that single `title_locations` row (calls the existing `sweepStaleOlderThan` path with a single-row predicate, OR exposes a new `deleteById` endpoint guarded by "row must be past grace"). Faster than running a full sweep.

**Cognition:** Past-grace = "this row is overdue for sweeping anyway; you can clear it now without waiting for the next sync."

### 3.4 Actress folder mismatches

**Detail row:**
```
Title code   Actress         Volume   Path                Action
ABP-030      Yui Hatano      vol-a    /stars/library/Other Actress/ABP-030   [Open in Misnamed Folders]  [Open title]
```

**Action: "Open in Misnamed Folders"** — links into the existing `find_misnamed_folders_for_actress` workflow, scoped to the relevant actress. The actual fix lives there; reconcile just surfaces the count and acts as a launchpad.

**Cognition:** Mismatch = "the title's filing actress doesn't match the folder it sits under." Resolution requires choosing whether to retitle the actress, move the folder, or accept it (e.g., a duo title). All of that already exists in the misnamed-folders flow.

---

## 4. UI structure

```
┌─ Reconcile signals ────────────────────────────────┐
│  Dup live: 7    [click to expand]                  │
│  Pending: 12    [click to expand]                  │
│  Past:    0     —                                  │
│  Mismatches: 21 [click to expand]                  │
│                                                    │
│  [Run reconcile] [Run + sweep]                     │
│                                                    │
│  ┌─ Detail: Duplicate live locations (7) ──┐      │
│  │  ABP-001 │ vol-a queue │ vol-b popular  │      │
│  │          [Trust vol-b] [Trust vol-a]    │      │
│  │  ABP-002 │ …                            │      │
│  └──────────────────────────────────────────┘     │
└────────────────────────────────────────────────────┘
```

One detail panel at a time (clicking a different signal swaps the contents). Cards show a small chevron when they have data; cards with count=0 are not clickable.

---

## 5. Implementation sketch

### 5.1 Frontend

- `utilities-sync-health.js` gains:
  - `renderSignalDetails(signalKey)` — renders the detail panel for one of `dupLive`/`pendingGrace`/`pastGrace`/`mismatch`.
  - `handleSignalAction(signalKey, action, rowData)` — dispatches to the right endpoint per action.
  - State: `selectedSignal: 'dupLive' | 'pendingGrace' | 'pastGrace' | 'mismatch' | null`.
- Card click handlers toggle `selectedSignal` and call the verbose endpoint to get details.

### 5.2 Backend additions

- `POST /api/reconcile/sweep-row?id=N` — sweep a single past-grace row (after verifying it's past grace; reject otherwise).
- `POST /api/reconcile/trust-volume?titleId=N&trustVolumeId=X` — convenience endpoint that triggers a partition sync of the other volume's matching partition.
- All other actions reuse existing endpoints.

### 5.3 Tests

- Backend: tests for the two new endpoints — happy path + guard rejection (e.g., trying to sweep a row that's still in grace).
- Frontend: manual smoke check.

---

## 6. Phasing within this proposal

Each signal type is independently shippable. Recommended order (by effort × value):

1. **Duplicate live** (highest leverage; clearest resolution) — ~1.5 hr.
2. **Past grace** (smallest action; sweep-this-row is trivial) — ~1 hr.
3. **Pending grace** (calls existing sync endpoints; mostly UI) — ~1 hr.
4. **Mismatches** (deepest integration via misnamed-folders flow; mostly link-out) — ~1.5 hr.

Total: ~5 hr Sonnet across 4 PRs (or batched into 2 if the user prefers fewer dispatches).

---

## 7. Open questions

1. **Should "Trust vol-X" auto-run after click, or stage as a confirmation modal?** Auto-run means a partition sync kicks off immediately (locks the task runner). Modal means user confirms once. Lean modal — partition sync is fast but not free, and an accidental click would tie up the lock for ~30 sec.
2. **Detail panel — inline below the signals card, or in a side panel?** Inline is simpler; side panel scales better when one wants to compare two signals. Lean inline for v1.
3. **Pagination for detail lists?** Reconcile detail lists can theoretically be long (`MISMATCH_LIMIT = 5000` in `ReconcileService`). Cap at 50 rows per panel with a "show all" link to a dedicated detail page if truncated.
4. **Should the inline actions be available in the persisted-report view too?** A historical report's "duplicate live: 7" might no longer reflect current state — clicking-through to act on it could be confusing. Lean: historical reports are read-only; inline actions are only on the live (current) reconcile output.

---

## 8. Why this is deferred

Per the parent proposal `PROPOSAL_SYNC_HEALTH_TAB.md`:
- Drill-down is the highest-leverage feature long-term but the biggest UI investment.
- Best done after Sync Health Tab ships and is in use for a few weeks — that lets the user see which signals they actually want to act on inline vs. just see counts for. Some signals may turn out to be rare enough that the count-only view is fine.
- Avoids speculative UX work; the page-of-record exists first, then we enhance.
