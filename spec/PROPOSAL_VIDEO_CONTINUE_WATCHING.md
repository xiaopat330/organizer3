# PROPOSAL — Playback-Position Persistence (Resume) + "Continue Watching" Home Shelf

**Status:** Draft / awaiting review. This is a SPEC ONLY — no code has been written.
**Phase:** Video work Phase 2 (resume + watch history; see MEMORY `project_video_phases`).
**Author:** architecture pass, 2026-05-28.
**Scope guard:** v2 + backend feature. Legacy UI (`modules/*.js` outside `v2/`/`chrome/`) is NOT touched.

> The §2 design questions below are presented as **explicit choices with a recommendation**. The
> user decides §2 before any build begins. Everything downstream (§3–§5) is written against the
> recommended options; if a different option is chosen, those sections adjust accordingly.

---

## §1 Current state (what exists today)

**Watch history is an append-only EVENT LOG — it has no position data.**
- Table `watch_history (id, title_code, watched_at)` — `SchemaInitializer.java:202-210`. There is a
  UNIQUE index on `(title_code, watched_at)` (`SchemaInitializer.java:210`, added by the v13 migration
  `SchemaUpgrader.java:1615`). No `position`, `duration`, or `completed` column.
- Repo: `WatchHistoryRepository` (`repository/WatchHistoryRepository.java`) +
  `JdbiWatchHistoryRepository` (`repository/jdbi/JdbiWatchHistoryRepository.java`). It `record()`s one
  row per watch event (`JdbiWatchHistoryRepository.java:27-45`).
- Routes: `WatchHistoryRoutes` (`web/routes/WatchHistoryRoutes.java`) — `POST /api/watch-history/{titleCode}`
  (line 25), `GET /api/watch-history` (line 41), `GET /api/watch-history/{titleCode}` (line 51).
- Backup: watch events participate in `UserDataBackup` via `WatchHistoryEntry`
  (`backup/WatchHistoryEntry.java`); `UserDataBackupService` exports/imports them
  (`backup/UserDataBackupService.java:72-73, 166-168, 273-278`).

**The v2 player has NO resume capability.**
- Player markup: `<video … src="/api/stream/${v.id}" preload="none">` —
  `v2/title-detail.js:365-367`.
- On `play`, it fires `POST /api/watch-history/{code}` to log the event — `v2/title-detail.js:388-391`.
- `seekVideo()` sets `player.currentTime = dur * fraction` — but that is the **thumbnail-scrubber seek**
  (clicking a preview thumbnail), NOT resume — `v2/title-detail.js:446-453`.
- There is no `timeupdate`/`pause`/`ended`/`pagehide` handler and no persisted position anywhere
  (grep-confirmed during orientation).

**Streaming is per-VIDEO, but watch history is per-TITLE.**
- A single `title_code` can have MULTIPLE video files. The `videos` table has per-file rows:
  `id, title_id, volume_id, filename, path, duration_sec, …` — `SchemaInitializer.java:118-132`.
- Streaming endpoint is `GET /api/stream/{videoId}` — `web/routes/VideoRoutes.java:45`, mapping
  `videoId → Video` via `VideoStreamService.findVideoById`.
- Therefore resume position **must be tracked per-video**, not per-title.

**Home shelves.**
- `v2/home.js` renders 4 shelves: Recently viewed · Recently added · Favorites · Needs attention
  (`mountHome` scaffold at `v2/home.js:246-327`). Recently-viewed is a thumb-chip row driven by the
  titles dashboard payload (`renderRecentlyViewed`, `v2/home.js:30-51`).

**Watchlist is OUT OF SCOPE (redundant).**
Titles already carry both a `favorite` and a `bookmark` flag, each with its own view mode in the Titles
dashboard (`v2/titles/index.js`). Bookmark already functions as a "watch later" list. A separate
watchlist would duplicate this and is explicitly **not** part of this proposal. (If review surfaces a
distinct need — e.g. queue ordering — it would be a separate spec.)

---

## §2 Data model — the design decisions (DECIDE THESE FIRST)

### Decision 1 — Position storage model  ★ RECOMMENDATION: new table

