# Organizer3 Design System

This is the **canonical reference** for the global UI redesign. Anyone (or any agent) working on UI changes should read this first. The visual reference is `reference/ui-mockups/mockup-recommended.html` — when this doc and the mockup disagree, **this doc wins**; update the mockup to match.

Status: **locked v1**. Migration is module-by-module against this spec.

---

## 1 · Purpose

The app has two fundamentally different jobs:

- **Consumption** — browsing actresses, titles, AV stars; reading detail pages; discovering content.
- **Administration** — triaging duplicates, working the translation queue, managing trash, reviewing logs, fixing data quality issues.

The redesign serves both with **one chrome** and **two presentation modes** inside it. The chrome itself never changes; what fills the main area does.

---

## 2 · Tokens

### Colors

```
--bg            #0d0d10    body
--bg-rail       #08080b    sidebar
--bg-panel      #14141a    inputs, cards, inspector
--bg-card       #15151b    media card surface
--bg-hover      #1c1c24    hover state
--bg-active     #232330    selected / pressed
--bg-status     #08080b    bottom status bar

--border        #1c1c22    subtle dividers
--border-strong #2a2a34    inputs, controls

--text          #e0e0e6    primary
--text-dim      #8a8a96    secondary / labels
--text-faint    #54545e    placeholder / metadata

--accent        #93c5fd    THE ONE accent color
--accent-bg     rgba(147,197,253,0.10)
--accent-fg     #c8dffd    text on accent surfaces

--ok            #6ee7b7    sync ok, kept, etc.
--warn          #fbbf24    needs attention, pending
--error         #f87171    failed, destructive
```

**Rule:** there is exactly **one accent color**. The previous chrome used 4 tab colors + 16 tools-tab colors. That ends now. Tier colors (`--tier-goddess`, `--tier-superstar`, etc.) are content semantics, not chrome.

### Spacing

Padding/margin uses 4-pixel base. Common values: `4 6 8 10 12 14 16 20 24 28 32`.

### Radii

```
--radius        5px    inputs, chips, buttons, rail items
--radius-card   8px    cards, hero portrait, palette
```

### Typography

```
--font-ui    system-ui, -apple-system, sans-serif    prose, names, headings, buttons
--font-mono  ui-monospace, 'SF Mono', Menlo, monospace    codes, IDs, paths, sizes, counts, timestamps
```

**Rule:** Mono is for *data*, not aesthetics. Use it for:
- Title codes (`SOE-803`)
- Volume paths (`qnap_4 / S / soe`)
- File sizes (`4.2 GB`), durations (`2h 41m`), years (`2012`)
- Counts and percentages (`38% · 142/372`)
- Timestamps (`2012-06-19`, `12s ago`)
- Confidence scores (`0.97`)
- Keyboard shortcuts (`⌘K`, `↑↓`, `esc`)

System UI is for everything else (names, titles, button labels, prose, headings).

### Motion

```
--dur-fast     120ms    hover state changes, button press
--dur-base     180ms    card lift, palette enter
--dur-slow     260ms    palette spring-in, hero transitions
--ease-out     cubic-bezier(0.16, 1, 0.3, 1)         entrances, hover
--ease-in-out  cubic-bezier(0.65, 0, 0.35, 1)        bidirectional
```

**Rules:**
- All UI transitions must respect `prefers-reduced-motion: reduce`. Global override sets duration to `0.01ms` when reduced motion is requested.
- Card hover: 3px lift + shadow grow + accent halo. ~180ms ease-out.
- Card press: settles to 1px lift, 60ms.
- Status bar dot pulses (2s ease-in-out infinite) only when its subsystem is *actively working* (sync running, task in flight). Idle subsystems get a static dot.
- Active task progress bar: linear shimmer (1.6s) so you can tell it's not frozen even when % isn't moving.
- Palette entrance: backdrop fades + blurs in, palette card scales from 0.98 with a slight Y offset, ~260ms.

---

## 3 · Chrome contract

The chrome is the **same on every view** and is composed of four elements:

### 3.1 Left rail (~214px, sticky full-height)

- Brand mark + "JAV Helper" at top.
- Sections: **Library** (Actresses, Titles, AV Stars), **Saved** (Favorites, Bookmarks), **Tools** (Translation, Trash, Duplicates, Sync Health, JavDB, Logs, Pending Kanji, Backup, Volumes).
- Each item: 14px monochrome SVG icon + label + optional `--font-mono` badge with a count.
- Badge color signals: default = neutral, `warn` (yellow) = needs attention, `error` (red) = failure.
- Active item: accent color + accent-bg.

**Critical:** Tools items are **first-class** rail siblings. There is **no Tools sub-landing** with rainbow buttons. That pattern is dead.

### 3.2 Topbar (40px, single row, sticky)

3-column grid: `[crumb left] [⌘K trigger centered] [actions right]`.

The ⌘K trigger is the **gravitational center of the chrome** and must visually stand out:
- 400px wide, accent-tinted background, accent search icon, accent-bordered ⌘K kbd.
- Soft accent halo on hover (`box-shadow: 0 0 0 3px rgba(accent,0.10)`).
- Always present, every view.

Right side holds at most 2–3 mono-color icon buttons (view-mode switch, inspector toggle, terminal). Never more.

### 3.3 Context strip (optional, below topbar)

Per-view filter/sort/scope row. Only appears when a view needs it. Filter chips use the chip primitive (rounded pill, accent-bordered when on). Sort/columns/secondary actions go on the right as text-only "context-action" links.

