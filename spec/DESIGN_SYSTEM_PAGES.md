# Design System — Pages & Missing Primitives

This is the **migration atlas** for the global UI overhaul. It enumerates every existing surface in the app, assigns each to library or workbench mode, lists primitives needed, and identifies primitives the v1 spec doesn't yet cover.

Companion to `spec/DESIGN_SYSTEM.md` (which is the locked v1 — tokens, chrome, primitives). When this doc and the spec disagree, the spec wins; update this doc.

Status: **draft v1** — needs review before migration starts. Open questions noted inline.

---

## Legend

- **L** = library mode (cards, hero, inspector off by default — consumption)
- **W** = workbench mode (table + inspector + mono fonts — administration)
- **H** = hybrid (mostly one mode but with elements of the other)
- **S** = special (needs a custom layout pattern beyond the two modes)

---

## 1 · Top-level navigation surfaces

### 1.1 Home
- **Mode:** L
- **Current:** `home.js` + giant centered "Search everything" portal in `index.html`
- **Becomes:** Library shelves (Recently viewed · New this week · Favorites · Continue exploring). The portal search is gone; ⌘K replaces it. Hero search panel deleted.
- **Primitives:** card-title, card-actress, section-head, shelf grid
- **Open question:** which shelves? Suggest: *Recently viewed* (mixed actresses+titles), *New this week* (titles), *Favorites* (actresses), *Needs attention* (drop into tools — duplicates count, translation backlog, etc.).

### 1.2 Actresses (`actress-browse.js`)
- **Mode:** L
- **Becomes:** Card grid of actress cards. Filter chips for tier / company / favorites. Sort options.
- **Primitives:** card-actress, chip, context-action, grid-cols slider (kept), filter dropdown (NEW)
- **Drops:** the `actress-landing` strip with 7 buttons (Dashboard/Favorites/Bookmarks/Exhibition/Archives/Studio/Tier). These become *filter chips* on the same browse, or first-class rail items in "Saved", or sub-routes — see §2.

### 1.3 Titles (`title-browse.js`)
- **Mode:** L
- **Becomes:** Card grid of title cards. Filter chips (Recent / Untagged / Has duplicate / Favorites). Filter dropdowns (Label / Year / Company). Sort.
- **Primitives:** card-title, chip, context-action, filter dropdown (NEW), grid-cols slider

### 1.4 AV Stars (`av-browse.js`)
- **Mode:** L
- **Becomes:** Same pattern as Actresses but for AV-volume actresses.
- **Primitives:** card-actress (variant), chip, context-action

---

## 2 · Actresses sub-views (current "actress-landing" strip)

The current `actress-landing` is a row of 7 special-purpose buttons (Dashboard / Favorites / Bookmarks / Exhibition / Archives / Studio / Tier). In the redesign these split:

### 2.1 Actress dashboard (`dashboard-panels.js`, `dashboard-renderers.js`)
- **Mode:** L
- **Becomes:** Default landing for the Actresses rail item. Shelves of actress cards: tier groups, recent activity, etc.
- **Primitives:** card-actress, section-head, shelf grid, **dashboard-panel (NEW — generic shelf with rotation/refresh)**

### 2.2 Favorites · Bookmarks
- **Mode:** L
- **Becomes:** Rail "Saved" section — first-class items (already in spec). Each shows a card grid of saved actresses.

### 2.3 Exhibition · Archives · Studio · Tier
- **Mode:** L (browsable filtered views)
- **Becomes:** Filter chips + dropdowns on the main Actresses browse. NOT separate sub-pages. Each chip applies a constraint; the URL/state captures it.
- **Drops:** the company-select dropdowns and the marquee component become a single "company filter dropdown" + small marquee primitive.
- **Open question:** is the company marquee important enough to keep? (it scrolls company labels horizontally)

---

## 3 · Detail pages

### 3.1 Actress detail (`actress-detail.js`)
- **Mode:** L
- **Becomes:** Hero band (portrait + name + tier eyebrow + stats + primary actions) → Portfolio shelf → Sections (Stats, Profile, Custom images, History).
- **Primitives:** hero band, card-title, section-head, kv table (NEW — for profile data), avatar-frame
- **Inspector:** off by default; opt-in for editing flow