| Option | Description | Verdict |
|--------|-------------|---------|
| (a) Extend `watch_history` | Add `position_sec`/`duration_sec`/`completed` columns. | **Reject.** `watch_history` is an append-only event log with a UNIQUE `(title_code, watched_at)` index (`SchemaInitializer.java:210`). "Latest playback position" is an *upserted single value*, which fights that grain: you'd either accumulate one row per timeupdate (log spam) or have to mutate historical event rows. It is also per-title, but position is per-video. Poor fit on every axis. |
| (b) **New `playback_position` table** | One upserted row per video, holding the latest position. | **Recommend.** Clean separation: `watch_history` stays the immutable "what/when did I watch" log; `playback_position` is the mutable "where am I" state. Upsert semantics fit a UNIQUE key naturally. |

**Recommended schema (option b):**

```sql
CREATE TABLE IF NOT EXISTS playback_position (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    title_code   TEXT    NOT NULL,        -- denormalized for Continue-Watching joins + survives re-sync
    volume_id    TEXT    NOT NULL,        -- part of the stable resume key (see Decision 2)
    video_path   TEXT    NOT NULL,        -- relative path within the volume (videos.path); stable key
    filename     TEXT    NOT NULL,        -- display only (e.g. "h265/foo.mp4")
    position_sec REAL    NOT NULL,        -- last playback offset in seconds
    duration_sec REAL,                    -- known duration at save time (nullable; from probe)
    updated_at   TEXT    NOT NULL,        -- ISO LocalDateTime; drives Continue-Watching ordering
    UNIQUE (volume_id, video_path)        -- one position per physical file
);
CREATE INDEX IF NOT EXISTS idx_playback_position_updated ON playback_position(updated_at);
CREATE INDEX IF NOT EXISTS idx_playback_position_title  ON playback_position(title_code);
```

**Migration version: V65.** `SchemaUpgrader.CURRENT_VERSION` is currently `64` (`SchemaUpgrader.java:27`);
the last dispatch block is `if (version < 64)` (`SchemaUpgrader.java:346-349`). The next version is **65**.

Two coordinated edits (both idempotent — `CREATE TABLE IF NOT EXISTS`):
1. **Fresh installs** — add the `CREATE TABLE`/indexes to `SchemaInitializer.java` (next to the
   `watch_history` block at lines 202-210). Fresh installs stamp `user_version` to the current max, so
   they never run the upgrader for already-present tables.
2. **Existing DBs** — bump `CURRENT_VERSION` to `65`, add the dispatch block, and add `applyV65()`:

```java
// in upgrade(), after the version < 64 block:
if (version < 65) {
    applyV65();
    setVersion(65);
}

private void applyV65() {
    log.info("Applying migration v65: playback_position table (resume + continue-watching)");
    jdbi.useHandle(h -> {
        h.execute("""
                CREATE TABLE IF NOT EXISTS playback_position (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    title_code   TEXT    NOT NULL,
                    volume_id    TEXT    NOT NULL,
                    video_path   TEXT    NOT NULL,
                    filename     TEXT    NOT NULL,
                    position_sec REAL    NOT NULL,
                    duration_sec REAL,
                    updated_at   TEXT    NOT NULL,
                    UNIQUE (volume_id, video_path)
                )""");
        h.execute("CREATE INDEX IF NOT EXISTS idx_playback_position_updated ON playback_position(updated_at)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_playback_position_title  ON playback_position(title_code)");
    });
}
```

### Decision 2 — Granularity / resume key  ★ RECOMMENDATION: `(volume_id, video_path)`, not `video_id`

Resume must be per-video (titles have multiple files — see §1). The question is **what key identifies a
video durably**.

| Key | Pro | Con |
|-----|-----|-----|
| `video_id` (FK to `videos.id`) | Simplest; matches `/api/stream/{videoId}`. | **`videos.id` is NOT stable across re-sync.** `JdbiVideoRepository.save()` uses a plain `INSERT … AUTOINCREMENT` for new rows (no `ON CONFLICT` that preserves id — `JdbiVideoRepository.java:80-106`), and the sync paths `deleteByTitle`/`deleteByVolume` drop+reinsert rows (`JdbiVideoRepository.java:261-272`). A re-sync would orphan/lose every saved position. **Verified during orientation.** |
| **`(volume_id, video_path)`** ★ | Survives re-sync: the SMB-relative path is the file's true identity and is re-derived on every sync. | Slightly more plumbing — the position endpoints take a `videoId`, resolve it to `volume_id`+`path` via `VideoStreamService.findVideoById`, and key on those. |

