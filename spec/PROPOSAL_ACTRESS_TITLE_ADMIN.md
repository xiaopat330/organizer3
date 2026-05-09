# Actress Title Admin — per-actress title management surface

> **Status: DRAFT v3** — drafted 2026-05-08. Owner: Patrick. v3 folds in
> the five Phase-blocking clarifications (C1–C5) on top of v2's resolved
> open questions (§7).
>
> Lives in the new `Admin` tab on the Actress Detail right panel (the tab
> shell shipped on `feature/actress-title-management`, commits `5516e32`
> and `fd439e4`). Catalog tab is unchanged; this proposal is entirely
> about what fills the Admin tab.
>
> Companion / supersedes-in-scope: existing global Tools features
> (`Curation → Duplicate Triage`, `analyze_title_videos` MCP tool,
> `trash_duplicate_video` / `trash_duplicate_cover`) — the Admin tab
> exposes per-actress, per-title slices of those same operations. The
> global tools remain; this is an additional, more focused surface.

---

## 1. Goal

When the user opens the Actress Detail screen, they're either browsing
the actress's catalog (existing `Catalog` tab) or they want to **clean
up and curate** that actress's titles. Today, curation is only available
through the global Tools screens — which are library-wide and not
actress-scoped, forcing the user to filter or context-switch.

The Admin tab is the actress-centric workbench: a paginated list of
**large per-title "Edit Cards,"** each card surfacing the most common
maintenance actions for *that one title* (toggle flags, resolve
duplicate folders, clean the file-system contents of the folder).

**User-visible loop:** open an actress → switch to Admin → page through
her titles → on any card with something to fix, fix it in place →
commit → next title.

---

## 2. Non-goals

- **No bulk operations.** Every action is scoped to a single title. No
  multi-select, no "trash all duplicates for this actress," no batch
  reject. The scope discipline is part of the feature — bulk lives in
  the global tools.
- **No full title metadata editor.** MVP edit controls are limited to
  the flag toggles below. The existing title editor / title detail view
  remains the surface for richer edits.
- **No actress-scoped enrichment / translation re-runs.** Those stay in
  the global Tools surfaces.
- **No "manage rejected titles" workflow** in this proposal — the reject
  toggle is introduced here, but the management/cleanup view for
  rejected titles is a separate, later feature (§9).

---

## 3. Layout

```
┌─ Actress Detail right panel ───────────────────────────────────────┐
│ [ Catalog ] [ Admin* ]                                              │
├─────────────────────────────────────────────────────────────────────┤
│ ┌─ Edit Card: ABC-123 ─────────────────────── [pending: 2 edits] ┐ │
│ │ ┌─ cover ──┐ Title: 高画質 ABC-123 ...                          │ │
│ │ │          │ Studio: S1 / Label: SOE  Released: 2014-06-01    │ │
│ │ │  [img]   │ Cast: 麻美 ゆま, 紗倉 まな                          │ │
│ │ │          │ Tags: ...   Grade: A   Age@release: 24            │ │
│ │ └──────────┘                                                   │ │
│ │ Flags:  [♥ Favorite*]  [🔖 Bookmark]  [⨯ Reject]                │ │
│ │                                                                 │ │
│ │ ── Duplicate folders (only if locations.size > 1) ───────────  │ │
│ │   [embed of duplicate-triage block, scoped to this title]     │ │
│ │                                                                 │ │
│ │ ── Folder contents (only if locations.size == 1) ────────────  │ │
│ │   /qnap_video/.../ABC-123/                                     │ │
│ │     videos: ABC-123.mp4   1080p   3.2 GB   1h 58m             │ │
│ │             ABC-123_2.mp4 1080p   3.1 GB   1h 57m   [trash*]  │ │
│ │     covers: ABC-123.jpg   1920×1080  280 KB                    │ │
│ │             cover.jpg     1920×1080  280 KB         [trash]   │ │
│ │     [normalize filenames…]                                     │ │
│ │                                                                 │ │
│ │ ─────────────────────── [Cancel] [Commit 2 changes] ─────────  │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                                                                     │
│ ┌─ Edit Card: ABC-124 ──────────────────────────────────────────┐  │
│ │ ...                                                            │  │
│ └────────────────────────────────────────────────────────────────┘  │
│                                                                     │
│ Pagination:  [⏮ first] [‹ prev 10] page [_3_] of 7 [next 10 ›] [last ⏭] │
└─────────────────────────────────────────────────────────────────────┘
```

