# Background Thumbnail Sync

## Motivation

Thumbnails today are generated on-demand when a user opens a title in the web UI. First-view latency is bad because FFmpeg has to probe duration and seek across ~10-30 keyframes over SMB. The service is otherwise idle most of the time; we can pre-warm thumbnails for content the user is likely to revisit, making subsequent visits feel instant.

Scope is deliberately narrow: **only titles the user has already shown interest in.** Discovery-time views keep the existing on-demand path. If an actress or title becomes popular, the attention signal naturally pulls it into the worker's queue — no explicit manual ranking required.

## Non-Goals

- No pre-generation for unvisited/undiscovered content.
- No AV-side (`av_videos`) integration — that pipeline already exists separately.
- No parallel FFmpeg — one video at a time (I/O-bound over SMB).
- No persisted queue — ranking is recomputed on boot and between cycles.

## Attention Signal / Ranking

Candidates are videos whose parent title has any attention signal. Ranking SQL joins `videos` → `titles` → `actresses` and scores each title:

| Signal | Source | Weight |
|---|---|---|
| Title bookmarked | `titles.bookmark = 1` | +1000 |
| Title favorited | `titles.favorite = 1` | +500 |
| Actress favorited | `actresses.favorite = 1` (any linked actress) | +500 |
| Title recently visited | `titles.last_visited_at` (exponential decay, 30-day half-life) | 0–300 |
| Actress recently visited | `actresses.last_visited_at` (same decay) | 0–200 |
| Title visit count | `titles.visit_count` (log-scaled) | 0–100 |
| Recently added | `videos.last_seen_at` within 14 days (linear decay) | 0–50 |

Titles with score = 0 are excluded. Worker takes top N (e.g. 200) per cycle, then re-ranks.

**Skip filter:** a video is considered "done" if its thumbnail directory has a `.count` marker AND the expected number of `thumb_NN.jpg` files are present. This check is cheap (stat-only) and matches `ThumbnailService`'s existing logic.

## Worker

A single `BackgroundThumbnailWorker` thread owned by `Application`. On app start, the worker waits `startup-delay-sec` (default 600s = 10 min) before its first cycle — gives the user a quiet boot, and avoids racing with any sync/index work that happens at launch.

Loop:

1. If disabled via config/toggle → sleep 60s, retry.
2. Wait for quiet period — `now - lastUserActivityMillis >= quietThresholdMs` (default 30s).
3. Load top-N ranked candidates (SQL query above). Filter out: already-complete videos, videos on unmounted volumes, videos in the in-memory fail set.
4. If queue empty → sleep `idle-sleep-sec` (default 300s), retry.
5. For each candidate:
   - Re-check quiet period before starting (abort cycle if user just acted).
   - Run generation with a hard timeout (see below).
   - On success: continue.
   - On failure: bump retry count; if count ≥ 2, add to in-memory fail set (cleared on app restart).
   - After each video, re-check quiet period.
6. Back to step 3.

**User activity detection:** a servlet filter updates a shared `AtomicLong lastUserActivityMillis` on every `/api/**` request. The worker reads this without locking.

### Hang protection

FFmpeg over SMB can hang indefinitely on corrupt files or flaky network. The worker MUST never wedge the process.

Implementation: run `generateBlocking` inside a single-thread `ExecutorService` with `Future.get(timeoutSec, SECONDS)`. On timeout:
1. `future.cancel(true)` — interrupts the worker's attempt.
2. Best-effort `grabber.stop(); grabber.release()` via a cleanup hook (needs `ThumbnailService` to expose the grabber or accept a cancellation callback).
3. Treat as failure (counts toward retry cap).
4. If the cancel doesn't actually free the FFmpeg native resources (JavaCV sometimes leaks), the thread is abandoned — the executor spawns a fresh thread next iteration. Thread leak is preferable to process freeze.

Default timeout: `generation-timeout-sec: 300` (5 min per video).

### Failure handling

- **Retry policy:** one automatic retry on failure, then the video is added to an in-memory "skip" set for the rest of the process lifetime. Fail set is cleared on app restart — gives the user a natural way to retry (restart the shell).
- **Unmounted volume:** worker consults the volume connection registry and skips any video whose `volume_id` isn't currently mounted. No auto-mount attempt — mount lifecycle stays owned by shell commands. For a user who only runs the shell every few weeks, this naturally means the worker idles between sessions, which is the desired behavior.

## Changes to `ThumbnailService`

Current code only exposes async, fire-and-forget generation via `getThumbnailStatus()`. Add:

```java
public void generateBlocking(String titleCode, Video video) throws IOException {
    Path videoDir = resolveVideoDir(titleCode, video.getFilename());
    int expected = readCountFile(videoDir).orElse(-1);
    if (expected > 0 && findCachedThumbnails(video.getId(), videoDir, expected).size() == expected) {
        return;  // already complete
    }
    String key = titleCode + "/" + video.getFilename();
    if (!generating.add(key)) return;  // another thread is on it
    try {
        generateThumbnails(video, videoDir);
    } finally {
        generating.remove(key);
    }
}
```

This reuses the existing private `generateThumbnails` method and the `generating` Set — no duplication.

## Eviction (LRU-style)

To keep disk use bounded without an explicit size cap, the worker also runs a periodic sweep deleting thumbnails for titles that have gone cold.

**Eviction rule** — a title's thumbnail directory is removed if **all** hold:
- `titles.last_visited_at` is null OR older than `eviction-days` (default 30)
- `titles.bookmark = 0`
- `titles.favorite = 0`
- No linked actress has `favorite = 1`

Favorites and bookmarks are sticky — those titles are never evicted regardless of visit recency. This prevents churn where a pre-warmed-but-unvisited favorite gets deleted then immediately regenerated.

Sweep runs once per worker cycle (cheap: filesystem scan + one SQL query per title directory). Eviction deletes the `<titleCode>/` directory and all `<videoFilename>/` subdirectories beneath it.

## Configuration

Under `organizer-config.yaml`:

```yaml
thumbnails:
  background-sync:
    enabled: false       # opt-in, default off
    quiet-threshold-sec: 30
    max-candidates-per-cycle: 200
    idle-sleep-sec: 300
    startup-delay-sec: 600       # wait 10min after boot before first cycle
    generation-timeout-sec: 300  # per-video hard timeout (hang protection)
    eviction-days: 30            # 0 disables eviction
```

Shell toggle:
- `background-thumbs on` / `off` / `status` — flips the in-memory flag, echoes queue size and last-generated video.

## Schema Changes

**None required.** All needed columns already exist:
- `titles`: `favorite`, `bookmark`, `last_visited_at`, `visit_count`
- `actresses`: `favorite`, `last_visited_at`, `visit_count`
- `videos`: `id`, `title_id`, `filename`

## Testing

- Unit test for ranking SQL with an in-memory SQLite fixture seeded with various signal combinations — verify ordering is stable and score = 0 titles are excluded.
- Unit test for the skip filter with a temp directory containing a `.count` marker + varying `thumb_NN.jpg` counts.
- Unit test for the worker loop with a mock `ThumbnailService`, mock activity clock, and a `Duration`-based test clock — verify pause-on-activity behavior and that `enabled=false` halts work.
- No FFmpeg in tests (reuse the existing approach of testing `ThumbnailService` logic without invoking JavaCV).

## Open Questions

None for v1.