**Recommendation:** key on `(volume_id, video_path)`. The API surface still accepts a `videoId` for
ergonomics (the player already knows it), but the persisted key is the stable path pair. `title_code`
is denormalized into the row so Continue-Watching can render covers without a join back to a possibly-stale
`videos.id`.

**Which video does title-detail resume when several exist?** The detail page renders one player per video.
Each player independently looks up its own saved position by its `videoId` (resolved server-side to the
path key) and seeks on `loadedmetadata`. There is no "pick one video for the title" problem at the
detail level — every file resumes itself. (Continue-Watching on home picks the single most-recently-updated
video for a title; see Decision 4 + §4.)

### Decision 3 — Save cadence

Write the position on:
- **`timeupdate`, throttled to once every 10s** of wall time (the event fires ~4×/sec; throttle in JS).
  10s bounds DB write volume while keeping resume accuracy within ~10s — acceptable for resume UX.
- **`pause`** — immediate write (user likely stepping away).
- **`pagehide`** — final write via **`navigator.sendBeacon`** (NOT `fetch`; fetch from an unload handler
  is unreliable and breaks bfcache). Prefer `pagehide` over `beforeunload` (works with bfcache/mobile).
  This means the upsert endpoint must accept a JSON body (sendBeacon sends `Content-Type: text/plain` or
  a Blob — the route parses the body rather than relying on a JSON content-type header).

**When position is CLEARED:**
- On **`ended`**, or when `position_sec >= 90%` of `duration_sec` → DELETE the row (treat as
  "finished" — it should not reappear in Continue Watching, and reopening the title starts from 0).
- A small **floor**: do not persist if `position_sec < 30s` AND `< 2%` of duration (avoids cluttering
  with accidental opens). Below the floor, also no row is written.

The existing `POST /api/watch-history/{code}` on `play` (`v2/title-detail.js:388-391`) stays **as-is** —
the event log is independent and additive. Position tracking does not replace or modify it.

### Decision 4 — "In progress" definition (Continue Watching eligibility)

A video qualifies for Continue Watching when its `playback_position` row satisfies:
- **floor:** `position_sec > 30` OR `position_sec > 0.02 * duration_sec` (started watching), AND
- **ceiling:** `duration_sec IS NULL OR position_sec < 0.90 * duration_sec` (not finished).

(Rows past the ceiling are deleted at save time per Decision 3, so the ceiling check is a belt-and-braces
guard for rows where `duration_sec` was unknown when saved.)

- **Ordering:** `updated_at DESC` (most recently watched first).
- **Grouping:** one card per `title_code` — if a title has multiple in-progress videos, show the
  most-recently-updated one (its card click resumes that specific file).
- **Cap:** top **N = 12** (matches the Favorites shelf cap, `v2/home.js:68`).

### Decision 5 — Continue Watching placement on home

- **Placement:** a new shelf **above "Recently viewed"** — Continue Watching is the highest-intent
  action ("pick up where I left off") and belongs first. Order becomes:
  **Continue Watching · Recently viewed · Recently added · Favorites · Needs attention.**
- **Cap:** 12 cards (Decision 4).
- **Card contents:** cover image + a **resume progress bar** (`position_sec / duration_sec`) + **time
  remaining** label (e.g. "23 min left"). Reuse the title-card cover treatment; the progress bar is a
  thin overlay at the card bottom.
- **Click target:** navigates to `v2-title-detail.html?code={code}&resumeVideoId={videoId}` so the
  detail page knows which file to scroll-to/auto-seek. (Without the hint, the per-video resume in §4
  still works — every player seeks itself on load — so the param is an enhancement, not a requirement.)
- **Empty state:** if no in-progress videos, render nothing (omit the shelf header) so the home page
  doesn't show a dead empty band.

### Decision 6 — Backup/restore  ★ RECOMMENDATION: include