**Asterisks** mark staged-but-not-yet-committed edits (see §4.5).

**Ordering: needs-attention-first, always.** No filter or sort controls
on this tab — the screen is for management, not exploration. The
backend computes an attention score per title and orders the actress's
full title list descending by it; titles with no attention signal fall
to the back in default order (release date desc as tiebreaker).
Score signals (rough sketch, finalize during Phase 2):
- **No-content title (folder empty or no video files)** +highest
- Multi-location title (duplicate folders)              +high
- Folder anomalies (misfiled cover, unprocessed file)   +med
- Unprobed videos                                       +low
- Otherwise                                             +0

No-content sits at the top because it's the only state the user can't
fix from this screen — surfacing it first makes the corner case
visible without forcing the user to page through the whole catalog.

**Pagination control:** `[first] [prev 10] page [N] of M [next 10] [last]`.
The page-jump input is a number field; submitting jumps to that page.
Page numbers are 1-indexed.

**Pagination over endless scroll** is intentional. The cards are tall;
rendering all of an actress's titles at once would be wasteful, and
pagination matches the discrete "review one title at a time" rhythm.

**Page size:** default **5**, configurable via `organizer-config.yaml`:

```yaml
actressTitleAdmin:
  pageSize: 5
```

(Exact key location/spelling finalized in Phase 2.)

---

## 4. The Edit Card

A card has up to **four** stacked sections, top to bottom:

### 4.1 Header — Title metadata (always)
Mirror the visual feel of the existing Title Detail screen: cover
image, code, full title, studio/label, release date, cast (linked),
tags, grade badge, age-at-release pill. Most of this content already
has render helpers in the codebase (see `cards.js`, `details.css`
`.grade-badge`, `.age-pill`); reuse rather than re-skin.

### 4.2 Flags row (always)
Three toggle buttons:
- **Favorite** — existing `POST /api/titles/{code}/favorite`
- **Bookmark** — existing `POST /api/titles/{code}/bookmark`
- **Reject** — **new** `POST /api/titles/{code}/reject` (§5)

Mutual exclusion mirrors actresses: setting Reject auto-clears
Favorite + Bookmark; setting Favorite or Bookmark on a rejected title
is blocked client-side (button disabled with tooltip) and refused
server-side (400). `ActressBrowseService.toggleRejected` is the
reference for the rule.

In this card, clicking a flag **stages** the change (visually marks
the toggle as "pending") — it doesn't fire until the user clicks
**Commit** (§4.5).

### 4.3 Duplicate folders (conditional: `title.locations.size() > 1`)
Embed the per-title slice of the existing Duplicate Triage UI. For
each location, show: volume, partition, NAS path, video count + total
bytes, and the action set already supported by the global tool —
**Keep / Trash / Variant** (mapped to `DuplicateDecision.decision`).

Backend: new endpoint **`GET /api/titles/{code}/duplicate-decisions`**
returns just this title's decisions (decided in §7.A=1). PUT/DELETE
continue to use the existing global routes
(`DuplicateDecisionsRoutes.java:42-102`) keyed by `(titleCode,
volumeId, nasPath)`.

Decision changes here are **staged**, like flag toggles — committed
on the card's Commit button.

### 4.4 Folder contents (conditional: `title.locations.size() == 1`)
**Always shown** for single-location titles, even when the folder is
fully canonical and there's nothing to fix. The display is
informative as well as actionable — users want to see what's actually
on disk for the title without leaving the screen.

A two-list view of what's actually on disk for this title's one folder:
- **Videos** — filename, container, resolution, codec, duration, size.
  Each row has a `[trash]` button.
- **Cover images** — filename, dimensions, size. Each row has a
  `[trash]` button.

