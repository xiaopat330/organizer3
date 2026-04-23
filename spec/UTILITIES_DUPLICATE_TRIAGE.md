# Utilities — Duplicate Triage

> **Status: EARLY / POC-phase** (2026-04-22)
>
> Feature branch: `utilities-duplicate-triage`.
>
> Unlike the prior Utilities screens (Volumes / Actress Data / Backup / Library
> Health), this one is not ready to spec-then-build. The problem space is wide
> enough that we don't yet know what the right experience is. This document
> captures what we've learned in discussion and establishes a **POC-first**
> approach: build the cheapest possible thing that puts real data in front of a
> real user, iterate on the UX, and rewrite this spec once the shape is
> visible.

## Why this exists

The current "Duplicates" tile is a wall of data: titles with >1 location,
rendered as an infinite-scroll list, with a modal for inspection. It has no
actions, no durable decisions, and doesn't surface the hard cases. Users can
only look.

This replaces it with a **triage workflow** — help the user make decisions,
remember them, and act on them.

## What we've learned so far (notes)

Captured from discussion so the POC carries forward the actual problem, not a
sanitized version of it.

### Where duplicates come from

1. **Cold-storage recovery.** Old archives from before the library had any
   management are being reintegrated. Some of that content is already present;
   reintegration creates duplicates.
2. **Quality replacements lost in the pool.** A renewed/better version was
   meant to replace the original but got lost; both still exist.
3. **Code normalization drift** — `ONED-01` vs `ONED-001`. Especially older
   titles. These appear as *different title rows* in the DB, not as
   multi-location groups — the current screen misses them entirely.
4. **Typos / mislabels** — poor actress spelling, wrong label, cross-volume
   mislabels (a title ends up filed against the wrong actress or volume).
5. **Accidental copies** — staging in `queue/` when the title already lives
   under `stars/library/` on the same or a different volume.

These can land anywhere. Intra-partition, intra-volume, cross-volume, and
across logically-different-but-actually-same title rows.

### Where duplicates live (scope levels)

Three natural zoom levels, probably three entry points long-term:

- **Per actress** — an actress's folder can contain variant-named copies of
  the same title: `Yua Aida (ONED-123)` vs `Yua Aida - Demosaiced (ONED-123)`.
  Bite-sized, motivating, matches existing curation rhythm.
- **Per volume** — same title in `queue/` and already in an actress folder on
  the same volume. Natural after reintegrating a cold-storage volume.
- **Whole collection** — cross-volume, including mislabels. The occasional
  big sweep.

The two that matter most for POC entry: **"clean up X's folder"** and
**"find all obvious duplicates."** These are near-peers, not primary/secondary;
long-term the screen supports both as top-level entry modes. The POC wires
one mode (probably per-actress) but shouldn't paint itself into a corner where
the other can't be slotted in.

### The atomic unit is the title

Every copy is — logically — a path of the same title. Duplicates are just a
title that has more paths than it should.

This unifies two cases that used to look separate:

- **Same-row multi-location** (easy): the title row already has N locations.
  Decisions are per-location: `KEEP canonical`, `TRASH`, `VARIANT`.
- **Cross-row duplicates** (hard — `ONED-01` vs `ONED-001`, typo'd name, wrong
  label): two *title rows* that should be one. The primary action is
  **MERGE** — collapse the rows into one title with all the locations
  combined. After merge, it's just the easy case with more copies.

That separates detection surfaces:

- Same-row detection → produces **triage candidates**.
- Cross-row / fuzzy / normalization detection → produces **merge candidates**.

MERGE is a first-class action, not a subclass of triage. The POC likely
doesn't build merge yet, but the frame matters — we don't want to paint the
UI around triage-only and then retrofit.

### The destructive action

- Move the selected copy's folder to the app's `_trash` area on the same
  volume. (Naming convention uses a leading underscore.)
- Remove that location from the title (drop the `title_locations` row).
- Needs SMB write capability, which doesn't yet exist in
  `VolumeFileSystem`. That's a real prerequisite, not a POC blocker.
- Emptying `_trash` is a manual filesystem operation for now. A
  "trash compactor" utility screen is imaginable much later — not a
  priority, not on any roadmap.

### Execute is async, queued, cancellable

Users mark decisions during a session and batch-execute. Execution is a
background task — non-blocking, retryable, cancellable — so the user keeps
working while the queue drains. This maps cleanly onto the task
infrastructure already shipped: atomic lock, `TaskIO`, cancellation,
task-pill progress, SSE events.

