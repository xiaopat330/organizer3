# Proposal: Federated Search

> **Status: IMPLEMENTED**
> Federated search is fully implemented: `SearchService` backend queries JAV actresses, titles, labels, companies, and AV actresses. The home page has a search bar with grouped results and per-category toggle filters. The AV actresses category was a subsequent addition — the `ALL_CATEGORIES` list in `home.js` controls which groups are rendered; `includeAv` flag is derived from whether that category is enabled.

Design spec for the home page federated search feature. The search box is the flagship element of the home landing page — the first thing a user sees when entering the site, and the primary navigation path into any part of the library.

---

## 1. Motivation

The existing home page is a collection of basic list placeholders. The new home page is a portal, and the centerpiece is a single text input that can reach across all major entity types in the library: actresses, titles, labels, companies, and (optionally) AV actresses. The goal is to let a user arrive, start typing anything they remember — a name, a code, a label — and get there in two keystrokes.

---

## 2. Scope

### In scope

| Entity | Match fields |
|---|---|
| JAV Actresses | Primary name + all aliases |
| Titles | Product code (exact) + title name (fuzzy) |
| Labels | Name |
| Companies | Name |
| AV Actresses | Stage name — first or last name match only |

### Out of scope

- AV video search
- AV studio search
- Tag / genre search
- Physical attribute search (nationality, measurements, etc.)

---

## 3. Interaction model

### 3.1 Entry point

The search box is centered on the home page above the fold. It is immediately focused on page load — no click required to start typing.

A keyboard shortcut (`/` or `Cmd+K`) re-focuses the search box from anywhere on the page, consistent with convention in media/content apps.

### 3.2 Trigger and debounce

Search fires on every keystroke with a 250 ms debounce. Minimum query length is 2 characters — queries shorter than 2 chars clear the results panel without querying.

### 3.3 Product code shortcut

If the input matches the product code pattern `[A-Z]+-\d+` and a title exists in the database for that code, navigate directly to the title detail page — no results panel shown. If the code is not found, fall through to the normal results panel in a "not found" state, with fuzzy matching of the code string against title names as a soft fallback.

### 3.4 Results panel

Results appear in a floating overlay panel anchored below the search box. The panel is dismissed by pressing `Escape` or clicking outside it. It does not displace page content.

The panel is divided into groups, one per entity type, displayed in this order:

1. Actresses (JAV)
2. Titles
3. Labels
4. Companies
5. AV Actresses (if enabled — see §5.1)

Each group shows:
- A header with the entity type name and total match count
- Up to N result rows (configurable — see §6)
- A "see all N →" link when total matches exceed the per-group limit

Groups with zero results are hidden entirely. If all groups are empty, the panel shows a "No results" message.

### 3.5 Keyboard navigation

Arrow keys move focus through result rows across groups. `Enter` navigates to the focused result. `Escape` dismisses the panel. Tab cycles through the "see all →" links.

### 3.6 "See all" destination

Clicking "see all N →" navigates to a dedicated search results page (`/search?q=<query>&type=<entity>`) scoped to that entity type, with full pagination and sort controls. This page is designed separately and is out of scope for this proposal.

---

## 4. Result rows

### 4.1 Actress (JAV)

```
[headshot]  Yua Mikami          [S]  · goddess
            S1 No.1 Style  ·  214 titles
```

Fields: headshot thumbnail, primary name, grade badge, tier, primary label, title count.

If the match was made via an alias rather than the primary name, the matched alias is shown beneath the primary name:

```
[headshot]  Yua Mikami          [S]  · goddess
            matched alias: "Mikami Yua"
            S1 No.1 Style  ·  214 titles
```

### 4.2 Title

```
ABP-123  Some Title Name
         Yua Mikami  ·  S1 No.1 Style  ·  2021
```

Fields: product code, title name, primary actress, label, year.

### 4.3 Label

```
S1 No.1 Style    (SOD)
```

Fields: label name, parent company.

### 4.4 Company

```
SOD  (Soft On Demand)
```

Fields: company ID / short name, full name.

### 4.5 AV Actress

```
[headshot]  August Ames
            142 videos
```