### 3.2 Actress detail — admin tab (`actress-detail-admin/`, recently merged)
- **Mode:** W (administrative — already designed as a tab)
- **Becomes:** Either: (a) a tab strip within actress detail (NEW primitive needed); or (b) a workbench mode toggle on the same page that swaps content.
- **Primitives:** tab strip (NEW), kv editor, table row, file row
- **Open question:** tab vs mode-switch. Tabs are familiar; mode-switch is more consistent with the global library/workbench split. Recommend **mode-switch** (the `i` toggle pattern but for the whole content area).

### 3.3 Title detail (`title-detail.js`)
- **Mode:** L
- **Becomes:** Hero band (cover + code + name + meta + primary actions) → Sections (Cast, Files, Tags, Related, History).
- **Primitives:** hero band (cover variant), section-head, tag pill, related-card grid

### 3.4 AV actress detail (`av-actress-detail.js`)
- **Mode:** L
- **Becomes:** Same as Actress detail. IAFD profile data lives in a section.

### 3.5 AV video detail (`av-video-detail.js`)
- **Mode:** L
- **Becomes:** Hero (thumbnail + title) → Video player → Metadata sections → Screenshot strip
- **Primitives:** **video player frame (NEW)**, **screenshot strip (NEW — horizontal scroll of thumbs)**, hero band

---

## 4 · Tools / administration

All of these become rail items under the **Tools** section. All default to W mode unless noted.

### 4.1 Library Health (`utilities-library-health.js`)
- **Mode:** W
- **Pattern:** two-pane — checks list (left) + check detail (right inspector).
- **Already:** uses two-pane layout. Migrates cleanly.

### 4.2 Translation (`utilities-translation.js`)
- **Mode:** W
- **Pattern:** stats panel + strategy table + manual translate widget + bulk submit + recent failures.
- **Becomes:** Top: stats tiles (NEW — small numeric KPI tile primitive). Body: failures table + inspector showing failure detail and retry actions. Right rail or top: bulk-submit form.
- **Primitives:** **kpi-tile (NEW)**, table row, inspector, **form group (NEW)**

### 4.3 Trash (`utilities-trash.js`)
- **Mode:** W
- **Pattern:** two-pane — volumes (left) + paginated trash table (right).
- **Becomes:** rail-mounted volume picker becomes a chip filter row OR a small rail-within-rail. Trash items in workbench table with inspector showing original location + restore/purge actions.
- **Primitives:** table row, inspector, chip, pagination (NEW)

### 4.4 Duplicates triage (`utilities-duplicate-triage.js`)
- **Mode:** W
- **Pattern:** per-actress comparison grid with KEEP/TRASH/VARIANT decisions.
- **Becomes:** Mockup view 3 nailed this. Table of duplicate pairs + inspector with side-by-side diff.
- **Primitives:** table row, inspector with **diff section (NEW — two-column kv with highlighted differences)**

### 4.5 Merge candidates (`utilities-merge-candidates.js`)
- **Mode:** W
- **Pattern:** title-code pairs sharing a base_code, MERGE/DISMISS decisions.
- **Becomes:** Same as duplicates; table + inspector with merge-preview diff.

### 4.6 Pending Kanji (`utilities-pending-kanji.js`)
- **Mode:** W
- **Pattern:** aggregate view of unresolved kanji stage names.
- **Primitives:** table row, inspector, **kanji-card (NEW — script-styled name display)**

### 4.7 Enrichment Review (`utilities-enrichment-review.js`)
- **Mode:** W
- **Pattern:** queue with bucket pills for per-reason filtering. Modal for ambiguous rows.
- **Primitives:** chip (bucket filter), table row, inspector, modal

### 4.8 JavDB Discovery (`utilities-javdb-discovery/`)
- **Mode:** W
- **Pattern:** search results from javdb, decide whether to enrich.
- **Primitives:** table row, inspector with cover preview, primary-action button

### 4.9 Actress Data (`utilities-actress-data.js`)
- **Mode:** W
- **Pattern:** two-pane — actress YAML list (left) + per-actress operations (right).
- **Primitives:** table row, inspector, **operations panel (NEW — labelled action group with status output)**

### 4.10 AV Stars (`utilities-av-stars.js`)
- **Mode:** W
- **Pattern:** two-pane filtered actress list + IAFD picker.
- **Primitives:** table row, inspector, picker (NEW or just dropdown), operations panel