Implication for the screen: a persistent **pending queue counter** that
reads as forward motion ("14 actions queued"), not backlog.

### Favoring / suggested canonical

Duplicates are often *asymmetric* — one copy is objectively better. Signals:

- **Resolution** (4K > 1080p > 720p > SD)
- **Codec / container** (HEVC/H.264 in MP4/MKV > MPEG > AVI/WMV). The old-AVI
  case is a broader pattern: some users keep duplicates deliberately to
  replace outdated codecs, but the replacement gets "lost" and both persist.
- **Integrity** — a copy might exist specifically to replace a corrupted
  file. We don't yet track corruption status; manual demote in the UI is
  fine for POC.
- **File size** as a tiebreaker (bigger within same codec ≈ higher bitrate).

When the signals point clearly, the screen proposes a canonical with visible
rationale ("1080p HEVC 4.2 GB vs 720×480 AVI 1.1 GB"). When they don't, no
default is proposed and the user picks.

### Invariant: at least one physical copy always remains

A title must never end up with zero locations through triage action. The last
`KEEP`-worthy copy cannot be marked `TRASH`.

Enforced at two points:

- **UI-side** (good feedback): trash button for the last non-trashed copy in a
  group is disabled with a tooltip explaining why.
- **Server-side** (correctness): the execute task re-checks before dropping a
  `title_locations` row — if it would leave the title with zero, it fails
  that action with a clear error rather than silently succeeding. Prevents
  stale client state from violating the invariant.

This also means the sync auto-prune we shipped earlier stays honest — it only
drops titles that already have zero locations for other reasons (files gone
from NAS), not because triage landed them there.

### Closure: what "done with an actress" means

Actress is complete when every duplicate group under her has received a
non-skip decision (`KEEP + TRASH` picks applied, or `VARIANT` declared, or
`MERGE` queued). Skipped groups keep her status as *in progress*.

We don't require the pending queue to be drained for her to show as done —
the decision is the meaningful work; execute is a separate mechanical step
that happens at the user's cadence. This bar is provisional; revisit after
POC testing to see if it matches the actual feel of closure.

### The feeling we're designing for: cleanup satisfaction

The library is large, under-curated, and the reason this app exists. Duplicate
triage is at the core of that curation work. The screen's job is not just to
surface data — it's to make the user *feel* the pile shrinking.

That's a first-class design input, not garnish. It shapes:

- **Prominent progress**. Headline counts — "3,433 found · 127 cleaned ·
  3,306 remaining" — visible at the top. The number going down is the
  payoff.
- **Completable units**. Per-actress closure: "✓ Yua Aida — all
  duplicates resolved." Actress is a natural chunk; finishing one is a small
  win. String wins together and the session feels productive.
- **Queue as momentum, not burden**. "14 actions queued" reads as "you're
  moving," not "you have unfinished work." The draining animation during
  execute reinforces that.
- **Copy matters**. "Queue cleanup" beats "Pending deletions." "Done with
  Yua Aida" beats "No unreviewed duplicates remaining." Tone throughout
  should be forward-looking.
- **No scolding**. The screen never says things like "you have 3,433
  unresolved issues." It says things like "3,433 candidates to review — let's
  go."

POC emphasis: the comparison grid matters, but the **progress + closure**
signals matter more. That's what we want to test the feel of.

### Metadata on a "keep both" decision

Unknown — user doesn't have strong intuition yet. Working hypothesis: minimal
reviewed flag + optional freeform note ("4K remaster replacing original"). If
patterns emerge during POC use, we'll formalize. No variant/edition ontology
until we see it's needed.

## POC approach

### Premise

The POC's value is **the conversation it unlocks**. Every hour spent on
infrastructure is an hour not spent learning whether the shape is right. Code
at this stage is a conversation tool, not an artifact — optimize for feedback
speed, not for correctness.

### Scope (the cheapest thing that lets us see the shape)

- **No backend changes if possible.** Existing endpoints already return:
  titles with >1 location (`/api/tools/duplicates`), per-location videos with
  size/resolution/codec/duration (`/api/titles/{code}/videos?volumeId=X`).
  That is everything the comparison grid needs.
- **Single-file frontend module**, replacing or alongside the current
  Duplicates tile binding. ~300 lines of disposable code.
- **No persistence.** Decisions paint UI state only. Refresh wipes. This is
  fine — we're testing the shape, not storing real work.
- **No Execute step.** Action buttons highlight what *would* happen; nothing
  hits the DB or filesystem. No task-center integration, no atomic-lock, no
  visualize-then-confirm.