**Rule:** Never stack more than one context strip. The old chrome had `header + breadcrumb + sub-nav + action-landing` — a vertical stack of 4. The new chrome is `topbar + at-most-one-context-strip`.

### 3.4 Status bar (24px, bottom, full width)

Mono font. Persistent across all views. Holds:
- Subsystem indicators (sync, translation, thumbnails, backup) — colored dot + tiny mono icon + label + state. Dot pulses when subsystem is live.
- Active task (right side): name, animated progress bar with shimmer, percent, count, cancel ×.

Replaces the floating task pill, the bg-thumbnails chip, and any other corner-overlay status indicators.

---

## 4 · The two modes

### 4.1 Library mode (default for consumption)

**When to use:** Actresses browse, Titles browse, AV browse, all detail pages (actress detail, title detail, AV star detail), Home.

**Pattern:**
- **Card grid** via `.grid-titles` or `.grid-actresses`. Cover/portrait dominates; metadata is small and below.
- **Hero band** on detail pages: large portrait/cover, name (32–36px), tier eyebrow, mono stats line, primary actions row.
- **Inspector off by default.** The page IS the content.
- **Container queries** on the grid container so cards reflow naturally if the inspector is opened.

**Future polish (deferred):** color extraction from cover/portrait → tinted hero gradient + accent on detail pages. Caches dominant color in DB.

### 4.2 Workbench mode (default for administration)

**When to use:** Duplicates triage, Trash, Translation queue, Pending Kanji, Review queue, Sync Health, Logs, JavDB Discovery, Volumes, Backup.

**Pattern:**
- **Table** with sticky header row, mono fonts on data columns, system font on names, monochrome status pills.
- **Persistent selection** — selected row gets accent-bg + 2px accent left border. Keyboard nav (`↑↓`, `↵`, `i`).
- **Inspector on by default** (~320px right pane). Shows full context for the selected row: cover/portrait at top, eyebrow + title, primary action row (e.g. Keep / Trash / Skip), then `--label` / `kv` sections for metadata, files, recommendations.

**Critical:** workbench is for *administrative* surfaces. If a list is for browsing/discovery (e.g. Titles browse), it gets library cards, not a workbench table. The user can override per-view via the topbar view-mode switch.

---

## 5 · Iconography

**Use icons when they earn their pixels:**
- Rail nav (helps scan dense vertical lists)
- Primary action buttons (Play ▶, Favorite ★, Trash, Edit, Re-enrich, Keep, Skip)
- Status bar items (parseable at a glance)
- Topbar utility buttons (search, view-mode, inspector toggle, terminal)

**Skip icons when:**
- They'd just decorate a clear text label ("Filter", "Sort", "All")
- They'd compete with imagery the card already shows (covers, portraits)
- The label IS the navigation (section titles, breadcrumbs)

**Style:** monochrome only. Inherits `currentColor`. Stroke-based, line weight ~1.8–2 (2.4 on primary buttons for legibility). Sized to context: 11px (status bar), 13–14px (rail, buttons, topbar). **No per-icon color identity.** That was the old chrome's failure mode.

---

## 6 · What's ruled out

- **Multi-color tabs.** The 4-tab rainbow (pink/blue/red/orange) + the 16-tool rainbow are dead. One accent.
- **Custom toggle switches** for filter scope. Use chip pills.
- **Stacked sticky bars.** Topbar + at-most-one context strip.
- **Tools sub-landing.** Tools are rail siblings now.
- **Duplicate search components.** One ⌘K palette serves both home and other views.
- **Floating task pill / bg-thumbnails chip.** Status bar holds all ambient state.
- **Glassmorphism on permanent chrome.** Topbar/sidebar stay opaque (perf during scroll). Backdrop blur is allowed only on transient overlays (palette, modals).
- **View transitions API, AI-augmented ⌘K, custom display fonts, scroll-driven animations.** Deferred or skipped.

---

## 7 · Federated search (⌘K)

One unified palette serves both content search and command-palette navigation.

- Single input. Results grouped by category: **Actresses · Titles · Labels · Studios · AV Actresses · Jump to…**
- Scope chips at top let user constrain. `tab` cycles scopes.
- Triggered by ⌘K from anywhere. The trigger in the topbar is also clickable.
- Backdrop blurs+dims the app behind the overlay (`backdrop-filter: blur(14px) saturate(140%)`, ~55% black). Spring-eased entrance.
- Keyboard: `↑↓` navigate, `↵` open, `⌘↵` open in new tab, `tab` cycle scope, `esc` close.

The "Jump to…" group covers commands like `Tools › Translation queue`, `Toggle inspector`, `Switch volume`. Same input, no separate command palette.

---

## 8 · Migration plan

1. **Token + primitive layer.** Extract these tokens into `base.css`. Build a `/design` kitchen-sink route that renders every primitive (rail item, chip, button, card, hero band, inspector section, status bar).
2. **Chrome shell.** Build the rail + topbar + status bar in the real app. Wire up ⌘K palette with current search backend. New views render inside the shell.
3. **Module-by-module migration.** Convert one feature at a time. As each module migrates, delete its bespoke CSS file. Done when 28 CSS files collapse to ~5.

The mockup at `reference/ui-mockups/mockup-recommended.html` is the visual reference but is **not** the implementation. The implementation lives in `src/main/resources/public/` and migrates against this spec.