### 4.11 Backup (`utilities-backup.js`)
- **Mode:** W
- **Pattern:** snapshots list + per-snapshot detail/visualize/run.
- **Primitives:** table row, inspector with **visualize section (NEW — small chart/diff)**, operations panel

### 4.12 Sync Health (`utilities-sync-health.js`)
- **Mode:** W
- **Pattern:** coherent sync CTA + reconcile signals + report history + per-volume actions.
- **Primitives:** kpi-tile, table row, inspector, operations panel

### 4.13 Tag Health (`utilities-tag-health.js`)
- **Mode:** W
- **Pattern:** every enrichment_tag_definitions row with title_count, alias status, etc.
- **Primitives:** table row, inspector, chip (filter)

### 4.14 No-Match Triage (`utilities-no-match-triage.js`)
- **Mode:** W
- **Pattern:** failed-enrichment titles with three resolution paths.
- **Primitives:** table row, inspector with three primary actions

### 4.15 Volumes (`utilities-volumes.js`)
- **Mode:** H (workbench list + library-style status cards)
- **Pattern:** volume picker + 5-mode operations stage.
- **Becomes:** Volumes can be either a workbench table OR a row of status cards (mount state, free space, last sync). Probably **status cards on top + workbench operations panel below**.
- **Primitives:** **status card (NEW — labeled metric with state pill)**, operations panel

### 4.16 Logs (`log-viewer.js`)
- **Mode:** S
- **Pattern:** tails `/api/logs/tail`. 32KB initial, 3s poll.
- **Becomes:** Code-tail surface — auto-scrolling pre with monospace font, level coloring (INFO/WARN/ERROR), timestamp column, optional filter.
- **Primitives:** **log-tail (NEW — virtualized auto-scrolling pre with level styling)**, chip (filter by level), search input

---

## 5 · Modals / overlays

Currently 6+ bespoke modal CSS files. All collapse to **one canonical modal primitive** with size variants and a slot-based body.

### 5.1 Cover modal (`cover-modal.js`)
- **Becomes:** image viewer modal (large size, image-only body)

### 5.2 Alias capture modal (`alias-capture-modal.js`)
- **Becomes:** form modal (medium size, form body, primary action)

### 5.3 Near-miss modal (`near-miss-modal.js`)
- **Becomes:** form modal (medium, with comparison body)

### 5.4 Enrichment detail modal (`enrichment-detail-modal.css`)
- **Becomes:** detail modal (large, kv + actions)

### 5.5 Title editor modal (`title-editor.js`, `title-editor-draft.js`, `title-editor-nodraft.js`, `title-tag-editor.js`)
- **Open question:** is the title editor a *modal* or a *page*? Currently it's a sub-route with sidebar queue + editor pane. Recommend keep as page; tag editor stays as modal-within-page.
- **Becomes:** workbench mode page (split: queue list left, editor pane right) + tag editor as small form modal

---

## 6 · Cross-cutting feature surfaces

### 6.1 Federated search (`search.js`)
- **Becomes:** ⌘K palette (per spec §7). Both the home portal and the sticky sub-nav search bar **disappear**.

### 6.2 Terminal (`terminal.js`)
- **Becomes:** slide-out panel from the topbar terminal icon, unchanged in concept. Restyled with monospace + log-tail primitive.

### 6.3 Task Center / Task Pill (`task-center.js`)
- **Becomes:** the **status bar** (per spec §3.4). The floating pill is dead.

### 6.4 Background thumbnails chip (`bg-thumbnails.js`)
- **Becomes:** a status bar item (per spec §3.4). The corner chip is dead.

### 6.5 AV Screenshot Controls (`av-screenshot-controls.js`)
- **Mode:** W (mounts inside av-actress-detail)
- **Pattern:** queue control buttons.
- **Primitives:** button, status row

### 6.6 Custom avatar editor (`custom-avatar-editor.js`)
- **Pattern:** image crop UI.
- **Becomes:** modal with **image-crop primitive (NEW)**

### 6.7 Alias editor (`alias-editor.js`)
- **Mode:** W
- **Pattern:** sub-view of Actress Data; two-pane search + selected aliases.
- **Primitives:** table row, inspector, chip

