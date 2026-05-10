# UI Chrome Mockups

Three directions for the global app chrome (top toolbar + federated search). Open each `.html` directly in a browser.

## What's in scope

- App header / brand
- Top nav tabs (Actresses, Titles, AV, Tools)
- Federated search (the "Search everything" component)
- Scope filters (Actresses / Titles / Labels / Studios / AV Actresses)
- Breadcrumb / context row

Sub-landings (Actresses landing, Tools landing) and feature pages will inherit from whichever direction we pick.

## A — Disciplined Dark (`mockup-a-disciplined-dark.html`)

Same dark / dense aesthetic as today, but the design *language* is enforced:
- One accent color (soft blue) instead of 4 tab colors + 16 tools-tab colors.
- Logo gets weight (gradient mark + readable name); tabs become quiet until hovered/active.
- Federated search lives in the header on every view (compact 360px input with ⌘K hint). On home, the same chrome is shown plus a hero search panel — search is never duplicated, just sized differently.
- Scope filters become rounded **chips** (not custom toggle switches).
- Breadcrumb moves into the sub-row alongside the chip filters — saves a row of vertical chrome.

**Pick this if:** the current density and look are basically right, the pain is just inconsistency.

## B — Editorial Calm (`mockup-b-editorial-calm.html`)

Same dark base, but loosened: bigger type (15px base), more padding (40px gutters), 8–12px radii, subtle backdrop blur on the sticky header. Search is a permanent first-class element on the header row.
- Logo + tabs read as muted text, no chromatic identity per-tab.
- Filter chips are full-color when active (purple accent), outlined when off.
- Home hero is genuinely heroic (32px headline, 640px search, soft shadow).

**Pick this if:** the current UI feels cramped, dated, or like a power-user terminal when you'd rather it feel like a 2026 web app. Trade-off: density drops everywhere — some dashboards may need to be re-thought to fit the same info.

## C — Command Bar (`mockup-c-command-bar.html`)

Collapse all chrome into one slim row (40px). Inspired by Linear / Raycast / GitHub.
- Single row: brand · tabs · ⌘K-style search trigger · terminal icon.
- The "federated search" is a **command palette**, not a persistent input — Cmd-K opens a full-featured overlay with scope chips, grouped results (Actresses / Titles / Jump-to actions), and keyboard nav. Same component handles search AND quick-navigation ("jump to Tools › Translation queue").
- A second optional context row appears below per-view, holding breadcrumb + view-specific actions (Filter / Sort / Columns).
- Home view has no hero search at all — Cmd-K *is* the search.

**Pick this if:** you want the most modern feel, you're a keyboard-driven user, and you're willing to teach the muscle memory (⌘K). Trade-off: search is one click further away than today; new users have to discover the palette.

## D — Left Sidebar Rail (`mockup-d-sidebar-rail.html`)

Scrap top tabs; permanent left rail (~210px) holds nav. Tools' destinations (Translation, Trash, Duplicates, Logs, JavDB, Volumes, etc.) are *first-class* siblings — no second strip of rainbow buttons. Top bar becomes a thin row holding breadcrumb + search. Counts/badges in the rail surface state at a glance ("Translation · 12", "Trash · 47").

**Pick this if:** you want one navigation surface that scales as you add utilities; willing to give up ~210px horizontal space; structural change is acceptable.

## E — Library OS (`mockup-e-library-os.html`)

Chrome recedes; content provides the identity. Top bar is a thin translucent strip. Detail pages get a hero band with imagery, and the chrome's accent (brand mark, focus rings) tints from the content's dominant color. Home is a Plex/Apple-Music-style "library shelves" view (Recently viewed, New this week) instead of a search-first portal.

**Pick this if:** the app should feel like a library you spend time in, not a database admin tool. Trade-off: more visual work; less density on detail pages; "tinted chrome" needs a color extractor (one-time work).

## F — Workbench / IDE (`mockup-f-workbench.html`)

Power-user workbench. Three-column layout: left rail (D-style), main table (dense, monospace, sortable), right inspector that shows full context for the selected row (cover, metadata, actions, tags). Bottom status bar replaces the floating task pill, bg-thumbnails chip, and queue badges — sync status, translation queue, active task progress all in one persistent strip.

**Pick this if:** you spend hours/day in this tool, you want zero-click context for every selection, and density is a feature not a bug. JetBrains/Linear/GitHub-Desktop ergonomics. Trade-off: highest learning curve, most code to migrate, least mobile-friendly.

## What to compare

When you open them, focus on:
1. **How much vertical chrome** is consumed before content starts.
2. **Whether tabs fight for attention** or sit quietly until needed.
3. **Where federated search lives** and how it scales from "browsing" to "I need to find one thing fast."
4. **How the scope filters feel** as chips vs toggles.

Tell me which direction (or which mix — e.g. "D's sidebar with C's command palette and F's status bar") and I'll write the design-system spec against it.
