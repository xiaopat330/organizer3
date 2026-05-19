# Post-It Notes — User Annotations for Actresses & Titles

> **Status: PROPOSED** — drafted 2026-05-14.
>
> Lightweight per-entity text note. One note per actress, one per title.
> Pure text, short, user-curated. Surfaces on both UI v1 (primary) and v2.

---

## 1. Goal

Let the user pin a short reminder / note to any actress or title without
leaving the screen they're on. Examples the feature should serve:

- "double-check cover — wrong actress on right side?"
- "favorite scene starts at 42:18"
- "merge candidate for [other slug] — confirm before acting"
- "do not enrich — javdb page is wrong person"

Notes are **personal scratch space**, not catalog metadata. They never
participate in enrichment, sync, or any automated pipeline. They are
user-curated data on equal footing with aliases, duplicate decisions, and
custom avatars: persisted in the DB, included in `UserDataBackup`.

---

## 2. Scope

**In scope:**

- One note per actress, one per title. Pure text, max 280 chars.
- Card affordance: post-it icon (filled when present, outline when empty)
  on actress cards and title cards across both UIs.
  - Click → small modal/popover to add or edit.
  - Hover (when present) → preview tooltip showing the text.
- Detail-page section: always-visible "Notes" block on actress detail and
  title detail with inline edit + clear.
- Backup/restore via `UserDataBackup` / `UserDataBackupService` from day one.
- Filter chip on actress and title browse: "has note" / "no note".

**Out of scope (for now, but not foreclosed):**

- Full-text search of note content. Future work — schema allows it,
  no index built yet.
- Multiple notes per entity, threads, timestamps in UI.
- Rich text, links, attachments, colors, pin/unpin.
- Notes on volumes, videos, partitions, labels — only actresses and titles.
- Mobile/touch hover affordance — long-press preview deferred.
- Sharing / export beyond backup file.

---

## 3. Data model

New table, keyed by `(entity_type, entity_id)`:

```sql
CREATE TABLE notes (
  entity_type TEXT    NOT NULL CHECK (entity_type IN ('actress','title')),
  entity_id   TEXT    NOT NULL,
  body        TEXT    NOT NULL,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  PRIMARY KEY (entity_type, entity_id)
);
CREATE INDEX idx_notes_entity_type ON notes(entity_type);
```

Notes:

- `entity_id` is `actresses.id` (slug) or `titles.id` (title code) — both
  are TEXT primary keys today, so a single column suffices. No FK
  constraint to keep deletes simple; orphan cleanup is a one-line sweep.
- `body` is NFC-normalized, trimmed, and length-capped server-side at 280
  chars. Empty/whitespace body = delete the row (no "empty note" rows).
- `created_at` / `updated_at` are epoch millis. Tracked from day one so we
  can sort or show "edited X days ago" later without a migration.
- Migration: incremental `SchemaUpgrader.applyVN()` adds the table + index.

### 3.1 Repository

```java
public interface NoteRepository {
    Optional<Note> find(EntityType type, String id);
    Map<String, Note> findAllForType(EntityType type, Collection<String> ids);
    void upsert(EntityType type, String id, String body);
    void delete(EntityType type, String id);
    int sweepOrphans();  // delete rows whose entity_id no longer exists
}
```

The batch `findAllForType` is the hot path: card grids and detail-page
hydrators load notes for the visible page in one query, not N+1.

### 3.2 Backup integration

`UserDataBackup` gets a `notes:` section. `UserDataBackupService` exports
on backup, replays on restore. Same shape as the existing aliases /
duplicate-decisions sections — list of `{entityType, entityId, body,
createdAt, updatedAt}`.

---

## 4. HTTP API

All under `/api`:

| Method | Path                                  | Body / Returns                      |
|--------|---------------------------------------|-------------------------------------|
| GET    | `/api/notes/{type}/{id}`              | `{body, createdAt, updatedAt}` or 404 |
| PUT    | `/api/notes/{type}/{id}`              | `{body}` → 200 with row             |
| DELETE | `/api/notes/{type}/{id}`              | 204                                 |
| POST   | `/api/notes/batch`                    | `{type, ids[]}` → `{id: note}` map  |

`{type}` ∈ `actress` | `title`. The batch endpoint backs card grids so a
page render is one round-trip for all visible notes.

