# Title Editor Design

> **Status: COMPLETE**
> Design spec for the Title Editor — a metadata preparation area for fully-structured titles in the unsorted (queue) volume.

## Purpose

The Title Editor is an interactive web page where users can enrich fully-structured titles that landed in the unsorted volume. It is a **preparation area** before redistribution: the user assigns actresses and attaches a cover image, then the title is later moved to the pool (via a separate redistribution command).

## Scope and Eligibility

**Target volume:** `unsorted` (type: `queue`, mapped to `//pandora/jav_unsorted`)

**Eligible titles ("fully-structured"):** all three must hold.

1. Folder name contains the product code in parentheses, e.g. `Some Title (ONED-123)`.
2. The code parses as a recognized JAV code pattern.
3. Videos live in a recognized child subfolder (`video/`, `h265/`, or `4K`) — not loose at the folder base.

Partially-structured entries (bare video files, malformed folder names, videos at base, code missing or outside parentheses) are excluded and not shown in the editor.

**Ineligible after redistribution:** once a title leaves the unsorted volume, it is no longer editable via this interface.

## Workflow

```
unsorted (queue)
  ├── fully-structured titles  ←  shown in editor (eligible)
  ├── partial-structure titles ←  hidden (ineligible)
  └── bare video files         ←  hidden (ineligible)
          ↓  [edit in Title Editor]
       pool (sort_pool)
          ↓  [separate redistribution command]
       library volumes
```

## Editor Rules

| Field     | Rule |
|-----------|------|
| Actresses | 1 or more required. User can add and remove freely, but cannot reduce to zero. Save is blocked if count = 0. One actress is marked **primary** (see Primary actress below). On save, the title folder is renamed to match the primary (see Folder rename). |
| Cover     | Optional. User can attach a cover image via URL drop, local file drop, or clipboard paste (image or URL). Replaces any existing cover on save. If a cover already exists, a one-shot confirmation is shown before replacement. If no cover is provided, existing cover (if any) is preserved. |

### Primary actress

One actress in the list is always marked primary (star / radio affordance). On save, `titles.actress_id` is set to the primary actress.

- **Default primary:** whichever actress sync already set as `actress_id`. If none, the first-added actress.
- **If the primary is removed:** the UI forces the user to pick a new primary before save is enabled.
- Primary is a distinct concept from list order — reordering the list does not change the primary, and the editor does not capture any other ordering.

### Folder rename

On save, the title folder on the unsorted volume is renamed to match the library convention:

    {PrimaryActressCanonicalName} ({code})
    {PrimaryActressCanonicalName} - {Descriptor} ({code})   (when the optional descriptor is set)

Examples:
- `/fresh/(RKI-745)` → `/fresh/Haruna Kawai (RKI-745)`
- `/fresh/(ONED-125)` → `/fresh/Yua Aida - Demosaiced (ONED-125)` (descriptor = `Demosaiced`)

The descriptor is an optional editorial tag (`Demosaiced`, `4K`, `Uncut`, etc.). It has no DB column — the folder basename is the durable storage, and the editor extracts it on load by splitting the basename on ` - ` before the trailing ` (code)`. Empty by default for unsorted folders that don't yet carry one.

**Descriptor character rules** (enforced both client-side for live feedback and server-side as a hard gate):

- Allowed: ASCII letters, digits, spaces, and the punctuation `_ @ # = + , ;`.
- Forbidden:
  - The hyphen `-` (reserved as the `actress - descriptor` delimiter).
  - Filesystem-reserved characters from any OS: `/ \ : * ? " < > |`.
  - Dots, parentheses, non-ASCII characters — not on the allowlist.
- Leading/trailing whitespace is trimmed; internal runs of spaces are preserved.
- Save is blocked (and the input is outlined red) when the current value fails the allowlist.