Fields: headshot thumbnail, stage name, video count. No grade or tier (AV schema has no tier system).

---

## 5. Advanced mode

A small `⚙` icon to the right of the search box opens an advanced controls bar that expands inline below the search box without navigating away. Collapsed by default.

### 5.1 Controls

| Control | Default | Description |
|---|---|---|
| Include AV Actresses | Off | Adds the AV Actresses group to results |
| Match mode | Contains | Contains (anywhere in name) vs. Starts with (prefix only) |

Advanced mode state is persisted in `localStorage` so it survives page reloads.

---

## 6. Configuration

The following properties are added to `organizer-config.yaml` under a `search:` block:

```yaml
search:
  includeAvActresses: false        # AV actresses shown in results by default
  showAliasMatchIndicator: true    # Show matched alias name under primary name
  maxActressResults: 5             # Max JAV actress rows shown in panel
  maxTitleResults: 3               # Max title rows shown in panel
  maxLabelResults: 2               # Max label rows shown in panel
  maxCompanyResults: 2             # Max company rows shown in panel
```

These values are the defaults. All are overridable.

---

## 7. Backend

### 7.1 Endpoint

```
GET /api/search?q=<query>&includeAv=<bool>&matchMode=<contains|startsWith>
```

Returns grouped results, one object per entity type. Groups with no results are omitted.

```json
{
  "query": "yua",
  "groups": [
    {
      "type": "actress",
      "total": 8,
      "results": [ ... up to maxActressResults items ... ]
    },
    {
      "type": "title",
      "total": 5,
      "results": [ ... up to maxTitleResults items ... ]
    },
    {
      "type": "label",
      "total": 1,
      "results": [ ... ]
    }
  ]
}
```

### 7.2 Queries

Each group is queried independently. For v1, `LIKE '%query%'` (contains) or `LIKE 'query%'` (starts-with) is sufficient — SQLite is local and library sizes are modest. FTS5 virtual tables are the upgrade path if performance becomes an issue.

**Actress query:** searches `actresses.name` and `actress_aliases.alias`. When a match is on an alias, the matched alias string is included in the result row so the frontend can render the indicator.

**Title query:** product code is checked first for an exact match (triggers the direct-navigate shortcut on the frontend). Title name is searched with the configured match mode.

**Label / Company queries:** name search with configured match mode.

**AV Actress query:** searches `av_actresses.stage_name` only. No alias matching.

### 7.3 Code shortcut detection

The frontend detects the product code pattern before submitting to the API. If the input matches `[A-Z]+-\d+`, the frontend calls a dedicated endpoint:

```
GET /api/titles/by-code/<code>
```

- **Found:** respond with the title ID; frontend navigates to `/titles/<id>`
- **Not found:** frontend falls through to the normal `/api/search` call and renders the overlay panel

---

## 8. Page layout

```
┌─────────────────────────────────────────────────────────────┐
│  [LOGO]  Home  Titles  Actresses  AV  ...              nav  │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│           Search your library                               │
│     ┌─────────────────────────────────────┐  [⚙]          │
│     │  🔍  actress, title, code, label…   │               │
│     └─────────────────────────────────────┘               │
│                                                             │
│     [advanced controls bar — hidden by default]            │
│                                                             │
│  ┌─ overlay panel, anchored below search box ─────────┐    │
│  │  Actresses  (8)                  see all 8 →        │    │
│  │  ...                                               │    │
│  │  Titles  (5)                     see all 5 →        │    │
│  │  ...                                               │    │
│  │  Labels  (1)                                       │    │
│  │  ...                                               │    │
│  └────────────────────────────────────────────────────┘    │
│                                                             │
│  [home page portal content below — separate design]        │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 9. Deferred / out of scope

- **Home page portal content** — the modules below the search box are a separate design effort
- **Dedicated search results page** (`/search`) — scoped entity results with pagination and sort; referenced here but designed separately
- **Search history dropdown** — recent queries shown when the box is focused empty; a nice affordance, deferred to a later pass
- **FTS5 upgrade** — full-text search index if `LIKE` queries prove too slow at scale
- **Tag / genre search** — if a tagging layer is added to the schema in a future phase

---

## 10. Open questions

None. All design decisions locked.