| Option | Verdict |
|--------|---------|
| Include `playback_position` in `UserDataBackup` | **Recommend.** It is durable user state of the same character as watch history, favorites, and grades — all of which are backed up (`UserDataBackupService.java`). Resume positions are cheap to serialize and annoying to lose on a restore. Use an `insertOrIgnore`/upsert path keyed on `(volume_id, video_path)` to make restore idempotent, mirroring `watch_history`'s `insertOrIgnore` (`JdbiWatchHistoryRepository.java:113-125`). |
| Exclude | Simpler, but inconsistent with every other piece of user state and loses resume on restore. |

Defer to **Wave 3** so Waves 1–2 ship without touching the backup schema.

---

## §3 Backend

All new code is modular and testable. **Repository tests use real in-memory SQLite; route tests use
Mockito mocks** (CLAUDE.md "Testing is mandatory"). Wired manually in `Application.java` (no Spring).

### Model
`model/PlaybackPosition.java` — a Lombok `@Builder` record/class mirroring `WatchHistory`:
`id, titleCode, volumeId, videoPath, filename, positionSec, durationSec, updatedAt`.

### Repository — `PlaybackPositionRepository` (+ `jdbi/JdbiPlaybackPositionRepository`)
Patterned on `WatchHistoryRepository`:

```java
public interface PlaybackPositionRepository {
    /** Upsert the latest position for a physical video (keyed on volumeId+videoPath). */
    void upsert(String titleCode, String volumeId, String videoPath, String filename,
                double positionSec, Double durationSec, LocalDateTime updatedAt);

    /** Latest position for one physical video, if any. */
    Optional<PlaybackPosition> find(String volumeId, String videoPath);

    /** Delete the position (called on completion / ended). */
    void delete(String volumeId, String videoPath);

    /** In-progress videos for Continue Watching: floor<pos<ceiling, ordered updated_at DESC, capped. */
    List<PlaybackPosition> findContinueWatching(int limit);

    /** Backup export — all rows. */
    List<PlaybackPosition> findAllEntries();

    /** Idempotent restore insert. Returns true if inserted. */
    boolean insertOrIgnore(PlaybackPosition entry);
}
```

- `upsert` → `INSERT … ON CONFLICT(volume_id, video_path) DO UPDATE SET position_sec=…, duration_sec=…,
  updated_at=…` (SQLite UPSERT against the UNIQUE index).
- `findContinueWatching` → the Decision-4 predicate, `GROUP BY title_code` taking the max `updated_at`
  row, `ORDER BY updated_at DESC LIMIT :limit`.

### Routes — `PlaybackPositionRoutes` (`web/routes/`)
Patterned on `WatchHistoryRoutes`. Endpoints take a `videoId` (the player knows it) and resolve it to the
path key via `VideoStreamService.findVideoById` (already a dependency of the video stack).

| Method + path | Body | Behavior |
|---------------|------|----------|
| `PUT /api/playback-position/{videoId}` | `{ "positionSec": N, "durationSec": M }` (parsed leniently — sendBeacon may send `text/plain`) | Resolve video → `volume_id`+`path`+`title_code`+`filename`; apply floor/ceiling (Decision 3); upsert or delete; 204. 404 if video unknown. |
| `DELETE /api/playback-position/{videoId}` | — | Delete the row (explicit "start over" / clear). |
| `GET /api/playback-position/{videoId}` | — | `{ positionSec, durationSec, updatedAt }` or 404 if none. |
| `GET /api/playback-position/continue-watching?limit=12` | — | JSON list for the home shelf: `[{ titleCode, coverUrl, videoId, positionSec, durationSec, updatedAt }]`. Cover URL resolved the same way the titles dashboard resolves `coverUrl` for recently-viewed. |

