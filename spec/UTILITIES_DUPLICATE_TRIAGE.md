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

## Long-arc orientation (post-POC, subject to change)

Sketched only so POC choices don't corner future phases. Concrete design
comes *after* POC feedback, when the actual shape is visible.

- **Phase 2: SMB writes + trash.** Introduce `VolumeFileSystem.moveFolder`,
  declare `trash/` partitions per volume, wire real destructive execute.
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

## Open questions (parked)

- Ordering within a scope — by confidence? by code? by actress alphabetical?
- Can decisions be revoked? How does a re-scan surface a newly added third
  copy when the first two have decisions?
- Per-location vs per-group decision grain?
- Default scope on open?
- Do we treat cross-row duplicates as a separate product surface (a
  "suspicious-pairs" review) from same-row duplicates (this screen), or as a
  unified queue?

These do not need answers before the POC. They inform what to look at
during feedback.

## Non-goals (regardless of phase)

- Physical file deletion on a NAS (move-to-`trash/` only; the user does
  real disposal at their own cadence).
- Auto-merging title rows.
- Automatic decisions without user review (except the ranker's *suggestion*,
  which is always user-confirmable).
