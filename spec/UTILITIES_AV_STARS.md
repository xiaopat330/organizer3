# Utilities — AV Stars Screen

> **Status: DRAFT** (2026-04-22)
>
> Sixth Utilities screen. Companion to `PROPOSAL_UTILITIES.md` and the prior
> specs. Feature branch: `utilities-av-stars`.
>
> Also the web-UI Phase 5 deferral from `PROPOSAL_AV_STARS.md` — but framed as
> **curation/maintenance**, not a dashboard. A consumption-oriented AV Stars
> dashboard (the analogue of the Titles / Actresses dashboards) remains a
> separate future effort.

## Purpose

Move AV-stars curation from the CLI to the web UI.

Today the AV-stars pipeline is fully CLI-driven: `av actresses`,
`av actress <name>`, `av resolve`, `av favorites`, `av migrate-actress`,
`av rename-actress`, `av delete`, `av parse filenames`, `av screenshots`,
`av tags`. This works for power use but makes routine curation tedious —
especially the interactive IAFD resolution flow, which wants click-through
picking rather than shell-prompt pagination.

This screen brings that surface into the Utilities pattern: goals not
commands, visualize-then-confirm for destructive work, atomic task lock,
task-pill progress.

## Scope

**In Phase 1 (this branch):**

- **Actress list** with sort/filter — folder name, stage name, video count,
  favorite/bookmark/rejected state, IAFD-resolved status, volume.
- **Actress detail pane** showing:
  - Profile summary (IAFD-enriched or bare).
  - Video count + quick tech summary (resolutions, codecs seen).
  - Curation state: favorite / bookmark / grade / rejected toggles.
- **IAFD resolve action** — search by name on iafd.com, present candidate
  list inline (picture + birthday + aliases), user clicks to pick, headshot
  fetches into `data/av_headshots/`. The interactive part of today's CLI
  command becomes a proper pick UI.