---

## 7 · Missing primitives — to add to the spec + `/design.html`

Listed in priority order (most used first).

### 7.1 Modal (canonical)
**Use:** every overlay form/dialog/viewer.
**Sizes:** `sm` (~360px), `md` (~520px), `lg` (~780px), `xl` (~1100px).
**Anatomy:** backdrop (blur+dim, like ⌘K palette) → card with rounded corners + accent-tinted shadow → header (title + close ×) → body (slot) → footer (cancel + primary action). Spring-eased entrance.
**Variants:** `confirm` (with destructive emphasis on primary action).

### 7.2 Form group
**Use:** every form field.
**Anatomy:** label (uppercase mini-label, text-faint) → input → optional help text (text-dim) → optional error (red, replaces help).
**Composition:** form fields stack with consistent gap; field-row groups inputs horizontally.

### 7.3 Empty state
**Use:** "No duplicates found", "No translation backlog", "Your favorites are empty".
**Anatomy:** centered icon (40-60px, accent color or text-faint) → title (15px text) → description (12px text-dim) → optional CTA button.

### 7.4 Loading / skeleton state
**Use:** while data loads on a workbench table or card grid.
**Anatomy:** card-shaped or row-shaped placeholders with subtle pulse animation. Match the actual layout so there's no jump on load.

### 7.5 Error state (page-level)
**Use:** API failure, missing data.
**Anatomy:** like empty state but with error-color icon + retry CTA.

### 7.6 Toast / notification
**Use:** "Trashed SOE-803", "Translation queued", "Sync failed".
**Anatomy:** small card sliding in from bottom-right above the status bar. Auto-dismisses (3-5s); persists on hover. Variants: ok / warn / error.

### 7.7 Tooltip
**Use:** hover hints on icon-only buttons, truncated names.
**Anatomy:** small dark popover with arrow, mono font for terse, ui font for prose.

### 7.8 Popover / dropdown
**Use:** filter dropdowns (Label / Year / Company), sort menus, the tier change menu, etc. Richer than tooltip.
**Anatomy:** card with optional input + scrollable list. Click-outside dismiss.

### 7.9 Sort header
**Use:** workbench table column headers.
**Anatomy:** existing header row + arrow indicator (▲/▼) when sorted; hover hints clickability.

### 7.10 Pagination / infinite-scroll cue
**Use:** big browse lists, trash, translation history.
**Default approach:** infinite scroll with a sentinel; show "loading more…" cue at the bottom.
**Fallback:** explicit page nav (prev/next + page count) for very long lists.

### 7.11 Tab strip
**Use:** within a feature page (actress detail, settings, possibly title editor).
**Anatomy:** horizontal row of tab buttons, active tab gets accent underline.
**Note:** prefer mode-switches over tabs where possible; only use tabs when content is truly parallel/peer.

### 7.12 Filter dropdown
**Use:** Label / Year / Company / Studio etc. on browse pages.
**Anatomy:** chip-styled button that opens a popover with search input + checkable list. Closes on selection or click-outside. Active state shows selected count.

### 7.13 KPI tile
**Use:** small numeric headlines on Tools dashboards (Translation: "12 pending · 3 failed").
**Anatomy:** label (mini-label) + big mono number + optional trend/secondary.

### 7.14 Operations panel
**Use:** Tools that "do work" (Actress Data, Backup, Volumes, AV Stars).
**Anatomy:** labelled action group → buttons → status output area (log-tail or live-text).

### 7.15 Inspector diff section
**Use:** Duplicates triage, Merge candidates.
**Anatomy:** kv with two values side-by-side and highlighted differences.

### 7.16 Status card
**Use:** Volumes (mount state per volume).
**Anatomy:** card with label + state pill + small numeric + action button.

### 7.17 Log-tail surface
**Use:** Logs viewer, terminal, operations panel output.
**Anatomy:** monospace `<pre>`, virtualized, auto-scroll on new content unless user scrolled up. Level coloring (INFO/WARN/ERROR). Timestamp column. Optional filter chips.

### 7.18 Video player frame
**Use:** AV video detail.
**Anatomy:** `<video>` with custom controls overlay matching the design tokens.

### 7.19 Screenshot strip
**Use:** AV video detail.
**Anatomy:** horizontal scroll row of thumbnails, click to fullscreen.