- **Start without the scope picker.** Per-actress dropdown populated from
  existing actress APIs. If scope feels wrong, we add it next iteration.
- **The ranker is the one thing worth getting right** — it's a pure
  function that carries forward to any MVP and is cheap to unit-test.
- **Cover thumbnails are in the grid.** A mis-labeled folder shows the
  wrong cover — that's diagnostic info the user needs at a glance. Uses
  existing `/covers/{label}/{code}.jpg` route; zero backend work.
- **Replaces the existing Duplicates tile.** The old tile was a dead-end
  list; we don't leave it around as an A/B. Tile wiring in `action.js`
  repoints to the new module; the old `duplicatesView` / `duplicatesFilters`
  DOM stays in `index.html` until the POC solidifies, then gets removed.

### What we learn

Things the POC puts in front of us so we can react:

- Does the screen *feel* like cleanup satisfaction, or like data entry?
  This is the primary question — everything else is downstream.
- Is the progress headline the right payoff signal, or too quiet / too
  loud?
- Does per-actress closure ("done with Yua Aida") actually read as a win?
- Is walking group-by-group the right rhythm?
- Does the ranker pick the right copy most of the time, or is the rationale
  misleading when it's wrong?
- Is `VARIANT` a real third option alongside `KEEP / TRASH`, or does it
  collapse in practice?
- What signals in the comparison grid are irrelevant? What's missing?
- Does the same-title-id grouping match how the user thinks about
  duplicates, or is it too narrow? (The cross-row / merge case isn't in the
  POC, but the absence of it may itself be informative.)

### What we deliberately don't learn yet

- Whether the SMB move UX feels right — Phase 2 problem.
- Whether fuzzy / cross-row detection finds the right cases — Phase 3
  problem.
- Whether the task-pill + atomic-lock model fits for execute — Phase-MVP
  problem.
- How decisions should persist across sessions — depends on what we learn
  above.

## POC → MVP transition (2026-04-22)

POC is complete on branch `utilities-duplicate-triage` (commit `b180f3e`).
The UX shape validated in the POC:

- Per-actress grouping with alpha filter + name/count sort
- Comparison grid with ranker-suggested canonical and visible rationale
- KEEP / TRASH / VARIANT decisions per location; auto-keep last survivor
- Closure per actress ("✓ Yua Aida — all duplicates resolved")
- Percentage-pie resolution state on each actress tile in the sidebar
- Inspect modal with HTML5 player + metadata for drill-in verification
- Identical-copies detection across resolution · codec · size · file count · container

What the POC deliberately left out, now Phase 2 scope: persistence, execute
(SMB writes + trash), server-side invariant re-check, queue counter UX.

## Phase 2 — MVP buildout plan

### Infrastructure correction

The POC memory and prior draft both flagged "introduce
`VolumeFileSystem.moveFolder`" as a prerequisite. **This is stale.**
`SmbFileSystem.move(source, destination)` (SmbFileSystem.java:161) already
handles directory rename over SMB with the correct SMB2 `FILE_DIRECTORY_FILE`
flag and minimal ACL rights (`DELETE + FILE_READ_ATTRIBUTES`). Phase 2 uses
the existing API; no new filesystem primitive is required.

### Sequencing (four independent, shippable sub-phases)

The phases are ordered so each produces standalone value and has a clean test
surface. The project remains demoable after each.

#### Phase 2A — Persistence

Decisions currently evaporate on refresh. This sub-phase stores them so the
UI can reload the same state a user left.

- **Schema migration v22.** New table:
  ```
  duplicate_decisions (
    title_code   TEXT    NOT NULL,
    volume_id    TEXT    NOT NULL REFERENCES volumes(id),
    nas_path     TEXT    NOT NULL,
    decision     TEXT    NOT NULL CHECK(decision IN ('KEEP','TRASH','VARIANT')),
    note         TEXT,
    created_at   TEXT    NOT NULL,
    executed_at  TEXT,                    -- set when Phase 2C finalizes a TRASH
    PRIMARY KEY (title_code, volume_id, nas_path)
  )
  ```
  Separate from `title_locations` because: (a) decisions are *intent*, not
  authoritative state; (b) a TRASH decision must outlive the `title_locations`
  row it removed (audit trail / undo window); (c) sync logic stays clean —
  decisions never participate in location dedup.
- **`DuplicateDecisionRepository`** (interface + jdbi impl). CRUD plus
  `listForTitle(titleCode)` and `listPending()` (decisions where
  `executed_at IS NULL`).