- **Rename actress** — move curation + IAFD linkage from an old folder name
  to a new one (today's `av migrate-actress` / `av rename-actress`).
- **Delete actress** — uses the already-built `AvDeleteActressCommand` with
  its artifact cleanup; exposes it as a confirm-then-delete action.
- **Parse filenames (batch, per actress or all)** — runs the existing parser
  pass as an atomic task.

**Out of Phase 1 (explicitly deferred):**

- **Screenshot workflows** — generating, reviewing, replacing. Enough surface
  for its own phase; phase 2 or 3.
- **Tag management** — editing AV tags on videos. Has its own CLI; can move
  in phase 2.
- **Dashboard / browse** — watching, gallery view, etc. That's the
  consumption UI referenced in `PROPOSAL_AV_STARS.md` §5 Phase 5; separate
  future doc.
- **Bulk IAFD resolve across many actresses at once.** Phase 2 — POC the
  single-actress picker first, then decide what bulk looks like (auto-pick
  high-confidence match? queue a batch for manual click-through?).
- **Live sync kick-off from the screen** — syncing the AV volumes is a
  Volumes-screen concern; this screen reads from DB + local artifacts
  only. A "sync qnap_av" button could be added to the Volumes screen
  directly if needed.

## Identity

- **Color**: deep purple (`#9333ea` / `#3b0764`) — distinct from the five
  prior palettes (blue / magenta / teal / amber / rose). Premium /
  curation / adjacent-to-Actress-Data-magenta without colliding.
- **Icon**: film-strip with star, or a simple headshot silhouette.
- **Tile label**: "AV Stars".

## Layout

Two-pane, same pattern as Volumes / Backup / Library Health.

```
┌──────────────────────────────────────────────────────────────┐
│ AV Stars   342 actresses · 48 resolved · 27 favorites        │
├────────────────────────────┬─────────────────────────────────┤
│ [filter: unresolved ▾]     │ Asa Akira                       │
│ [sort:   video count ▾]    │ ──────────                       │
│                            │ Folder: Asa Akira   ★ favorite  │
│ ★ Asa Akira        142     │ Videos: 142 · Bytes: 873 GB     │
│   Riley Reid       118     │ Codecs: H264 (89%), HEVC (11%)  │
│   Abigail Mac       76     │                                 │
│ ? Unresolved Folder 38     │ IAFD: resolved (2026-03-14)     │
│ ...                        │ Born: 1986-01-03 · U.S.         │
│                            │ Bio: …                          │
│                            │                                 │
│                            │ [Re-resolve IAFD] [Rename]      │
│                            │ [Parse filenames] [Delete]      │
│                            │                                 │
│                            │ Recent videos: …                │
└────────────────────────────┴─────────────────────────────────┘
```

- Left pane: sortable/filterable list of AV actresses. Status icons inline:
  ★ favorite, ? unresolved IAFD, ⊘ rejected.
- Right pane: selected actress's detail + curation actions. Destructive
  actions (delete, rename) route through the standard visualize-then-confirm
  flow; non-destructive toggles (favorite, grade) are instant.
- Header: summary counters (total / resolved / favorites) reinforcing
  progress, same vibe as Library Health / Duplicate Triage planning.

## IAFD resolve flow

The biggest single UX improvement over CLI is the resolver. Current flow:
`av resolve <name>` → shell prompts with numbered candidates → user types an
index → fetches. Fine for one actress, tedious for many.

Proposed:

1. Click **[Resolve IAFD]** on the detail pane.
2. Right pane shifts to a search UI pre-filled with the actress's name.
3. Candidates appear as a grid (headshot + name + born + aliases + match
   confidence). 0-6 rows typically.
4. User clicks the right one. Server fetches full profile + headshot,
   saves, returns to the detail pane populated.
5. "Not a match" action clears the result without saving anything.

All network work (iafd.com fetches) runs server-side as an atomic task so
the pill gives progress. Cancellable.

## Actions & routing

| Action | Routing | Confirmation |
|---|---|---|
| Toggle favorite / bookmark | Inline | None |
| Set grade | Inline | None |
| Resolve IAFD | Atomic task | Pick flow (step 4 above) |
| Rename (merge curation to new folder) | Atomic task | Visualize: "move X's curation → Y" |
| Delete | Atomic task | Visualize: screenshot/headshot counts to delete |
| Parse filenames | Atomic task | None (read-only; no confirm needed) |

All atomic tasks reuse the infrastructure already shipped — task runner,
cancellation, task-pill, SSE events.

## Backend

New package `com.organizer3.utilities.avstars`:

- `AvStarsCatalogService` — aggregate reads for the list + detail panes.
  Probably wraps `AvActressRepository` + `AvVideoRepository` and adds the
  tech-summary derivation.
- `IafdResolverService` — wraps the existing `HttpIafdClient` +
  `IafdSearchParser` + `IafdProfileParser` in a stateless service that
  returns the candidate list and finalizes the pick. (Some of this already
  exists inside the CLI command — extract and share.)

New tasks under `com.organizer3.utilities.task.avstars`:

- `ResolveIafdTask` — inputs: actressId + chosen candidate URL.
- `RenameAvActressTask` — inputs: oldId, newFolderName.
- `DeleteAvActressTask` — inputs: actressId. (Wraps the existing
  command+cleaner combo.)
- `ParseFilenamesTask` — inputs: optional actressId for scope.

## HTTP surface

- `GET  /api/utilities/avstars/actresses` — list with filter/sort params.
- `GET  /api/utilities/avstars/actresses/{id}` — detail payload.
- `POST /api/utilities/avstars/actresses/{id}/iafd/search` — returns
  candidates.
- `POST /api/utilities/tasks/{id}/preview` — visualize dispatcher gains
  cases for rename + delete.
- `POST /api/utilities/tasks/{id}/run` — existing task-run convention.

Curation toggles (favorite, bookmark, grade, rejected) ride the *existing*
non-utilities `/api/av/actresses/...` endpoints rather than duplicating.

## Frontend module

`src/main/resources/public/modules/utilities-av-stars.js`, class prefix `as-`
(consistent with `bk-`, `lh-`, `al-`, `ad-`). New tile in `action.js` +
`index.html`. No new base patterns — reuses task-center, two-pane, visualize
overlay from prior screens.

## Testing

- `AvStarsCatalogService` — against in-memory SQLite with a small fixture.
- `IafdResolverService` — mock the IAFD client; verify candidate parsing +
  finalize-pick wiring.
- Each new task — atomic composition + cleanup assertions.
- Route shape tests for the new endpoints.

## Open questions

- **Default sort**: video count DESC (power users want their biggest
  actresses first) or unresolved first (curation-momentum framing, matches
  the "cleanup satisfaction" feeling)? I lean unresolved first for parity
  with Duplicate Triage's design ethos, but that's a call.
- **Stage name vs folder name display.** Today the folder is the identity;
  IAFD gives a stage name. When they disagree, which leads? Probably
  stage-name-if-resolved-else-folder; display the other in a smaller line.
- **Headshot refresh cadence.** Does the user ever want to *force* a
  re-fetch even if the URL hasn't changed? Likely a "re-resolve" button
  implies re-fetch; call out in copy.
- **Interaction with IAFD rate limits.** We should throttle. Existing CLI
  does (check). If not, add.

## Non-goals (any phase)

- Ingesting IAFD filmography into `av_iafd_credits` — deferred per
  PROPOSAL_AV_STARS §9.
- Fuzzy matching across renamed actress folders. Manual `rename` is the
  user-confirmed path.
- Consumption surfaces (watch, gallery, search-by-tag from a user's
  perspective). That's the dashboard, separate doc later.
