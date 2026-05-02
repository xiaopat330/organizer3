# AV Stars — Web UI Design

> **Status: FULLY IMPLEMENTED**
> All phases of the AV Stars web UI described in this document are implemented: actress browse grid, actress detail with IAFD profile, video grid with screenshot thumbnails, video modal with inline player, favorite/bookmark/watched controls, tag browser, screenshots-on-demand, and federated search integration. The UI follows the patterns established by the JAV dashboards.

Phase 5 of the AV Stars implementation. Specifies the web UI for browsing and interacting with AV actress and video data. Follows the patterns established by the JAV Actresses and Titles dashboards.

---

## 1. Overview

The AV section is entirely actress-oriented. Videos have no independent identity — they exist only in the context of the actress who owns them. There are no studio, label, or title browse screens.

**Core principle:** Every path through the AV UI leads through an actress. The actress detail page is the central hub; everything else (landing, search results, favorites, bookmarks) routes to it.

---

## 2. Navigation changes

### 2.1 Toolbar button

Add **AV** button to the header between Titles and Action!:

```
Actresses | Titles | AV | Action! | Terminal
```

Icon: film strip or video camera SVG. Clicking AV navigates to the AV landing island (§3).

### 2.2 Federated search

Add **AV Actresses** toggle to both search filter bars (portal and sub-nav):

```
[Actresses] [Titles] [Labels] [Studios] [AV Actresses]
```

AV actress search matches `stage_name` and `aka_names_json`. Results route to the AV actress detail page (§6). Labels and Studios toggles do not affect AV results.

---

## 3. AV landing island

Appears below the sub-nav bar when AV is the active section. Mirrors the actress-landing and title-landing island pattern.

**Buttons (first row):**

| Button | ID | Behavior |
|---|---|---|
| Dashboard | `av-dashboard-btn` | Shows AV Dashboard (§4) |
| Favorites | `av-favorites-btn` | Actress grid filtered to `favorite = 1` |
| Bookmarks | `av-bookmarks-btn` | Actress grid filtered to `bookmark = 1` |
| Index | `av-index-btn` | Full filterable actress grid (§5) |

No secondary expansion rows needed at this time.

**Hash routing:**
- `#av` → landing island visible, AV Dashboard shown by default
- `#av/favorites` → Favorites grid
- `#av/bookmarks` → Bookmarks grid
- `#av/index` → Index grid
- `#av/actress/:id` → Actress detail page
- `#av/video/:id` → Video detail page

---

## 4. AV Dashboard

**Blank slate for Phase 5.** Render a placeholder `<div id="av-dashboard">` with a short "coming soon" text or an empty state. No data queries.

Future: activity feed, recently visited actresses, recently added videos, watch statistics.

---

## 5. AV Index (actress grid)

Filterable, paginated grid of all AV actresses. Entry point for browsing the full library.

### 5.1 Grid card

Each card shows:
- Headshot (from `headshot_path`; generic silhouette placeholder if null)
- Stage name
- Video count
- Active years (`active_from`–`active_to`)
- Top 3 tags (most frequent tags across her videos, derived from `av_video_tags`)
- Favorite/bookmark indicators

Clicking a card navigates to the actress detail page (§6).

### 5.2 Filter bar

Above the grid:

| Control | Function |
|---|---|
| Text search | Filter by `stage_name` (client-side against loaded set, or server-side API) |
| Tag multi-select | Filter to actresses who have at least one video tagged with selected tags |
| Sort | Video count desc (default), name A-Z, recently added, last visited |
| Resolved only toggle | Show only actresses with `iafd_id IS NOT NULL` |

### 5.3 API

`GET /api/av/actresses` — returns all actresses with `video_count`, `headshot_path`, `active_from`, `active_to`, `favorite`, `bookmark`, `top_tags[]`.

---

## 6. AV Actress detail page

The central hub. Layout mirrors the JAV actress detail page: left column profile, right column video grid.

### 6.1 Left column — profile card

Populated from `av_actresses` IAFD fields. The card degrades gracefully — sections with no data simply don't render. Before `av resolve` has been run, only the stage name, video count, and any filename-derived data (active years, studio) are shown. After resolution the full IAFD profile appears. No explicit "unresolved" state messaging is needed; the card is just sparse until enriched. An "Resolve on IAFD" action link may be shown when `iafd_id IS NULL` to prompt resolution.

**Header:**
- Headshot (full size; silhouette placeholder if null)
- Stage name (h2)
- AKA list (collapsed by default if > 3 names)
- Favorite / Bookmark action buttons