`videoId` in the continue-watching response is re-resolved from `(volume_id, video_path)` at read time
(it may differ from the saved-time id after a re-sync — that's the point of the stable key). If the file
no longer resolves to a live video row, omit it from the shelf.

### Wiring (`Application.java`)
- Instantiate `PlaybackPositionRepository playbackPositionRepo = new JdbiPlaybackPositionRepository(jdbi);`
  next to `watchHistoryRepo` (`Application.java:291`).
- Register `PlaybackPositionRoutes` alongside the video/watch-history routes (~`Application.java:710`).
- Wave 3: add `playbackPositionRepo` to the `UserDataBackupService` constructor
  (`Application.java:387`).

---

## §4 Frontend (v2 only)

### Player resume wiring — `v2/title-detail.js`
At the per-video player setup (currently `v2/title-detail.js:384-391`), add for each player:

1. **Resume on load** — on `loadedmetadata` (NOT `play`, so we can set `currentTime` before playback):
   ```js
   const pos = await fetchJson(`/api/playback-position/${v.id}`, null);
   if (pos && pos.positionSec > 30) player.currentTime = pos.positionSec;
   ```
   If `resumeVideoId` query param matches `v.id`, also `scrollIntoView()` + (optionally) `play()`.
2. **Save throttled on `timeupdate`** — a 10s throttle gate, `PUT /api/playback-position/{v.id}` with
   `{ positionSec, durationSec }`.
3. **Save on `pause`** — immediate `PUT`.
4. **Save on `pagehide`** — `navigator.sendBeacon('/api/playback-position/'+v.id, blob)` with the JSON
   body. (Register one document-level `pagehide` that flushes all visible players.)
5. **`ended`** — `PUT` with the final position; the server deletes the row (Decision 3 ceiling).

The existing watch-history `POST` on `play` (`v2/title-detail.js:388-391`) is **unchanged**.

### Continue Watching shelf — `v2/home.js`
- Add a new `<section class="shelf">` **before** the "Recently viewed" section in the `mountHome`
  scaffold (`v2/home.js:254-260`), with slot id `#shelf-continue`.
- Add `renderContinueWatching(slot)`:
  ```js
  const items = await fetchJson('/api/playback-position/continue-watching?limit=12', []);
  if (!items.length) { slot.closest('.shelf').remove(); return; }   // hide when empty
  // render cards: cover + progress bar (positionSec/durationSec) + "<N> min left"
  // each card href = /v2-title-detail.html?code=<code>&resumeVideoId=<videoId>
  ```
  Reuse the cover/escape helpers already in `home.js` (`escapeHtml`, `v2/home.js:23`). The progress bar
  is a thin div overlay; styling lives in the existing v2 shelf CSS (new classes `home-resume-bar` /
  `home-resume-fill`).
- Call `renderContinueWatching(continueSlot)` independently in `mountHome` (like `renderFavorites`,
  `v2/home.js:323`) so it doesn't block the dashboard-driven shelves.

---

## §5 Implementation waves (vertical, independently merge-able)

### Wave 1 — Position persistence + resume-on-load  (useful standalone)
**Goal:** play a video, leave, come back → it resumes where you left off.
**Files:** `SchemaInitializer.java` (+table), `SchemaUpgrader.java` (V65 + `CURRENT_VERSION=65`),
`model/PlaybackPosition.java`, `repository/PlaybackPositionRepository.java`,
`repository/jdbi/JdbiPlaybackPositionRepository.java`, `web/routes/PlaybackPositionRoutes.java`,
`Application.java` (wire repo + routes), `v2/title-detail.js` (save + resume).
**Acceptance (empirical):**
- Fresh DB and migrated DB both end at `PRAGMA user_version == 65` with a `playback_position` table.
- Open a title, play ~40s, refresh the page → the player seeks to ~30–40s on load (within the 10s
  throttle window).
- Play past 90% (or let it end) → reopening the title starts at 0 (row deleted).
- Open a title, watch 5s, close → no row written (below floor).
- `pagehide` flush writes a position even without a prior `timeupdate` write (verified via the beacon
  endpoint receiving the body).
**Tests:** repo tests (in-memory SQLite) for upsert/find/delete/floor-ceiling-via-`findContinueWatching`;
route tests (Mockito) for PUT floor/ceiling/clear branches + 404 on unknown video. Migration test:
apply V64→V65 on a seeded DB asserts table exists and version bumped.

### Wave 2 — Continue Watching home shelf  (consumes Wave 1)
**Goal:** home page surfaces in-progress videos with resume progress.
**Files:** `repository`/`jdbi` (`findContinueWatching` if not already in Wave 1),
`web/routes/PlaybackPositionRoutes.java` (`/continue-watching`), `v2/home.js` (new shelf +
`renderContinueWatching`), v2 shelf CSS (progress-bar classes).
**Acceptance (empirical):**
- After Wave-1 leaving a title mid-play, the home page shows a Continue Watching shelf with that title
  first, a partial progress bar, and a "N min left" label.
- Clicking the card opens the detail page and resumes that file.
- Finishing the video removes it from the shelf on next home load.
- With zero in-progress videos, the shelf is not rendered (no empty band).
**Tests:** repo test for `findContinueWatching` ordering/cap/grouping (multiple videos per title →
one card, newest); route test (Mockito) for the continue-watching JSON shape; Playwright smoke (repo
convention: JS is covered by Playwright, not unit tests).

### Wave 3 — Backup integration + polish
**Goal:** resume positions survive backup/restore; rough edges cleaned.
**Files:** `backup/PlaybackPositionEntry.java` (new record), `backup/UserDataBackup.java` (+field),
`backup/UserDataBackupService.java` (export at ~line 72-95, restore at ~166-168, preview at ~273-332),
`Application.java:387` (add repo to constructor).
**Acceptance (empirical):**
- Backup → wipe positions → restore → positions return; restore is idempotent (re-running inserts 0).
- Restore preview counts playback positions in its category breakdown.
**Tests:** backup-service round-trip test (export then restore into a fresh in-memory DB; assert counts
and idempotency), matching the existing watch-history backup test pattern.

---

## §6 Testing

- **Repository (mandatory, real in-memory SQLite):** upsert overwrites the same `(volume_id, video_path)`
  row (not a new row); `find` returns latest; `delete` removes; `findContinueWatching` honors floor,
  ceiling, ordering (`updated_at DESC`), grouping (one row per `title_code`), and the `LIMIT`. Migration
  test: seed a DB at user_version 64, run `SchemaUpgrader`, assert table present + version 65, and assert
  re-running the upgrader is a no-op (idempotent).
- **Routes (Mockito mocks):** PUT applies floor/ceiling/clear correctly; lenient body parse (sendBeacon
  `text/plain`); 404 on unknown `videoId`; GET 404 when no row; continue-watching JSON shape and limit.
- **Backup (Wave 3):** export/restore round-trip + idempotency.
- **JS (v2):** Playwright, per repo convention — no JS unit tests. Cover: resume-on-load seek, throttled
  save, ended-clears, home shelf render + empty-state hide + card click resume.

---

## §7 Risks & open questions

1. **`video_id` instability (resolved by Decision 2).** Verified: `JdbiVideoRepository.save()` plain-INSERTs
   new rows (`:80-106`) and sync drop+reinserts (`deleteByTitle`/`deleteByVolume`, `:261-272`), so ids are
   not stable. Keying on `(volume_id, video_path)` is therefore required, not optional. **Open:** does any
   code path ever *rename/move* a video's path within a volume (organize/tier-reconcile)? If so, a position
   keyed on `video_path` is orphaned (cosmetic — resume silently resets to 0; no crash). Note the
   `videos.path` desync caveat in MEMORY (`reference_videos_path_desync`).
2. **Multi-video resume UX.** Per-video resume is unambiguous on the detail page (each player resumes
   itself). The home shelf collapses to one card per title (newest in-progress video). Confirm this is the
   desired behavior vs. one card per in-progress *video*.
3. **Throttle vs. accuracy.** 10s `timeupdate` throttle bounds DB writes; worst-case resume error is ~10s.
   `pause`/`ended`/`pagehide` writes tighten the common cases. Tune the interval if 10s feels loose.
4. **`sendBeacon` reliability + body parsing.** The PUT route must tolerate `text/plain`/Blob bodies (no
   JSON content-type). Confirm Javalin body parsing handles this; otherwise read the raw body and parse.
5. **Privacy / clearing history interplay.** If a "clear watch history" gesture exists or is added, it
   should likely **also clear `playback_position`** (the position log is the same class of personal state).
   Recommend wiring a clear-positions path alongside any history-clear. **Open** until a clear-history UX
   is specified.
6. **Floor/ceiling thresholds (30s / 2% / 90%) are proposed defaults** — confirm or tune at review.
7. **Backup payload growth.** `playback_position` is small (one row per in-progress file, completed rows
   deleted), so backup size impact is negligible.