Validation: server trims, NFC-normalizes, enforces 280-char cap, rejects
unknown entity_id with 404. **PUT additionally rejects `entity_id`s that
resolve only to a draft row** (`draft_actresses`, draft title state) —
notes are canonical-only; drafts return 400 with a "drafts cannot have
notes" message. PUT with empty body deletes (mirror of the "empty =
delete" rule).

---

## 5. UI — common behavior

### 5.0 Visual language — sticky-note theme

The feature leans hard on the universally recognized **yellow sticky-note**
metaphor. Every visible surface — icon, hover preview, editor modal,
detail-page block — uses the same palette:

- **Sticky yellow** (`#FFF59D` body / `#FFEB3B` accent edge) as the
  primary surface color.
- **Black text** (`#1A1A1A`, not pure black) for body, counter, and
  buttons-on-yellow.
- **Subtle drop shadow** (1–2px, low opacity) on the modal and detail
  block to read as a physical note pinned to the page.
- Slight rotation (≈ −1°) on the detail-page block for the "stuck on"
  feel — skip in card hover popovers (looks janky at small sizes).

The icon is a yellow square note with a folded corner — bright yellow
fill when a note exists, gray outline (same shape, no fill) when empty.
Material `sticky_note_2` works as the base glyph; tint via CSS rather
than picking a generic gray icon, so the empty state still reads as
"this is the sticky-note slot."

A new `--postit-yellow` / `--postit-yellow-edge` / `--postit-ink` token
set is added to the design system tokens so v1 and v2 share the exact
palette. These tokens live alongside the existing accent tokens and are
the only colors used by the `modules/notes/` shared module.

### 5.1 Card icon

The sticky-note icon (yellow filled / gray outline as above) sits in the
card's icon row, after the fav / grade indicators. Always visible:

- **Filled (note present)** — bright yellow square with folded corner.
  Hovering shows a small **yellow popover with black text** (no extra
  fetch — body is in the card model). Click opens the editor modal.
- **Outline (no note)** — muted gray outline, same silhouette. Click
  opens the editor modal with an empty textarea.

### 5.2 Editor modal

Small centered modal (~360px wide), rendered as a **yellow sticky note**
— `--postit-yellow` background, `--postit-ink` text, soft drop shadow,
no chrome border. Buttons sit on the yellow surface as dark text with
a hover underline; no competing accent colors.

```
┌─ Note for [Entity Name] ─────────────┐  ← yellow surface, black text
│  ┌──────────────────────────────┐    │
│  │ <textarea, 4 rows>           │    │  ← transparent textarea on yellow
│  └──────────────────────────────┘    │
│  142 / 280                            │  ← black counter, red past 280
│                                       │
│         [Clear] [Cancel] [Save]      │  ← dark text buttons on yellow
└───────────────────────────────────────┘
```