**Bio section:**
- Date of birth, birthplace, nationality, ethnicity
- Height, weight, measurements, cup
- Hair color, eye color
- Tattoos, piercings
- Active years (`active_from`–`active_to`)

**Stats:**
- IAFD title count (`iafd_title_count`)
- Local video count (`video_count`)
- Total size

**Links:**
- Website URL
- Social links (rendered as icon links from `social_json`; icons: Twitter/X, Instagram, TikTok, etc.)
- Platform links (rendered from `platforms_json`; icons: OnlyFans, ManyVids, etc.)
- External refs (EGAFD, StashDB links from `external_refs_json`)

**Visit tracking** (bottom of profile card):
- Last visited: `last_visited_at` formatted as relative time
- Visit count: `visit_count`

Updated via `POST /api/av/actresses/:id/visit` on a 5-second debounce timer (same pattern as JAV actress detail).

**Awards** (collapsible section):
- Rendered from `awards_json` grouped by org
- Winner entries bolded; nominee entries normal weight
- Year shown as section header per group

**Comments** (collapsible section, shown only if `iafd_comments_json` non-empty):
- IAFD editorial notes, rendered as bullet list

### 6.2 Right column — video grid

**Filter bar (above video grid):**

| Control | Function |
|---|---|
| Tag multi-select | Filter videos by tag |
| Bucket filter | Filter by `bucket` (all / keep / new / old / etc.) |
| Sort | Added date desc (default), name A-Z, file size desc, watch count desc, last watched |
| Watched toggle | Show all / unwatched only / watched only |
| Favorites first toggle | Pin `favorite = 1` videos to top |
| Bookmarks first toggle | Pin `bookmark = 1` videos to top |

**Video card:**
- Thumbnail: first screenshot from `av_video_screenshots` if available; actress headshot as placeholder if no screenshots exist yet
- Title: `parsed_title` if available, else `filename` (truncated)
- Tags: rendered as small tag badges (from `tags_json`)
- Studio + release date (if parsed)
- File info: resolution, size
- Watch status indicator (watched / unwatched dot)
- Favorite / bookmark icons

Clicking a video card navigates to the video detail page (§7).

### 6.3 API

- `GET /api/av/actresses/:id` — full profile
- `GET /api/av/actresses/:id/videos` — videos for this actress, accepts filter/sort params
- `POST /api/av/actresses/:id/visit` — record visit, returns `{visitCount, lastVisitedAt}`
- `PATCH /api/av/actresses/:id` — update `favorite`, `bookmark`, `grade`, `notes`

---

## 7. AV Video detail page

Mirrors the JAV title detail page structure. Entry point: clicking a video card on the actress detail page or from bookmarks/favorites lists.

### 7.1 Layout

Left panel:
- Screenshot carousel (up to N screenshots from `av_video_screenshots`; actress headshot placeholder if none)
- "No screenshots — run `av screenshots <actress>`" hint text if none

Right panel:
- **Title:** `parsed_title` or `filename`
- **Actress link** (routes back to actress detail)
- **File info:** filename, size, resolution, codec, extension, bucket
- **Release info:** studio, release date (if parsed)
- **Tags:** rendered as tag badges; each tag is clickable to filter actress video grid
- **Watch status:** last watched timestamp, watch count; Mark Watched button
- **Favorite / Bookmark** toggle buttons (actress-scoped — affect ordering on actress detail page only)
- **Playback:** SMB path display + Open button (same VLC/IINA deep-link mechanism as JAV titles)
- **More from this actress:** horizontal strip of video cards (5–8 items, same-actress random sample)

### 7.2 Visit / watch tracking

**Passive view counting** — on page open, a 5-second debounce timer fires `POST /api/av/videos/:id/watch`, which increments `watch_count` and sets `last_watched_at`. This records that the video was opened, not that it was completed.

**Explicit watched flag** — `watched = 1` is only set via the **Mark Watched** button (or toggled off via **Mark Unwatched**). It is not automatically set by the debounce timer. This mirrors the distinction between visit-counting and intentional completion marking.

UI updates `watch_count` and `last_watched_at` display after the debounce fires; updates the watched indicator after explicit toggle.

### 7.3 API

- `GET /api/av/videos/:id` — full video record
- `POST /api/av/videos/:id/watch` — record watch, returns `{watchCount, lastWatchedAt}`
- `PATCH /api/av/videos/:id` — update `favorite`, `bookmark`, `watched`

---