#### Multi-cover handling
A title should have **exactly one** cover image. When the folder has
more than one, the cover section displays a notice:

```
covers
  ⚠ Multiple covers detected — keep one, trash the rest.

  ABC-125.jpg         1920×1080    280 KB    [keep] [trash]
  cover (1).jpg       1920×1080    278 KB    [keep] [trash]●
  cover_alt.jpg       1280×720     180 KB    [keep] [trash]●
```

`[keep]` is a one-click affordance: clicking it stages trash on every
*other* cover row in the folder. It's a shortcut, not a new operation
— under the hood it's just N row-trash stages. The user can also
trash individual rows manually via the per-row `[trash]` buttons.

If the folder has zero covers, no notice — covers are technically
optional (per memory: 99% present, but not required), and the absence
isn't an error worth surfacing.

#### Folder-level actions
- **Normalize folder…** — opens the unified normalize modal (§4.4.1).
  Covers both filename normalization (rename to canonical) and layout
  restructure (move misplaced files into the conventional layout).
  Always shown; the modal will be empty / disabled-confirm if the
  folder is already canonical.

If a title has zero locations (orphan in DB), show neither §4.3 nor
§4.4 — render a small warning instead with a link to the existing
orphan triage tool.

Backend: a new shared service layer extracted from the existing MCP
tools (decided in §7.B=2):
- `TitleFolderService.listContents(titleCode)` — returns videos +
  covers for the title's single location. Both the new HTTP route
  `GET /api/titles/{code}/folder-contents` and the existing
  `analyze_title_videos` MCP tool become thin adapters over this
  service.
- `TitleFolderService.trashVideo(titleCode, filename)` —
  `trash_duplicate_video` becomes a thin adapter; new HTTP route
  `POST /api/titles/{code}/videos/{filename}/trash`.
- `TitleFolderService.trashCover(titleCode, filename)` — same shape
  for `trash_duplicate_cover`.
- `TitleFolderService.proposeNormalization(titleCode)` — returns the
  suggested set of file moves (rename + relocate) needed to bring this
  folder to canonical layout. Drives the modal pre-fill (§4.4.1).
- `TitleFolderService.applyMoves(titleCode, [{from, to}, …])` —
  executes the user-confirmed `from → to` set as a sequence of
  intra-volume atomic moves (per CLAUDE.md). Used both for filename
  renames and layout restructures (one operation, since `from → to`
  is fully general — change the basename, change the path, or both).
  The existing `restructure_title` MCP tool becomes a thin adapter
  over the same call.

Trash and normalize actions inside the card are **staged** until Commit.