- Live char counter; counter goes red past 280, Save disabled.
- `Clear` button shown only when a note exists; confirms inline ("Clear?
  [Yes] [No]") and DELETEs.
- `Esc` cancels; `Cmd/Ctrl+Enter` saves.
- On save, the page's note state is updated in place — no full reload.
  The card's icon swaps fill state and the tooltip updates.

### 5.3 Detail page section

A "Notes" block on actress detail and title detail. **Placement: bottom
of the left column** on both v1 and v2 — the column that holds the
cover (title detail) or profile photo (actress detail). The sticky note
sits as the last panel in that column, reading as a yellow note pinned
beneath the entity's visual anchor.

Always visible. Rendered as a **yellow sticky note** — same palette as
the modal, slight ≈ −1° rotation, soft drop shadow, inline edit:

- Empty state: "No note yet — click to add" placeholder (dashed yellow
  outline). Clicking converts the block to a textarea + Save/Cancel.
- Present state: body rendered as preformatted text + a small "Edit"
  affordance in the corner. Edit converts to textarea + Save/Cancel/Clear.
- **Edited-at line** (detail page only, present state only): small
  muted text in the bottom-right corner of the sticky reading
  `"edited 3 days ago"` / `"edited just now"` / `"edited 2 months ago"`.
  Relative format; hover/title attribute shows the absolute timestamp
  for precision. Reads `updated_at` (which equals `created_at` on a
  freshly created note, so first-save renders `"edited just now"`).
  Hidden on cards and hover popovers.

Width fits the left column (~300–400px). The sticky is anchored to the
end of the left-column content stack, not absolutely positioned — if
the right column is taller, the sticky stays put at the end of the
left content.

Same 280-char cap + counter as the modal.

### 5.4 Browse filter

Actress browse and Title browse get a tri-state filter chip "Notes":
**Any / Has note / No note**. Server side this is a single
`EXISTS (SELECT 1 FROM notes ...)` predicate added to the existing
browse SQL.

---

## 6. UI v1 surfaces (primary, must ship complete)

Files to touch in `src/main/resources/public/modules/` **outside `v2/`**:

- `actress-browse/` — card template (icon slot), batch-notes fetch,
  filter chip.
- `actress-detail/` — Notes section at bottom of left column.
- `title-browse/` — card template (icon slot), batch-notes fetch,
  filter chip.
- `title-detail/` — Notes section at bottom of left column.
- Duplicate triage card template — icon slot on both sides of the
  decide-keep/trash pair.
- A new shared module `notes/` (sibling to other shared UI modules) with
  the modal component, API client, char-counter helper, and the design
  tokens (`--postit-yellow`, `--postit-yellow-edge`, `--postit-ink`).
  Both v1 and v2 import from here so the visual treatment lives in one
  place.

The shared `notes/` module is the only new directory under `modules/`;
existing legacy files only get the additions for the icon, filter, and
detail block. Per CLAUDE.md, **legacy UI is protected** — every change
here is additive (new icon slot, new section) and reviewed before commit.

### 6.1 Surfaces explicitly excluded from v1

The following card-shaped surfaces do **not** get the icon in this
release (icon would be too crowded or the surface is draft-state):

- v2 palette title results (popover cards — too tight)
- Dashboard widgets / "recently viewed" tiles
- Cast pills on title detail
- Review queue rows
- Near-miss resolver / pending kanji queue (draft-state)
- Draft title editor (draft-state)

Adding any of these later is purely additive — import the shared module
on the new template.

---

## 7. UI v2 surfaces

Same shape as v1 but using the v2 component conventions:

- `v2/actress-browse/` cards: post-it icon slot.
- `v2/actress-detail/`: Notes section at bottom of left column.
- `v2/title-browse/` grid cards: post-it icon slot. **Palette result
  cards excluded** (see §6.1).
- `v2/title-detail/`: Notes section at bottom of left column.
- v2 duplicate triage cards (when present): icon slot.
- v2 imports the shared `modules/notes/` module (modal + tokens).

v2 work is straightforward once v1 lands — same API, same shared module.

---

## 8. Server module layout

```
com.organizer3
  notes/
    Note.java                  // record(entityType, entityId, body, createdAt, updatedAt)
    EntityType.java            // enum ACTRESS, TITLE
    NoteRepository.java
    NoteService.java           // trim/NFC/cap, canonical validation, upsert/delete, batch
    OrphanNoteFinder.java      // health-sweep + MCP-backed orphan detection
  repository/jdbi/
    JdbiNoteRepository.java
  web/
    NoteRoutes.java            // wires /api/notes/* into WebServer
  backup/
    UserDataBackupService.java // extended to include notes
  mcp/
    NoteToolHandlers.java      // find_orphan_notes, prune_orphan_notes
  utilities/
    VolumeCurationReport.java  // extended to surface orphan note count
```

`Application.java` wires `NoteRepository` → `NoteService` → `NoteRoutes`
manually (no Spring, per project constraint).

### 8.1 Orphan handling

Following the `find_stale_locations` / `prune_stale_locations` pattern
already in use for filesystem rows:

- **No auto-cleanup at startup, no scheduled sweep.** Notes whose
  `entity_id` no longer resolves to a canonical row simply remain in the
  DB until the user explicitly prunes them.
- **Health-sweep surface**: the volume curation report (and any future
  consolidated health view) surfaces `notes.orphan_count` with a peek at
  the bodies so the user can copy anything worth keeping before pruning.
- **MCP tool pair**:
  - `find_orphan_notes` — returns `{entityType, entityId, body, updatedAt}`
    for every note whose entity has been deleted.
  - `prune_orphan_notes` — deletes the rows surfaced by the finder.
    Optional `dryRun` flag mirrors the location-prune tool.
- Backup round-trip preserves orphan rows; restore replays them as-is.
  This is intentional: if a user restores after accidentally deleting an
  actress, the note survives and is awaiting either the actress's
  restoration or an explicit prune.

---

## 9. Testing

Following the testing rules in CLAUDE.md and memory (in-memory SQLite for
repos, mocks via Mockito for command/service):

- `JdbiNoteRepositoryTest` — upsert, find, batch find, delete, orphan
  detection, NFC normalization, length cap (server defense in depth).
- `NoteServiceTest` — empty-body-deletes rule, trim behavior, cap
  enforcement, **draft-rejection** (canonical-only).
- `NoteRoutesTest` — 404 on unknown id, 400 on draft id, 400 on
  overlong body before trim/cap, PUT-then-DELETE-when-empty.
- `OrphanNoteFinderTest` — actresses and titles deleted out from
  under their notes are correctly surfaced; canonical rows are not
  flagged.
- `NoteToolHandlersTest` — `find_orphan_notes` and `prune_orphan_notes`
  including the `dryRun` path.
- `UserDataBackupServiceTest` — round-trip of a notes section
  (export → import → assert row reappears), **including orphan rows
  which must survive the round-trip per §8.1**.
- Browse-filter SQL tests on `JdbiActressRepository` /
  `JdbiTitleRepository`: has-note vs no-note returns the right rows.
  (Per the "SQL predicates get regression tests" rule.)

No frontend tests beyond the existing Playwright snapshot safety net for
the cards.

---

## 10. Rollout

Single PR, since the feature is small and the v1↔v2 split is purely a
template duplication.

1. Schema migration + `Note` model + `JdbiNoteRepository` + tests.
2. `NoteService` (incl. draft-rejection) + `NoteRoutes` + tests; wire in
   `Application`.
3. Shared `modules/notes/` (modal + API client + design tokens).
4. v1 actress-browse + actress-detail (left-column sticky) surfaces.
5. v1 title-browse + title-detail (left-column sticky) surfaces.
6. v1 duplicate triage card icon slot.
7. v2 mirrors of (4)–(6).
8. `UserDataBackup` integration + test (incl. orphan round-trip).
9. `OrphanNoteFinder` + MCP tool pair + volume curation report surface.
8. Browse-filter chip + repo predicate tests.

If the diff gets unwieldy, the natural split is (1–3) shared infra in one
PR, then (4–6) UI surfaces in a follow-up. Backup and filter chip can
piggy-back on either.

---

## 11. Decisions locked & deferred

Decided 2026-05-14:

- **Clear is singular only for v1.** Modal `Clear` (with inline confirm)
  and detail-page `Clear` — no bulk-clear, no multi-select sweep, no
  "clear all" admin button. DELETE endpoint exists, so any future bulk
  UI is additive.
- **Touch / mobile preview deferred.** Desktop-only hover tooltip in v1.
  Touch users tap the icon and go straight to the modal. Revisit when v2
  takes a real touch pass.
- **No edit history.** Editing overwrites the body. Adding
  `note_revisions` later is non-breaking.
- **`updated_at` surfaced on detail pages only.** Relative
  ("edited 3 days ago") in the bottom-right corner of the sticky, with
  absolute timestamp in the hover/title attribute. Hidden on cards and
  hover popovers to keep those surfaces clean. See §5.3.
- **Actress + Title only, but the data model stays type-generic.**
  `entity_type` discriminator, generic API path, generic shared modal
  — adding videos / volumes / labels later is purely additive (enum
  value + CHECK constraint + new UI hook). No third type pre-wired.
- **Visual treatment: yellow sticky-note theme.** `--postit-yellow` /
  `--postit-yellow-edge` / `--postit-ink` token set; icon is a yellow
  square with folded corner (filled = note present, gray outline =
  empty); modal, detail block, and hover popover all render on yellow
  with black text; slight rotation + drop shadow on the detail block.
  See §5.0.
- **Orphan handling: health-sweep + MCP tool pair, no auto-cleanup.**
  Mirrors `find_stale_locations` / `prune_stale_locations`. See §8.1.
- **Drafts excluded.** Notes attach to canonical actresses/titles only;
  draft rows reject with 400 server-side and have no icon affordance.
- **Card surfaces (v1+v2):** actress-browse, title-browse, duplicate
  triage. Detail-page Notes block on actress-detail and title-detail at
  **bottom of left column** (under the cover / profile photo). All
  other card-shaped surfaces explicitly deferred — see §6.1.

Still open / not foreclosed:

- **Full-text search of note body.** Schema is search-friendly; adding
  an FTS5 virtual table later is a single migration. No commitment now.