### 7.20 Image-crop primitive
**Use:** Custom avatar editor.
**Anatomy:** existing crop UI restyled with tokens.

### 7.21 Avatar frame (small)
**Use:** Inline actress representation in cast lists, search results, palette rows.
**Anatomy:** small circular/rounded portrait + name + optional tier dot.

### 7.22 Tag pill (selectable, removable)
**Use:** Title tag editor, actress profile tags.
**Anatomy:** chip variant with optional × to remove. Categories color-coded (limited palette, not the rainbow).

### 7.23 Section head with action
**Use:** "Portfolio · 165 [Filter ▾]" headers within detail pages.
**Anatomy:** title + count + right-aligned action link.

### 7.24 Range filter
**Use:** Year range, file size range.
**Anatomy:** dual-thumb slider OR two number inputs + chip showing active range.

### 7.25 Marquee (small)
**Use:** Company labels scrolling on actress sub-views (if kept).
**Anatomy:** auto-scrolling horizontal text, pauses on hover.
**Open question:** worth keeping at all?

---

## 8 · Proposed migration order

The spec's order (tokens → kitchen-sink → chrome shell → modules) holds, but here's the per-module order I'd recommend within step 3:

### Wave 1 — Chrome + foundation (must come first)
1. Chrome shell in real app (rail, topbar, ⌘K palette, status bar) — wired to current routes; existing views render inside it. Old chrome deleted.
2. Add missing primitives 7.1 (modal), 7.2 (form group), 7.3 (empty state), 7.4 (skeleton), 7.6 (toast), 7.7 (tooltip), 7.8 (popover) to `/design.html`.

### Wave 2 — High-value library surfaces (most user-visible)
3. Titles browse → card grid + filter chips + filter dropdowns
4. Actresses browse → card grid (also handles favorites / bookmarks via filter chips; Exhibition / Archives / Studio / Tier collapse here)
5. Title detail → hero + sections
6. Actress detail → hero + portfolio shelf + sections
7. Home → shelves
8. AV browse + AV detail (parallel structure)

### Wave 3 — Workbench surfaces (one early as a proof point, then sweep)
9. Duplicates triage → table + inspector + diff section (proof point)
10. Translation, Trash, Pending Kanji, Enrichment Review, JavDB Discovery, Merge Candidates, No-Match Triage (parallel structure — sweep)
11. Library Health, Sync Health, Tag Health, Backup, Actress Data, AV Stars, Volumes (parallel structure — sweep)

### Wave 4 — Special / cross-cutting
12. Logs viewer (log-tail primitive)
13. Title editor (its own page, workbench-leaning)
14. Custom avatar editor (modal w/ crop)
15. Terminal (slide-out, restyled)

### Wave 5 — Cleanup
16. Delete the 28 legacy CSS files as their owning modules migrate. Target end state: ~5 CSS files (`base.css`, `chrome.css`, `library.css`, `workbench.css`, `primitives.css`).
17. Delete the 6+ modal CSS files; one canonical modal serves all.
18. Delete `tools-chrome.css` rainbow rules entirely.

---

## 9 · Open decisions before migration starts

These should be answered before Wave 1 ships:

1. **Home shelves — which ones?** (per §1.1)
2. **Actress detail admin tab — tabs vs mode-switch?** (per §3.2) — recommend mode-switch.
3. **Company marquee — keep?** (per §2.3, §7.25)
4. **Title editor — modal or page?** (per §5.5) — recommend page.
5. **Pagination strategy — infinite-scroll default or explicit pages?** (per §7.10) — recommend infinite-scroll.
6. **Responsive collapse — at what breakpoint does the rail collapse to icons-only? When does the inspector drop?** Spec is silent. Default for now: rail collapses below 1100px (icons-only with hover labels), inspector hides below 1400px (must be reopened with `i`).

---

## 10 · What this doc is NOT

- Not the implementation. The spec (`DESIGN_SYSTEM.md`) is authoritative for tokens/primitives.
- Not the visual reference. The mockup (`reference/ui-mockups/mockup-recommended.html`) is.
- Not exhaustive. As we migrate, we'll find edge cases. Update this doc inline.
- Not an architecture plan. JS module organization, route structure, state management are separate concerns.