#### 4.4.1 Normalize folder modal (decided in §7.C=2; expanded for layout)
A unified modal — never silently moves files. Covers both the rename
case ("file is in the right place but has the wrong name") and the
restructure case ("file has the right name but is in the wrong
folder"), and the both-at-once case, in a single review step.

Pre-fill is computed by `TitleFolderService.proposeNormalization`:
both filename canonicalization (Phase 5 — see §5.A below) and layout
normalization (cover at base, videos in `video/` subfolder per memory
"Physical layout of a title folder") in the same proposal.

The modal shows two grouped sections:

```
Normalize folder:  /qnap_video/stars/superstar/麻美ゆま/ABC-125/

  RENAMES                                              keep │ target
   ABC-125 (1).mkv                                     [_]  │ ABC-125.mkv
   cover (final).jpg                                   [_]  │ ABC-125.jpg

  MOVES                                                keep │ target
   cover.jpg                              (at base)    [_]  │ (no move)
   ABC-125.mp4                            (at base)    [_]  │ video/ABC-125.mp4
   trailer.mp4                            (at base)    [_]  │ video/trailer.mp4
   subs/cover.jpg                         (subfolder)  [_]  │ ABC-125_alt.jpg

                                  [ Cancel ]   [ Stage 4 changes ]
```

Behavior:
- For a **single-video folder**, the rename pre-fill is the canonical
  `{CODE}.{ext}` — one-click confirm.
- For a **multi-file folder**, the rename pre-fill is the system's
  **best guess** (e.g. `{CODE}_disc1.{ext}` from filename hints), but
  the user **must explicitly review and accept each target name**.
  No silent disc-set renaming.
- For the **moves** section, the pre-fill is the canonical layout:
  videos under `video/`, covers at base. User can override any target
  path or uncheck the "keep" box to skip a particular move.
- An already-canonical row may still appear with `(no move)` /
  `(canonical)` marker so the user sees the system reasoned about it
  and chose not to act — no confusion about silent skips.
- Confirm validates: no target path collisions (within the staged set
  or against existing untouched files), all targets legal, all moves
  intra-volume (always true per CLAUDE.md, but asserted defensively).

Confirming the modal **stages** all the moves on the card; they're
applied on Commit as one ordered sequence (renames before path moves
to avoid path-then-rename two-step on the same file). All moves go
through the same `TitleFolderService.applyMoves` call.

If the folder is already fully canonical, the modal opens in an
**informational state** — both sections empty, with a single line
"Folder is already canonical." and the Stage button disabled. Keeps
the entry point predictable (button always available) without forcing
busywork.

**Modal sees the staged-state view.** When other file-level edits are
already staged on the card (e.g. a per-row trash on
`ABC-125_(2).mp4`), the modal builds its proposal as if those staged
edits had already happened — the trashed video is excluded from
rename proposals, the canonical-name slot it would have occupied is
free for the keeper, etc. The modal preview always matches what
Commit will actually do. Implementation: backend
`proposeNormalization` produces the *initial* card-render proposal;
once any file-level edits are staged, the modal recomputes its
proposal client-side from `(server folder state) + (staged
trashes/renames so far)`.

### 4.5 Card Commit / Cancel (always, when there are staged changes)
A footer row appears on cards with at least one staged edit:
- **Commit N changes** button — fires all staged actions in order to
  the appropriate endpoints. Behavior on partial failure: see below.
- **Cancel** button — **silently** discards all staged edits and
  returns the card to its server state. No confirm modal — a button
  labeled Cancel should just cancel.

A small per-card pending counter ("pending: N edits") shows in the
card header so the staged state is visible even if the footer scrolls
out of view.

**Navigate-away protection.** If the user attempts to leave the
current page (paginate, switch to Catalog tab, open Title Detail,
browser back) while *any* card on the current page has staged edits,
show a confirm modal: "You have N staged changes on this page.
Discard?" with **Discard** / **Stay** buttons. Discarding cancels all
staged state; staying aborts the navigation.

**Order of execution on Commit (Phase 2 default; revisit in Phase 4):**
1. Flag toggles (favorite, bookmark, reject).
2. Duplicate decisions (PUT/DELETE per location).
3. Folder-content actions (trash files, then rename/move files).

This ordering avoids the worst-case "trash a file and then try to set
favorite on a now-orphan title" sequence.

**Stage status model.** Each staged edit carries a status:
- `pending` (default) — not yet sent to the server.
- `committed` — sent and succeeded; cleared from the card on its next
  refresh.
- `failed` — sent and the server returned an error; stays on the card
  with the error text inline.

**Atomicity:** the card commit is **best-effort sequential**, not
transactional — each underlying call commits independently. On a
failure, the iterator **stops** (later steps stay `pending`, never
fired); successful steps remain `committed`. The user sees:
- the failed step rendered in red with the server's error inline,
- the still-`pending` steps preserved in their staged form,
- a `[ retry remaining ]` button alongside Commit (functionally the
  same — just relabeled when there are failures).

The user fixes the failed stage (cancel it, or change its inputs and
re-stage) and re-Commits to fire the rest. The no-bulk rule means
this footgun is bounded to one title at a time.

**Refresh after Commit (full success or partial).** Re-fetch **just
this card's data** from the server; do **not** re-fetch the page or
re-sort the list. The page becomes a stable snapshot of its original
attention ordering until the user navigates (paginate or refresh).
Stable positions while working is preferred over re-sorting under the
user's feet — a card whose attention score dropped after Commit stays
where it was, and the user moves on. Pagination naturally re-evaluates
ordering on the next page fetch.

### 4.6 No-content card mode
Some title folders are unrecoverable from this screen:
- **Empty folder** — directory exists but contains nothing.
- **Cover-only** — directory has cover image(s) but **no video files**.
  (Cover images alone aren't a viable title — content is the videos.)

The Admin tab does **not** offer cleanup for these cases — that's a
future global Tools feature, where the user can review all such titles
across the library and decide whether to drop the DB rows / delete
the folder remnants. (Out of scope here, see §9.)

This screen surfaces the corner case so the user is aware of it:

- The Edit Card **still renders** in normal sort position. (Attention
  score puts it at the very top — see §3.)
- Card body is **greyed out** with a clear status banner:
  - Empty folder: `⚠ NO CONTENT — folder is empty`
  - Cover-only:  `⚠ NO CONTENT — no video files (cover only)`
- Sections §4.3 and §4.4 are **suppressed** entirely (no duplicate
  decisions to make on a single-location no-content title; no folder
  contents to manage).
- Flag row: **Reject is enabled**; Favorite and Bookmark are disabled
  (greyed with tooltip "title has no content"). This lets the user
  flag the title for the eventual cleanup tool right where they
  noticed the problem.
- Card footer: only the Commit/Cancel pair appears, only if the
  Reject toggle is staged.

A small `[ what's this? ]` link in the banner expands an inline
explanation: "This title's folder is empty / has no video files. The
Admin tab can't fix this — use Tools → (future cleanup tool) to
review and clean up no-content titles across the library."

**Detection:** `TitleFolderService.listContents(code)` returns the
existing video + cover lists; the card decides "no-content" when
`videos.size() == 0`. Empty-folder vs cover-only is a display
distinction; both go through the same code path.

This mode applies only when `title.locations.size() == 1`. A title
with **multiple** locations where one is empty stays in normal mode
(§4.3 duplicate-decisions handles it — e.g. Trash the empty
location).

### 4.7 Rejected card mode
Rejected titles **stay visible** on the Admin tab — they're not
filtered out — but the card body is greyed and all sections except
the Reject toggle are suppressed. Mirrors §4.6 no-content mode in
shape; the rationale is identical: "no need to edit a reject."

Card layout in this mode:
- Header (§4.1) renders normally but with the cover dimmed and a
  `⨯ REJECTED` badge alongside the code.
- Flag row (§4.2): **Reject is enabled** (so the user can un-reject);
  Favorite and Bookmark are disabled with the "title is rejected;
  clear reject first" tooltip.
- Sections §4.3, §4.4, §4.6 are **suppressed** entirely.
- Card footer: only the Commit/Cancel pair, only if Reject was
  staged (toggling it on a not-yet-rejected title, or off on an
  already-rejected one).

Un-rejecting from this mode reverts the card to its normal mode on
the next render (after Commit + card refresh).

If a title is **both** rejected and no-content, the rejected mode
takes precedence — the no-content banner is suppressed (the user has
already acknowledged the title is on its way out; the banner adds no
information).

---

## 5. The Reject concept

Already half-built. The infrastructure exists for both actresses and
titles:

| Layer | Actress | Title |
|---|---|---|
| Schema column `rejected` | ✅ `actresses.rejected` | ✅ `titles.rejected` |
| Repo `toggleRejected` | ✅ | ✅ |
| HTTP endpoint | ✅ `POST /api/actresses/{id}/reject` | ❌ **missing** |
| UI surface | ✅ (`actress-card-rejected` class) | ❌ **missing** |
| Mutual exclusion w/ fav+bookmark | ✅ `ActressBrowseService.toggleRejected` | ❌ to be added in service layer |

**Definition (titles):** Reject = "marked for potential deletion." Not
deletion itself — the title row + on-disk content are untouched. A
later "Manage Rejects" workflow (out of scope) reviews and acts.

**Mutex semantics (titles):** identical to actresses.
- Setting `rejected = true` clears `favorite` and `bookmark`.
- While `rejected = true`, attempts to set `favorite` or `bookmark`
  return 400 with `{error: "title is rejected; clear reject first"}`.
- Setting `rejected = false` is unconditional; flag history isn't
  restored.

**API:** `POST /api/titles/{code}/reject` returns the full flag state
`{code, favorite, bookmark, rejected}` so the card can re-render all
three toggles atomically (matches the actress endpoint shape).

**Toggle locations (decided in Q3):**
- **Title Detail screen** — adds a Reject button alongside the existing
  Favorite/Bookmark buttons.
- **Admin Edit Card** — Reject is in the flags row (§4.2), staged on
  click and committed via the card's Commit button.
- **Catalog tab cards** — **no toggle.** Rejected titles still appear
  here, with a passive "rejected" visual treatment (decided in §7.E=1):
  cover dimmed and a small ribbon/badge marks rejection. To toggle,
  the user goes to the Edit Card or Title Detail.

**UI:** new `.title-card-rejected` CSS class (mirror
`.actress-card-rejected`); applied to the Edit Card and to the same
title's tile in the Catalog tab so the user sees rejection consistently
across both views.

---

## 6. Schema changes

**None.** `titles.rejected` already exists. The proposal is purely
plumbing (HTTP endpoints, service-layer extraction, mutex enforcement)
+ new UI + one new config key.

**Config key (organizer-config.yaml):**
```yaml
actressTitleAdmin:
  pageSize: 5
```

---

## 7. Resolved decisions

All open questions from v1 are answered. Recorded here for future
reviewers.

| # | Question | Decision |
|---|---|---|
| Q1 | Sort/filter controls on Admin? | **Needs-attention-first**, no filter/sort controls, full pagination control (§3). |
| Q2 | Page size? | **5**, configurable via `organizer-config.yaml` (§6). |
| Q3 | Where can Reject be toggled? | **Title Detail + Admin Edit Card.** Catalog cards: passive visual only (§5). |
| Q4 | Multi-disc memory persistence? | **Defer.** Ephemeral — re-prompt every visit. Revisit if it annoys. |
| §7.A | Per-title duplicate-decisions endpoint vs filter global? | **Per-title endpoint** (`GET /api/titles/{code}/duplicate-decisions`, §4.3). |
| §7.B | How to expose MCP-only folder tools to HTTP? | **Extract shared service layer.** New `TitleFolderService`; both MCP tools and new HTTP routes are thin adapters (§4.4, Phase 4). |
| §7.C | Normalize-filenames UX for multi-disc? | **Always-prompt modal.** Multi-file targets are **manually entered** by the user with a best-guess pre-fill — never silently renamed. **Modal also covers layout restructure** (move misplaced files into canonical layout) in the same review step (§4.4.1). |
| §7.D | Save boundary on the card? | **Card-level Commit button.** All edits stage locally; nothing hits the server until Commit (§4.5). |
| §7.E | Reject's effect on Catalog tab visuals? | **Greyed in place with ribbon.** No hide toggle (§5). |
| §7.F | Pagination: page-number or cursor? | **Page-number** (offset/limit). Drift on destructive ops mitigated with refresh + toast (§3). |
| C1 | Rejected card visibility in Admin tab? | **Visible in full reject mode.** Body greyed, sections suppressed, only Reject toggle live (§4.7). Mirrors §4.6 no-content. |
| C2a | Cancel button behavior? | **Silent discard** — no confirm modal (§4.5). |
| C2b | Navigate-away with staged edits? | **Confirm modal** — "Discard N staged changes?" with Discard / Stay (§4.5). |
| C3 | Normalize modal awareness of staged edits? | **Staged-state view** — modal recomputes proposal client-side from `(server state) + (staged edits)` (§4.4.1). |
| C4 | Refresh granularity after Commit? | **Single card only.** Page is a stable snapshot of original ordering until user paginates (§4.5). |
| C5 | Partial-Commit failure UX? | **Preserve staged state with per-stage status.** `pending` / `committed` / `failed`. Iterator stops at first failure; user fixes failed stage and re-Commits (§4.5). |

---

## 8. Phases

### Phase 1 — Reject for titles (foundation, no Admin UI yet)
- Add service-layer `TitleService.toggleRejected(code, value)` enforcing
  the mutex rules.
- Refactor `toggleFavorite` / `toggleBookmark` to refuse on rejected
  (return 400 with structured error).
- Add `POST /api/titles/{code}/reject` route, returning full flag state
  `{code, favorite, bookmark, rejected}`.
- Backfill `POST /api/titles/{code}/favorite` and `…/bookmark` to also
  return full flag state — needed so the new UI can render mutex state
  consistently. **Backwards-compat risk: low** — existing JS callers
  ignore extra fields.
- **Title Detail screen:** add the Reject button alongside Favorite/
  Bookmark, with mutex-aware enable/disable. (Per Q3, Title Detail is
  one of the two MVP toggle locations.)
- Add `.title-card-rejected` CSS class. Apply to Catalog tab cards
  (§7.E=1: greyed-in-place + ribbon).
- Tests: service-layer mutex unit tests; route integration tests for
  the new endpoint and the "refused on rejected" path.

### Phase 2 — Admin tab scaffolding + flags row + Commit button
- Read `actressTitleAdmin.pageSize` from `organizer-config.yaml`
  (default 5).
- Backend: new endpoint `GET /api/actresses/{id}/admin-titles?page=N`
  returning the page slice **ordered by attention score** (compute
  inline; don't persist scoring yet — revisit if perf needs it).
  Returns `{titles: [...], page, totalPages, pageSize}`.
- New JS module `modules/actress-detail-admin/` (directory split from
  the start, per the canonical sibling-module pattern in memory). At
  minimum: `index.js` (lifecycle + pagination), `card.js` (Edit Card
  rendering + staging logic), `commit.js` (the Commit/Cancel
  orchestration).
- Render Edit Card with §4.1 header + §4.2 flags + §4.5 Commit footer.
  Sections §4.3 and §4.4 stubbed as "Coming soon" placeholders.
- **No-content card mode (§4.6) is also implemented in this phase**,
  even though the underlying detection lives in Phase 4's
  `TitleFolderService.listContents`. Until Phase 4 ships, treat the
  detection as "if the existing video count from the title's data is
  0, render no-content mode" — refine when the service-layer call
  arrives.
- Pagination control wired (first / prev 10 / page-jump / next 10 /
  last). On destructive commits, surface a "list refreshed" toast and
  re-fetch the current page (mitigates §7.F drift).
- Lazy-render: only fetch the current page's titles.

### Phase 3 — Embedded duplicate-triage section (§4.3)
- New endpoint `GET /api/titles/{code}/duplicate-decisions` (§7.A=1).
- Identify and extract any reusable rendering helpers from the global
  Duplicate Triage view (decision row, location row). If the existing
  view's rendering is too coupled to its own DOM, render a smaller
  bespoke version inside the Edit Card — don't twist the global tool
  to fit.
- Stage decision changes; apply them on Commit (PUT/DELETE to existing
  global routes, keyed by `(titleCode, volumeId, nasPath)`).
- Test on an actress with known multi-location titles.

### Phase 4 — Service-layer extraction + folder contents (§4.4, no rename yet)
- Extract `TitleFolderService` (§7.B=2) covering: `listContents`,
  `trashVideo`, `trashCover`. Refactor `AnalyzeTitleVideosTool`,
  `TrashDuplicateVideoTool`, `TrashDuplicateCoverTool` to be thin
  adapters over the service. **Tests for the service** (this is the
  win — the existing tools were hard to test through the MCP envelope).
- New HTTP routes:
  - `GET  /api/titles/{code}/folder-contents`
  - `POST /api/titles/{code}/videos/{filename}/trash`
  - `POST /api/titles/{code}/covers/{filename}/trash`
- Build the two-list UI inside the Edit Card. Trash actions stage on
  click and apply on Commit. Show staged trashes with strikethrough +
  asterisk.
- Section is gated on `locations.size() == 1`; show the orphan warning
  for `locations.size() == 0` (no §4.3 in that case either).

### Phase 5 — Unified Normalize folder modal (§4.4.1)
Folds in both filename normalization **and** layout restructure
behind one modal entry point.

- Add `TitleFolderService.proposeNormalization(code)` — returns the
  full set of suggested moves (renames + path moves) needed to reach
  canonical layout. Reuses logic from existing `restructure_title`
  and the canonical-filename rules (`{CODE}.{ext}`, `{CODE}_disc{N}.{ext}`,
  cover naming).
- Add `TitleFolderService.applyMoves(code, [{from, to}, …])` — executes
  the user-confirmed moves via `VolumeFileSystem` (intra-volume, atomic
  per CLAUDE.md). Renames first, then path moves, to avoid two-stepping
  the same file. The existing `restructure_title` MCP tool becomes a
  thin adapter over this same call.
- New HTTP routes:
  - `GET  /api/titles/{code}/normalize-proposal` — returns the proposal
    used to pre-fill the modal.
  - `POST /api/titles/{code}/apply-moves` (body: array of `{from, to}`
    pairs). Server re-validates everything client-side did: no
    collisions, all files exist, all targets legal, no target hits an
    existing out-of-set file, all moves intra-volume.
- Build the modal (§4.4.1) with both Renames and Moves sections.
  Single-video / canonical pre-fill = one-click confirm. Multi-file
  rename pre-fill is best-guess, user reviews/accepts each. Move
  pre-fill is canonical layout.
- Already-canonical case: modal opens with empty sections + "Folder
  is already canonical." message + disabled Stage button.
- Confirming the modal stages all moves on the card; they're applied
  on Commit. Stages render as `from-path → to-path` rows in the card's
  pending list.

### Phase 6 — Polish
- Empty states: no titles, all titles rejected, all clean.
- Loading / error states for each section (duplicate decisions still
  loading, folder contents fetch failed, commit partial-failure recovery).
- Keyboard nav between cards (?).
- Accessibility pass on Commit / Cancel buttons.

---

## 9. Out of scope (future work)

- **"Manage Rejects" workflow.** Reviewing all rejected titles for an
  actress / library-wide; bulk-restore; bulk-delete-from-disk. Earns
  its own proposal once the reject concept has bedded in and we have
  real data on how users use it.
- **Richer in-place metadata editing on the Edit Card.** Title rename,
  cast adjustment, tag editing — the existing Title Detail screen
  remains the surface for these.
- **Reject for actresses' Catalog visibility.** Whether rejected titles
  affect actress dashboards / counters — separate question.
- **Per-actress filename normalization batch.** Phase 5 is single-title
  only; a "normalize all this actress's folders" would be the bulk
  variant we explicitly excluded.
- **No-content title cleanup workflow.** This proposal *detects* and
  *flags* no-content titles (§4.6) but does not offer cleanup. A future
  global Tools feature should review all no-content titles across the
  library — confirm folder remnants are gone, drop the DB rows. Reject
  is the current bridge: the Admin tab lets the user mark these titles
  for that future tool to find.

---

## 10. Revisit notes

- After Phase 2 ships: confirm `actressTitleAdmin.pageSize: 5` feels
  right (Q2). Tune in config first; only change the default if there's
  a clear pattern.
- After Phase 2: assess attention-score formula. If "needs attention"
  bucket is too noisy or too narrow, refine signals before adding any
  filter UI.
- After Phase 4: revisit Q4 — is the lack of disc-set memory actually
  annoying in practice? If so, evaluate adding a `titles.video_layout`
  column.
- After Phase 5: confirm the unified Normalize folder modal feels
  right with both renames + moves in one preview. Watch for "I just
  want to rename one file" friction — could be a signal to add a
  per-row inline rename in addition to the modal. Watch for "the
  proposal is too aggressive" — if pre-fills suggest moves the user
  routinely overrides, tune the canonical-layout heuristics.
- After Phase 6: revisit §7.E — does greying rejected titles in Catalog
  feel right, or do users actually want a hide-by-default toggle?
- Open: should commit failures (mid-sequence) offer a retry-just-the-
  remaining-steps button, or always require the user to re-stage? MVP
  does the latter; revisit if it's annoying.