- Only the folder basename changes; the parent directory stays the same.
- No-op when the current folder name already matches the target.
- Actress name is sanitized: filesystem-unsafe characters (`/ \ : * ? " < > |`) are replaced with a space, and runs of spaces are collapsed.
- **Ordering:** the DB actress save commits first (its own transaction), then the SMB rename runs, then the DB path rewrite (title_locations.path + video paths rooted under the folder) runs in a second transaction. This keeps SQLite locks off the network path.
- **Failure mode:** if the SMB rename fails, the actress save is already committed and the folder is unchanged on disk. The save endpoint returns 500 with the error message; the next successful save will re-attempt the rename.
- Collision guard: if the target folder name already exists (and isn't the current folder), the rename is aborted with a clear error.

### Tags

Under a horizontal divider below the cover + actress grid, the editor exposes the full tag vocabulary (from `/api/tags`) as toggleable chips, grouped by category (Format, Production Style, Setting, Role, Theme, Act, Body). Each category has a distinct color so groups read at a glance.

- **Direct tags** (on `title_tags`) — user-editable; click a chip to toggle.
- **Label-implied tags** (on `label_tags` for the title's label) — **rendered in red, non-interactive**. They already apply automatically via the `title_effective_tags` view and cannot be toggled off from the editor.
- **No DB write until Save.** Toggles mutate a local Set; a tag-only change is enough to enable Save. On Save (non-duplicate), the PUT body carries a `tags` array; the backend replaces `title_tags` for the title and rebuilds `title_effective_tags` (direct ∪ label-implied).
- **Duplicates disable tag editing** (consistent with actress/cover rules — tags are global per product code).

### Duplicates

A title is a **duplicate** when its product code already has ≥1 `title_locations` row outside the unsorted volume (another library volume, partition, or unsorted path). Duplicates are legitimate — special editions, different encodes, BD rips, demosaiced variations — and must be preservable alongside the original.

**When loaded, the editor adapts:**
- A red **Duplicate** badge appears next to the title code, and a banner above the editor lists the other `{volume_id, path}` entries that already exist.
- **Actress list is read-only.** Primary marker, remove button, and the `+ Add actress` card are all disabled. Rationale: actress assignment is global per code — it already reflects the canonical entry and should not be overridden from a duplicate copy.
- **Cover panel is read-only.** Drop, file-paste, and URL-paste handlers are all gated. The existing cover (from the local cache) is reused.
- **Descriptor field stays editable** — it is the mechanism for differentiating this copy (`Demosaiced`, `4K`, `Special Edition`, etc.).

**Save behavior (duplicate):**
- The actress DB and cover cache are never touched.
- The folder is renamed using `titles.actress_id`'s canonical name as primary, with the optional descriptor: `{ExistingPrimary} ({code})` or `{ExistingPrimary} - {Descriptor} ({code})`.
- DB-side path rewrites on `title_locations.path` and `videos.path` are scoped to this location only.
- Save is enabled only when the descriptor changed (the only editable field).

**Cover endpoint guard:** `POST /api/unsorted/titles/{id}/cover` returns `409 Conflict` on duplicates with the message *"Cover changes are disabled for duplicates — reuse the existing cover."* Belt-and-suspenders — the frontend already blocks the interaction.

### Adding a new actress (not in DB)

Typeahead against the existing actress DB. If no match is found, the dropdown offers a "Create '{typed name}'" row. Clicking it:

- Stages a **draft actress** in the editor's local state — the actress is **not persisted until the user hits Save**. Discarding changes (via the unsaved-changes guard) drops the draft with zero DB footprint.
- Attaches the draft (marked visually, e.g. a "new" badge) to the title in the UI.
- On save, the backend creates the actress row (name only, empty profile, no aliases, `needs_profiling = true`) **in the same transaction** as the title_actresses update. If the save fails, no orphan row is left behind.
- `needs_profiling` flags the actress for MCP/AI tooling as a profiling candidate. (Actual profiling remains selective — reserved for established performers.)
- The title's own cover (once attached) becomes her effective cover image via the existing portfolio → actress cover derivation.

This transactional create flow means there is no separate "create actress" endpoint — draft actresses travel with the actress-assignment PUT payload.

## Navigation / Entry Point

The editor is reached from the main toolbar's **Tools** button. Clicking Tools opens the existing tools sub-navigation row (currently hosting **Aliases** and **Duplicates**). A new **Queue** button is added to that row:

- **Label:** `Queue`
- **Placement:** alongside Aliases and Duplicates (`#action-landing-tools-row` in `index.html`), using the same `action-tool-btn` base class with a new distinguishing class (e.g. `tools-queue-btn`).
- **Icon:** an editor-type icon — a pencil-on-document (edit) glyph, consistent in stroke style with the existing `Aliases` (people) and `Duplicates` (stacked squares) SVGs.
- **Color:** a distinct hue from the other two buttons — suggest amber/orange for a "work-to-do" feel, final value chosen during implementation to fit the palette.
- **Behavior:** clicking Queue shows the Title Editor view (sidebar queue + editor pane). The button enters an active/pressed state identical to how Aliases and Duplicates behave.

No changes to the top-level toolbar — the editor lives entirely inside the Tools sub-nav surface.

## UI Layout

Single route, split layout — a persistent sidebar queue on the left, the editor pane on the right. Clicking a sidebar row swaps the editor in place without navigation.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Unsorted — Title Editor                                                    │
├──────────────────────┬──────────────────────────────────────────────────────┤
│  Queue               │  CODE-123 · Folder Name                              │
│  ────────────        │                                                      │
│  ● CODE-123          │  ┌─────────────────────────┐  ┌──────────────────┐  │
│  ○ CODE-124   ◐      │  │                         │  │  Actresses       │  │
│  ○ CODE-125          │  │   Cover Image Preview   │  │  ┌────────────┐  │  │
│  ○ CODE-126   ◐      │  │   (or placeholder)      │  │  │★ Name [x]  │  │  │
│  ○ CODE-127          │  │                         │  │  │○ Name [x]  │  │  │
│  ...                 │  │   Drop / paste image    │  │  └────────────┘  │  │
│                      │  │                         │  │  [+ Add...]      │  │
│                      │  └─────────────────────────┘  └──────────────────┘  │
│                      │                                                      │
│                      │  [Save]  [Skip →]                                    │
├──────────────────────┴──────────────────────────────────────────────────────┤
│  42 eligible  ·  17 complete                                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Sidebar:** scrollable queue of eligible titles in FIFO order. Each row shows the code, folder name (truncated), and a small status marker (● complete, ◐ partial, ○ untouched — where "complete" = ≥1 actress AND cover). The currently-selected row is highlighted. A filter toggle ("Show complete") at the top of the sidebar defaults to **off**, so the queue shows only incomplete titles during a batch session; toggling it on reveals finished ones for re-editing. Clicking a row swaps the editor pane.

**Cover panel:** left side. Shows current cover if one exists, otherwise a styled placeholder. The entire panel is a drag-drop zone **and** is focusable (click to focus). Accepted inputs:

- **URL drop** from another browser tab (`text/uri-list` in `dataTransfer`) — backend fetches bytes.
- **Local file drop** from Finder (`dataTransfer.files`) — bytes uploaded directly.
- **Clipboard paste** when the panel is focused (Cmd-V): either an image blob (`image/*` item) or an image URL (`text/plain` / `text/uri-list`).

Paste is scoped to the drop zone rather than the window to avoid conflicting with the actress typeahead.

**Actress panel:** right side. Shows current actress attributions (pre-populated from sync if actress was embedded in the folder name). Each actress row has a primary marker (star/radio) and an [x] remove button (disabled when count = 1). Typeahead searches both canonical actress names **and** aliases; when a match is via an alias the dropdown row labels it, e.g. `Ai Uehara (matched: Rio)`, so the user sees why it matched. Inline "Create '{name}'" for unknown names (see Editor Rules → Adding a new actress).

**Navigation:** Skip advances to the next title in the currently-visible sidebar order without saving. Iteration is FIFO by `title_locations.added_date` (oldest unedited first), stable across sessions. No "snooze" — skip simply advances. When no titles remain in the current view, the editor pane shows a "no more titles" state. A progress indicator at the bottom shows completion status across the eligible set; **complete** = has ≥1 actress **and** a cover.

**Unsaved-changes guard:** if the editor has dirty state (actresses changed, primary changed, new inline-created actresses pending, or a new cover staged) and the user hits Skip or clicks a different sidebar row, a confirmation dialog prompts **Discard changes? [Discard] [Cancel]**. Cancel keeps the user on the current title with their edits intact; Discard drops the edits and proceeds to the new selection. The same rule applies to both Skip and sidebar-row-click.

## Data Flow

### Actress assignment
- Read from `title_actresses` junction table on page load (sync may have already resolved some)
- Save writes to `title_actresses` (idempotent `INSERT OR IGNORE`); removals delete the junction row
- `titles.actress_id` (filing actress) is set to the **primary** actress selected in the UI, not the first in list order
- Create-on-the-fly actresses are inserted into `actresses` with `needs_profiling = true` and attached in the same save

### Cover image
- Three frontend paths converge on two backend endpoints:
  - **URL** (drop or pasted text) → `POST /api/unsorted/titles/{id}/cover { "url": "..." }`
  - **Bytes** (file drop or pasted image blob) → `POST /api/unsorted/titles/{id}/cover` multipart upload
- For URL fetch, backend sends an appropriate `Referer` header (DMM hotlink protection)
- If an existing cover is present, the frontend shows a one-shot confirmation before issuing the request
- The backend writes the cover bytes **twice**, in order:
  1. **NAS write (first, source of truth):** to the title folder base on the unsorted volume via `VolumeFileSystem.writeFile`. Filename is the normalized product number (`baseCode.ext`, e.g. `RKI-00738.jpg`) — consistent with the local cover cache and library conventions.
  2. **Local cache write (second, best-effort):** to `<dataDir>/covers/<LABEL>/<baseCode>.<ext>` via `CoverPath.resolve` — so the UI preview is instant and other views see the cover without waiting for `sync covers`.
- **Failure handling:**
  - NAS write fails → return an error; cache is not touched; user retries.
  - NAS write succeeds, cache write fails → log a warning, return success. The cover is durably saved where it needs to be; the next `sync covers` (or the editor's next page load) will heal the cache.
  - No multi-destination atomicity is attempted — SMB doesn't support it and the cache is always recoverable.
- Both writes preserve the source image's extension (jpg/png/webp). `CoverPath.find` already probes multiple extensions; no re-encode is performed.
- On success, frontend updates the preview and the sidebar status marker.
- The editor is always **armed** — it performs real filesystem writes regardless of the global dry-run toggle. (Preview mode has no meaningful interpretation for "save a cover.")

### Eligibility check at save time
- Backend verifies the title still has a `title_location` in the unsorted volume before committing
- Guards against a race where redistribution ran between page load and save

## API Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/unsorted/titles` | List eligible (fully-structured) titles in unsorted volume, FIFO by discovery time, with actress count, cover present, and complete status (used by the sidebar queue) |
| `GET` | `/api/unsorted/titles/{id}` | Single title detail: code, folder name, actresses (with primary flag), cover path |
| `PUT` | `/api/unsorted/titles/{id}/actresses` | Replace actress list and primary. Body: `{ "actresses": [{"id": 123} \| {"newName": "..."}, ...], "primary": {"id": 123} \| {"newName": "..."} }`. Items with `newName` are created transactionally (`needs_profiling = true`) and attached in the same request. Rejects if list is empty, if primary is not in the list, or if any `newName` is blank/duplicate. |
| `GET` | `/api/unsorted/actresses/search?q=...` | Typeahead search against canonical names **and** aliases. Returns canonical actress rows, each annotated with the matched alias (if any) for UI transparency. |
| `POST` | `/api/unsorted/titles/{id}/cover` | Save cover. Accepts either JSON `{ "url": "..." }` (backend fetches) or multipart upload (direct bytes). Writes to NAS and local cache. |

## Backend Concerns

- **DMM hotlink protection:** image fetch should send `Referer: https://www.dmm.co.jp/` (or the source domain) to avoid 403s.
- **Cover path:** use existing `CoverPath` utility to resolve the correct filename and directory. Writes are overwrite-in-place; no archival of prior covers.
- **Eligibility filter:** the list endpoint applies the fully-structured filter server-side (see Scope and Eligibility for the three-part definition). All three facts are derivable from existing DB state — no SMB round-trips at list time:
  1. `(CODE)` in folder name: match `TitleLocation.path.getFileName()` against the title's `baseCode` wrapped in parens.
  2. Code parses: guaranteed for any row in `titles`.
  3. Videos in recognized child subfolder: check that at least one `Video` row for the location has a parent folder named `video`, `h265`, or `4K`.

  No sync-side changes required.
- **Actress typeahead:** extend the existing actress search infrastructure to include aliases and return the matched-alias annotation.
- **Needs-profiling marker:** requires an `actresses.needs_profiling` boolean (schema migration via `SchemaUpgrader.applyVN`).
- **URL fetch safety** (SSRF-adjacent guardrails on `POST /cover` with `{ url }`):
  - Scheme allowlist: `http`/`https` only.
  - Total size cap (e.g. 20 MB); stop reading past the cap.
  - Connect + total-read timeouts.
  - Require `Content-Type: image/*` on the final response.
  - Follow redirects, but re-validate the final-hop host and scheme.
  - No strict host allowlist — the tool is single-user and image CDNs churn.

## Out of Scope

- Moving/redistributing titles out of unsorted — separate redistribution command
- Editing title metadata beyond actresses, cover, and folder-name-derived-from-primary (code itself, video filenames)
- Editing titles in any volume other than unsorted
- Bulk editing