- **REST surface** (under `/api/tools/duplicates/decisions`):
  - `GET /api/tools/duplicates/decisions` — all pending, for initial UI load
  - `PUT /api/tools/duplicates/decisions` — upsert a single decision; body
    `{titleCode, volumeId, nasPath, decision, note?}`
  - `DELETE /api/tools/duplicates/decisions/{titleCode}/{volumeId}` — path
    param uses encoded nas_path or just (titleCode, volumeId) — TBD in
    implementation
- **UI wiring**: `loadAll()` fetches pending decisions and hydrates the
  `decisions` Map before first render; `setDecision()` fires the upsert/delete
  alongside its UI update. Failure handling: toast + retry, but UI state
  wins (optimistic).

**User-visible payoff:** decisions survive reload and can be resumed across
sessions. No execute yet.

#### Phase 2B — Trash primitive contract update

Infrastructure-only; no user-visible change.

**Good news**: the trash primitive (`src/main/java/com/organizer3/trash/Trash.java`)
already exists with mirrored-path layout, atomic SMB move, and JSON sidecar
writing. Config is already declared per-server in `organizer-config.yaml`
(`trash: _trash`). `TrashPathService` is effectively
`Trash.mirrorUnderTrash()` — no new service needed.

**What changes**:

- **Rename sidecar field `entityType` → `reason`** to match the updated
  global trash contract (`spec/PROPOSAL_TRASH.md` §5). `reason` is
  free-form app-provided text explaining *why* the item was trashed,
  not a structured type label. The four required fields are now
  `originalPath`, `trashedAt`, `volumeId`, `reason`.
- **Update `Trash.trashItem` signature**: replace the `String entityType`
  parameter with `String reason`; callers pass a human-readable
  explanation.
- **Update existing call sites**: `TrashDuplicateVideoTool` and
  `TrashDuplicateCoverTool` — these are greenfield MCP scaffolding
  with no production consumers yet, so the change is safe.
- **Tests**: update `TrashTest.java` for the new field name and
  assertions; verify all four required fields are always present.

#### Phase 2C — Execute task

The destructive piece. Runs as a background task through the existing
TaskRunner atomic lock, so it participates in the same UX as other
Utilities tasks.