## 8. Screenshot pipeline

### 8.1 CLI command

```
av screenshots <actress-name>
```

Finds all `av_videos` for the matched actress where no screenshots exist yet (no rows in `av_video_screenshots`), then for each video:
1. Resolves the video's SMB path via `VolumeFileSystem`
2. Streams the file to a local temp file via smbj (the project has no OS mount points — ffmpeg cannot read directly from an smbj stream)
3. Uses ffprobe on the temp file to get duration, then invokes ffmpeg to extract 10 frames at evenly-spaced percentage offsets (5%, 15%, 25%, 35%, 45%, 55%, 65%, 75%, 85%, 95%)
4. Saves frames to `data/av_screenshots/<video_id>/<seq>.jpg`
5. Inserts rows into `av_video_screenshots`
6. Deletes the temp file

Progress is printed per-video. Skips videos that already have screenshots.

### 8.2 Schema additions

```sql
CREATE TABLE av_video_screenshots (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    av_video_id INTEGER NOT NULL REFERENCES av_videos(id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL,          -- 0-based frame index
    path        TEXT NOT NULL,             -- absolute local path
    UNIQUE(av_video_id, seq)
);

CREATE INDEX idx_av_video_screenshots_video ON av_video_screenshots(av_video_id);
```

### 8.3 Screenshot serving

`GET /api/av/screenshots/<video_id>/<seq>.jpg` — serves the file from `data/av_screenshots/`. Returns 404 if not yet generated.

---

## 9. Schema additions (beyond §8.2)

All additions go into a new migration `SchemaUpgrader.applyV12()`.

### 9.1 Visit + watch tracking on existing tables

```sql
-- av_actresses (last_visited_at, visit_count added; favorite/bookmark/rejected already exist)
ALTER TABLE av_actresses ADD COLUMN last_visited_at TEXT;
ALTER TABLE av_actresses ADD COLUMN visit_count     INTEGER NOT NULL DEFAULT 0;

-- av_videos (favorite/rejected already exist; adding bookmark + watch fields)
ALTER TABLE av_videos ADD COLUMN bookmark        INTEGER NOT NULL DEFAULT 0;
ALTER TABLE av_videos ADD COLUMN watched         INTEGER NOT NULL DEFAULT 0;
ALTER TABLE av_videos ADD COLUMN last_watched_at TEXT;
ALTER TABLE av_videos ADD COLUMN watch_count     INTEGER NOT NULL DEFAULT 0;
```

Column naming follows the existing convention on both tables (`favorite`, `bookmark`, `rejected` — no `is_` prefix).

### 9.2 Normalized tags

```sql
CREATE TABLE av_tag_definitions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    slug         TEXT NOT NULL UNIQUE,   -- canonical tag key, e.g. "creampie"
    display_name TEXT NOT NULL,          -- human label, e.g. "Creampie"
    category     TEXT                    -- optional grouping: "act", "role", "quality", etc.
);

CREATE TABLE av_video_tags (
    av_video_id  INTEGER NOT NULL REFERENCES av_videos(id) ON DELETE CASCADE,
    tag_id       INTEGER NOT NULL REFERENCES av_tag_definitions(id),
    source       TEXT NOT NULL DEFAULT 'parsed',   -- "parsed" | "manual"
    PRIMARY KEY (av_video_id, tag_id)
);

CREATE INDEX idx_av_video_tags_video ON av_video_tags(av_video_id);
CREATE INDEX idx_av_video_tags_tag   ON av_video_tags(tag_id);
```

Tag definitions are populated from the canonical tag set (see §10). The `av_video_tags` table is populated by `av parse filenames` (source = "parsed") or future manual tagging (source = "manual").

---

## 10. Tag taxonomy

Tags are derived from filename parsing (§6 of PROPOSAL_AV_STARS.md) plus trailing `[tag, tag]` blocks.

### 10.1 Corpus-first workflow

The canonical tag set is authored after analyzing the real corpus — not seeded from assumptions. The workflow:

1. Run `av parse filenames` against the full corpus (mounted volume required)
2. Run `av tags dump` to emit all distinct raw tokens with frequency counts (see §10.2)
3. Review the output, author `data/av_tags.yaml` defining canonical slugs, display names, categories, and aliases
4. Run `av tags apply` (or re-run `av parse filenames`) to populate `av_video_tags` from the yaml

This ensures normalization is accurate from the start rather than growing incrementally with gaps.

### 10.2 `av tags dump` command