- **`ExecuteDuplicateTrashTask`** (`utilities.task.duplicates`):
  1. `phaseStart("plan", "Resolve queued trash actions")` — load all
     decisions where `decision='TRASH' AND executed_at IS NULL`, optionally
     filtered by the `actressKey` task input (empty = all). Compute
     destination paths via `TrashPathService`. One phase-log line per
     planned move.
  2. For each decision (one phase each so failures don't block siblings):
     - **Server-side invariant re-check**: re-query
       `title_locations` for the title. If trashing this row would leave
       zero locations, fail that phase with
       `"Skipped: would leave {code} with zero locations"`. This is the
       primary safety gate — stale client state cannot violate the
       invariant.
     - Call `trash.trashItem(sourcePath, reason)` where `reason` is
       built from the ranker rationale when available, falling back to
       `"Duplicate Triage — kept peer on volume {id}"` when not. The
       primitive handles mirrored-path creation, atomic SMB move, and
       sidecar write (with best-effort sidecar semantics — failure
       logs a warning but doesn't roll back the move).
     - Drop the `title_locations` row for (title_code, volume_id, nas_path).
     - Stamp `duplicate_decisions.executed_at = now()`.
  3. Summary phase: `N executed · M skipped (invariant) · K failed (I/O)`.
- **Task inputs**: optional `actressKey` (STRING, `required=false`) — when
  present, restricts execution to that actress's pending TRASH decisions.
  Empty/absent → batch-all.
- **REST surface**: `POST /api/utilities/tasks/duplicates.execute_trash/run`
  — body `{}` for batch, `{"actressKey":"id:123"}` for per-actress. Uses
  the existing task run endpoint — no new route needed.
- **Registered in `TaskRegistry`** alongside backup/sync/etc.
- **Never applies VARIANT or KEEP decisions** — those are metadata-only
  and never move files. (VARIANT might later write a `reviewed` flag;
  out of scope for MVP.)

#### Phase 2D — UI integration

The Phase 2A/B/C work ships user-visible behavior only if the screen
surfaces it.

- **Pending queue counter** in the headline row: `"14 actions queued"`
  badge next to `"found · cleaned · remaining"`. Forward-motion copy per
  spec §The feeling we're designing for.
- **Global execute button** next to the counter: "Execute all (14)".
  Disabled when queue is empty. Disabled + tooltip when any other
  Utilities task is running (read `TaskRunner.currentlyRunning()` via
  existing task-pill mechanism).
- **Per-actress execute button** on the closure/progress region of each
  actress card in the sidebar, visible only when that actress has ≥1
  pending TRASH decision. Label: "Execute (3)". Clicking posts with
  `actressKey` so only her queue drains.
- **Task-pill integration**: re-use the existing pill for progress. SSE
  events drive phase updates and the queue counter drains live.
- **Post-execute reconcile**: after task ends OK/PARTIAL, re-fetch
  duplicates (titles with zero or one location disappear from the list)
  and pending decisions. Failed/skipped phases surface as a dismissable
  banner inline with the affected title card.

## Design decisions (resolved 2026-04-22)

1. **Trash layout: mirrored source path.** A trashed folder lands at
   `_trash/{original-relative-path}/`. Example:
   `//pandora/jav_A/stars/library/Yua Aida/ONED-123` →
   `//pandora/jav_A/_trash/stars/library/Yua Aida/ONED-123`.
   Collisions handled by appending `__{timestamp}` only when a conflict
   would occur (rare in practice — most collisions would require the same
   title in the same actress folder to be trashed twice).
2. **Sidecar metadata file.** Reuses the global trash contract defined
   in `spec/PROPOSAL_TRASH.md`. Every trashed folder carries an
   `ITEM.json` sidecar alongside it (mirrored path under `_trash/`) with
   the four required fields: `originalPath`, `trashedAt`, `volumeId`,
   `reason`. Duplicate triage fills `reason` with `"Duplicate Triage — {ranker rationale}"`,
   falling back to `"Duplicate Triage — kept peer on volume {id}"` when
   no rationale is available.

   The DB `duplicate_decisions` row serves the *active triage session*
   only. It and the sidecar are written together during execute and are
   allowed to diverge afterward (sidecar survives even if the DB is
   lost/restored; DB survives even if the volume is offline).
3. **Execute scope: both.** The UI offers two execute affordances:
   - **Per-actress**: a button on the closure banner (or near the
     actress's pending count in the sidebar) — "Execute for Yua Aida
     (3 queued)". Matches the per-actress rhythm.
   - **Global batch**: a button in the headline — "Execute all N queued".
     Matches the batch-execute spec language.
   Both use the same task; the scope filter is a task input (`actressKey`
   optional; empty = all).
4. **VARIANT note: deferred.** v22 does not include a `note` column. If
   POC/MVP use surfaces a real need, v23 adds it.
5. **Re-scan resilience: leave as-is.** Existing decisions stay; new
   locations show undecided; the title returns to "in progress" for
   that actress. No decision invalidation on sync.
6. **Trash on unstructured volumes: same convention.** `_trash/` at the
   volume root, mirrored-path semantics. For a `queue/TITLE/` folder, the
   mirrored trash path is `_trash/queue/TITLE/`.
7. **Decision cleanup: never prune.** Rows stay forever. The future
   Trash Management screen operates on sidecar files, not the DB.

## Non-goals (regardless of phase)

- Physical file deletion on a NAS (move-to-`_trash/` only; the user does
  real disposal at their own cadence).
- Auto-merging title rows — Phase 3 concern.
- Automatic decisions without user review (except the ranker's *suggestion*,
  which is always user-confirmable).
- Cross-row detection surface (`ONED-01` vs `ONED-001`) — Phase 3.
- Corruption / codec modernization — Phase 4.

## Long-arc (post-MVP)

- **Phase 3: merge candidates + layered detection.** The cross-row case —
  `ONED-01` vs `ONED-001`, typo'd names, wrong labels — produces *merge
  candidates*, not triage candidates. A separate surface (or a separate
  section within this screen) walks the user through
  "these two title rows are actually one," with merge as the confirm action.
  Detection feeds it: code normalization, folder-name variant suffix
  capture, fuzzy name/code matching, size+duration fingerprinting, optional
  content hash. Each a distinct confidence tier. Once merged, locations
  collapse onto the surviving title and the ordinary triage flow applies.
- **Phase 4: adjacent workflows.** Corruption auto-detect from ffprobe
  warnings during sync; an AVI/WMV modernization tool (related but separate
  from dedup).

## Non-goals (regardless of phase)

- Physical file deletion on a NAS (move-to-`trash/` only; the user does
  real disposal at their own cadence).
- Auto-merging title rows.
- Automatic decisions without user review (except the ranker's *suggestion*,
  which is always user-confirmable).