Prints all distinct raw tokens extracted from `av_videos.tags_json` across the full library, sorted by frequency descending. Output is tab-separated `count\ttoken`, suitable for piping to a file for review.

```
av tags dump
```

Example output:
```
342    creampie
289    anal
201    dp
198    double penetration
...
```

After reviewing the dump, `double penetration` and `dp` would both appear — these get collapsed into one canonical slug in `av_tags.yaml`.

### 10.3 `data/av_tags.yaml` format

```yaml
- slug: dp
  display_name: Double Penetration
  category: act
  aliases:
    - dp
    - double penetration
    - double-penetration
    - doublepenetration

- slug: creampie
  display_name: Creampie
  category: act
  aliases:
    - creampie
    - cream pie
```

### 10.4 Normalization rules

- Raw tokens are matched case-insensitively against all aliases across all tag definitions
- A match inserts a row into `av_video_tags` with `source = 'parsed'`
- Unmatched tokens remain in `av_videos.tags_json` only and are invisible to the tag filter UI
- The yaml is the source of truth — re-running the normalization pass is idempotent
- The canonical set is extended by adding entries to `av_tags.yaml` and re-running the normalization pass

---

## 11. Backend route additions

All new routes under `/api/av/`:

| Method | Path | Description |
|---|---|---|
| GET | `/api/av/actresses` | List all actresses (grid data) |
| GET | `/api/av/actresses/search` | Federated search endpoint |
| GET | `/api/av/actresses/:id` | Single actress profile |
| GET | `/api/av/actresses/:id/videos` | Videos for actress (filterable) |
| POST | `/api/av/actresses/:id/visit` | Record actress visit |
| PATCH | `/api/av/actresses/:id` | Update actress curation fields |
| GET | `/api/av/videos/:id` | Single video detail |
| POST | `/api/av/videos/:id/watch` | Record video watch |
| PATCH | `/api/av/videos/:id` | Update video curation fields |
| GET | `/api/av/tags` | List canonical tag definitions |
| GET | `/api/av/screenshots/:videoId/:seq` | Serve screenshot image |

---

## 12. JavaScript modules (new files)

```
public/modules/
  av-browse.js          AV landing island + index grid logic
  av-actress-detail.js  AV actress detail page rendering
  av-video-detail.js    AV video detail page rendering
```

All three follow the same module pattern as their JAV counterparts (`actress-browse.js`, `actress-detail.js`, `title-detail.js`).

---

## 13. Phasing

### Phase 5a — Navigation shell + blank dashboard
1. Add AV toolbar button → AV landing island → AV Dashboard (blank slate)
2. Hash routing for `#av`, `#av/favorites`, `#av/bookmarks`, `#av/index`
3. AV landing island HTML + CSS
4. Sub-nav AV Actresses search toggle wired up (results list but route to placeholder)

### Phase 5b — Index + actress detail
1. `GET /api/av/actresses` endpoint
2. AV Index grid (actress cards, sort, text search)
3. AV Actress detail page (profile card + video grid)
4. Visit tracking (`POST /api/av/actresses/:id/visit`)
5. Schema v12 (visit/watch columns)
6. Favorites / bookmarks curation (actress-level)

### Phase 5c — Video detail + watch tracking
1. `GET /api/av/videos/:id` endpoint
2. AV Video detail page
3. Watch tracking (`POST /api/av/videos/:id/watch`)
4. Video favorites / bookmarks (actress-scoped ordering)
5. SMB playback deep-link

### Phase 5d — Screenshots
1. `av_video_screenshots` schema
2. `AvScreenshotsCommand` CLI (`av screenshots <actress>`)
3. Screenshot serving endpoint
4. Screenshot carousel on video detail page; placeholder logic

### Phase 5e — Tag taxonomy + filter
1. `av_tag_definitions` + `av_video_tags` schema
2. `av tags dump` CLI command — emit raw token frequencies for corpus analysis
3. Author `data/av_tags.yaml` from dump output (manual step, requires mounted volume)
4. Tag normalization pass — populate `av_video_tags` from yaml
5. Tag filter on actress detail video grid
6. Tag filter on AV Index grid

### Phase 5f — Federated search integration
1. AV actress search results in portal and sub-nav
2. Route to AV actress detail from search results

---

## 14. Out of scope for Phase 5

- AV video search (no standalone video browse screen)
- Studio / label / network browse pages
- Cross-referencing AV videos with IAFD filmography credits
- IAFD filmography ingestion (`av_iafd_credits` table)
- AV Dashboard content (remains blank slate)
