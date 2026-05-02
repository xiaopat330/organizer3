# AV Screenshot Generation Queue

> **Status: IMPLEMENTED** — shipped 2026-05-01 in PR #19 (merge commit a4f8e0f)

Proposes a persistent, FIFO background queue for generating AV video screenshots, driven by a
button on the AV actress profile screen. Replaces the purely synchronous `av screenshots <name>`
CLI command as the primary screenshot generation path for UI-initiated work.

---

## 1. Motivation

`AvScreenshotService.generateForVideo()` calls FFmpeg over HTTP, grabbing 10 frames per video.
For a large actress this is minutes of work. The current CLI command blocks the shell for its
entire duration and cannot survive an app restart. The web UI has no way to initiate bulk
screenshot generation today.

Goals:
- One-click screenshot generation from the AV actress profile screen and the Utilities → AV
  Stars detail panel (same controls in both places)
- Queue survives app restarts — work resumes from where it stopped
- Multiple actresses can be enqueued and process in order (FIFO)
- Live per-actress progress visible wherever her profile/detail is open
- Per-actress pause / resume / stop controls (no global controls in v1)
- No rate limiting — this is all local FFmpeg work

---

## 2. Database schema

New table added via `SchemaUpgrader.applyV42()` (current `CURRENT_VERSION` is 41 at
`SchemaUpgrader.java:22`; bump to 42).

```sql
CREATE TABLE av_screenshot_queue (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    av_video_id   INTEGER NOT NULL UNIQUE REFERENCES av_videos(id) ON DELETE CASCADE,
    av_actress_id INTEGER NOT NULL REFERENCES av_actresses(id),
    enqueued_at   TEXT NOT NULL,   -- ISO-8601; FIFO ordering key
    started_at    TEXT,            -- null until worker picks this row up
    completed_at  TEXT,            -- null until done or failed
    status        TEXT NOT NULL DEFAULT 'PENDING',
                                   -- (see line below)
    error         TEXT             -- populated on FAILED; null otherwise
                                   -- status set: PENDING | IN_PROGRESS | PAUSED | DONE | FAILED
);

CREATE INDEX idx_asq_status_enqueued ON av_screenshot_queue(status, enqueued_at);
CREATE INDEX idx_asq_actress         ON av_screenshot_queue(av_actress_id);
```

**`UNIQUE` on `av_video_id`** — enqueue is idempotent. Re-clicking the button for an actress
whose videos are already queued is a no-op for existing rows; only genuinely new pending videos
are inserted.

**Startup reset**: on application start, any rows with `status = 'IN_PROGRESS'` are reset to
`PENDING`. The worker may have died mid-video; re-running is safe because
`AvScreenshotService.generateForVideo()` is idempotent (returns existing screenshots if already
present).

**Retention**: `DONE` and `FAILED` rows are kept indefinitely as an audit trail. A future
`prune` step (not in scope here) can clean rows older than N days. `ON DELETE CASCADE` ensures
rows vanish automatically if the underlying video is deleted.

---

## 3. Worker

A single background daemon thread, started at application boot. Mirrors
`EnrichmentRunner` (`src/main/java/com/organizer3/javdb/enrichment/EnrichmentRunner.java`)
in lifecycle and pause semantics — that is the canonical reference.

### 3.1 Class shape

```java
public class AvScreenshotWorker {
    private final AvScreenshotQueueRepository queueRepo;
    private final AvScreenshotService screenshotService;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile Thread thread;
    private volatile Long currentVideoId;     // for /worker/state endpoint
    private volatile Long currentActressId;

    private static final long LOOP_SLEEP_MS = 1_000;     // between successful videos
    private static final long IDLE_SLEEP_MS = 5_000;     // when queue empty
    private static final long PAUSED_SLEEP_MS = 30_000;  // matches EnrichmentRunner
    private static final int  PER_VIDEO_TIMEOUT_SEC = 120;

    public synchronized void start() { ... }   // mirrors EnrichmentRunner.start()
    public synchronized void stop()  { ... }   // mirrors EnrichmentRunner.stop()
    public void setPaused(boolean on) { paused.set(on); }
    public boolean isPaused() { return paused.get(); }
}
```

### 3.2 Processing loop

```
on start():
  queueRepo.resetOrphanedInFlightJobs()    // IN_PROGRESS → PENDING
  thread = new Thread(this::runLoop, "av-screenshot-worker")
  thread.setDaemon(true)
  thread.setPriority(Thread.MIN_PRIORITY)
  thread.start()

runLoop:
  while !stopRequested:
    try { runOneStep() }
    catch InterruptedException → break
    catch Throwable → log; sleepInterruptibly(30_000)

runOneStep:
  if paused                              → sleepInterruptibly(PAUSED_SLEEP_MS); return
  if streamActivity.isPlaying(30_000)    → sleepInterruptibly(5_000); return    // see §6.2
  row = queueRepo.claimNextPending()       // atomic UPDATE → IN_PROGRESS, started_at = now
  if row == null → sleepInterruptibly(IDLE_SLEEP_MS); return
  currentVideoId = row.avVideoId; currentActressId = row.avActressId
  try:
    urls = withTimeout(PER_VIDEO_TIMEOUT_SEC, () -> screenshotService.generateForVideo(row.avVideoId))
    if urls.isEmpty() → queueRepo.markFailed(row.id, "no frames generated")
    else              → queueRepo.markDone(row.id)
  catch TimeoutException:
    queueRepo.markFailed(row.id, "timeout after " + PER_VIDEO_TIMEOUT_SEC + "s")
    // FFmpeg native thread may be wedged — replace the executor (see AvScreenshotsCommand:118-125)
  catch Throwable t:
    queueRepo.markFailed(row.id, t.getMessage())
  finally:
    currentVideoId = null; currentActressId = null
  sleepInterruptibly(LOOP_SLEEP_MS)
```

**Per-video timeout is mandatory.** `AvScreenshotsCommand` (`AvScreenshotsCommand.java:97-128`)
already learned this the hard way: a pathological file can wedge FFmpeg's native thread
indefinitely. Reuse the same single-thread executor + 120s timeout + executor-replace-on-timeout
pattern. Without it, one bad video stalls the queue forever.

### 3.3 Atomic claim

`claimNextPending()` must be a single atomic statement to be safe against the on-startup
`resetOrphanedInFlightJobs()` and concurrent shutdown:

```sql
-- inside a transaction:
UPDATE av_screenshot_queue
   SET status = 'IN_PROGRESS', started_at = :now
 WHERE id = (
     SELECT id FROM av_screenshot_queue
      WHERE status = 'PENDING'
      ORDER BY enqueued_at ASC
      LIMIT 1
 )
RETURNING *;
```

SQLite supports `UPDATE ... RETURNING` (3.35+). The JDBI driver bundled with this project is
recent enough.

### 3.4 Pause / stop semantics — per-actress only

Both pause and stop are **scoped to one actress**. There is no global pause or stop in v1; a
global control may be added later but is not currently a priority.

- **Pause actress** — transition every `PENDING` row for that actress to `PAUSED`. The worker's
  `claimNextPending()` only claims rows where `status = 'PENDING'`, so paused rows are skipped
  automatically and the worker continues processing other actresses. If the in-flight row is
  for the paused actress, it runs to completion (no mid-video cancellation), then the worker
  moves to the next eligible PENDING row.
- **Resume actress** — transition every `PAUSED` row for that actress back to `PENDING`.
- **Stop actress** — delete every `PENDING` and `PAUSED` row for that actress. The in-flight
  row, if hers, is allowed to finish.

The worker class still carries an internal `paused` flag (mirrors `EnrichmentRunner`) to keep
the door open for a future global pause control, but no HTTP endpoint exposes it in v1.

### 3.5 Per-actress progress tracking — **polling**

**Decision: polling, not SSE.** Rationale:

- The queue is long-running, not a one-shot task. SSE shines for bounded task lifecycles
  (TaskRunner emits a TaskEnded and the connection closes); a queue has no natural end.
- Profile screens come and go. Polling is stateless on the server side; SSE requires per-client
  subscription bookkeeping in the worker, which adds non-trivial concurrency surface for a
  minor UX win.
- 2–3s polling against a single SQL aggregate is cheap.

The one-shot Utilities/TaskRunner SSE pattern stays intact for those task-shaped workflows.

---

## 4. HTTP surface

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/av/actresses/{id}/screenshots/enqueue` | Add this actress's pending videos (no existing screenshots, not already in queue) to the queue. Returns `{ enqueued: N, alreadyDone: M, alreadyQueued: K }`. |
| `POST` | `/api/av/actresses/{id}/screenshots/pause` | Transition this actress's `PENDING` rows to `PAUSED`. Worker continues with other actresses. Returns `{ paused: N }`. |
| `POST` | `/api/av/actresses/{id}/screenshots/resume` | Transition this actress's `PAUSED` rows back to `PENDING`. Returns `{ resumed: N }`. |
| `DELETE` | `/api/av/actresses/{id}/screenshots/queue` | Remove all `PENDING` and `PAUSED` rows for this actress ("stop"). In-flight row is unaffected. Returns `{ removed: N }`. |
| `GET` | `/api/av/actresses/{id}/screenshots/progress` | Scoped stats for this actress: `{ pending, inProgress, paused, done, failed, total, currentVideoId }`. Drives the progress bar on both the profile screen (§5) and the Utilities-screen detail panel (§5A). `currentVideoId` is non-null only when this actress's row is the in-flight one. |
| `GET` | `/api/av/screenshot-queue/state` | Worker state: `{ running, streamActive, queueDepth, currentVideoId, currentActressId }`. `streamActive` reflects the playback-gating signal from §6.2. Path is outside `/api/av/screenshots/` to avoid colliding with the pre-existing image-serving route `/api/av/screenshots/{videoId}/{seq}`. |

**No global pause/resume endpoints in v1** — see §3.4. The worker's internal `paused` flag is
present but unexposed; a global control can be added later without schema or repository
changes.

Routes live in a new `AvScreenshotQueueRoutes` class registered in `WebServer` via a new
`registerAvScreenshotQueue(AvScreenshotQueueRoutes)` method, mirroring how `UtilitiesRoutes`
and the existing AV-stars routes are wired (see `WebServer.java:108-193`).

### 4.1 Enqueue semantics

The enqueue endpoint must replicate the "pending" definition used today by
`AvScreenshotsCommand.java:77-79`:

```java
List<AvVideo> videos  = videoRepo.findByActress(actressId);
List<AvVideo> pending = videos.stream()
    .filter(v -> screenshotRepo.countByVideoId(v.getId()) == 0)
    .toList();
```

Of those `pending`, the route then calls `queueRepo.enqueueIfAbsent(actressId, videoId)` for
each. The `UNIQUE(av_video_id)` constraint makes the insert idempotent — `INSERT ... ON
CONFLICT DO NOTHING` returns 0 rows for already-queued videos. The response counts:

- `alreadyDone` = videos with `countByVideoId > 0`
- `alreadyQueued` = videos that hit the ON CONFLICT branch
- `enqueued` = newly inserted rows

After inserting, the route notifies the worker (signal/notify on a shared monitor or simply
let the worker's idle sleep tick — 5s ceiling is fine).

---

## 5. Profile screen button (av-actress-detail.js)

The button lives in `renderActionBar()` alongside the existing favorite and bookmark buttons.

### 5.1 Button states

| Condition | Display |
|-----------|---------|
| All videos have screenshots | "Screenshots ✓" — disabled |
| Videos pending, not yet enqueued | "Generate screenshots (N pending)" — enabled |
| Actress has `PENDING` or `IN_PROGRESS` rows | Progress bar + label ("3 / 47") + **Pause** + **Stop** |
| Actress has `PAUSED` rows (and no PENDING/IN_PROGRESS) | Progress bar (paused style) + label + **Resume** + **Stop** |
| Worker is running but is gated on §6.2 playback signal AND this actress's row is currently in-flight | Same as the running state, with sub-label "paused — video playing" beneath the bar |

### 5.2 Interaction flow

1. User clicks **Generate screenshots (N pending)**
2. JS calls `POST /api/av/actresses/{id}/screenshots/enqueue`
3. On success, button transitions immediately to progress view
4. Progress bar polls `GET /api/av/actresses/{id}/screenshots/progress` every **2 seconds**
   while the host screen is mounted and `pending + inProgress + paused > 0`
5. Polling stops when:
   - `pending + inProgress == 0` → button transitions to "Screenshots ✓", videos grid refreshes
   - The user navigates away from the profile (cleanup in the screen's teardown hook)
6. On the completion transition, dispatch an `av-screenshots-generated` CustomEvent on
   `document` with `{ detail: { actressId } }`. The video grid module (already on the page)
   listens and re-fetches `/api/av/actresses/{id}/videos`. (This event does not exist today —
   the queue introduces it.)

**Pause**: calls `POST /api/av/actresses/{id}/screenshots/pause`. This actress's `PENDING`
rows transition to `PAUSED`; the worker continues with other actresses (or idles). Button
row swaps to **Resume** + **Stop**.

**Resume**: calls `POST /api/av/actresses/{id}/screenshots/resume`. `PAUSED` rows return to
`PENDING`. Button row swaps back to **Pause** + **Stop**.

**Stop**: calls `DELETE /api/av/actresses/{id}/screenshots/queue`. Removes this actress's
`PENDING` and `PAUSED` rows. Button returns to "Generate screenshots (N pending)" state.

---

## 5A. Utilities screen — AV Stars detail panel (`utilities-av-stars.js`)

The same control set lives in the right-hand detail panel of the Utilities → AV Stars screen.
When the user clicks an actress in the left list, the right panel renders her metadata; the
screenshot controls live in `renderDetail()` (`utilities-av-stars.js:150`), placed
**immediately below the favorite/bookmark toggle row** at lines 181–182. Specifically:

```
[ profile picture ]
[ stage name + metadata ]
[ ★ Favorite ]  [ 📑 Bookmark ]
[ Generate screenshots (N pending) | Pause | Resume | Stop + progress bar ]   ← new
[ rest of profile ]
```

Same five states as §5.1, same endpoints, same 2s polling cadence, same
`av-screenshots-generated` CustomEvent on completion. Implementation should be a single
shared module (e.g. `av-screenshot-controls.js`) imported by both `av-actress-detail.js` and
`utilities-av-stars.js` — duplicating the state machine across two files is a known
maintenance trap. Module exports a `mount(containerEl, actressId)` and `unmount()` for the
host screens to call from their own mount/teardown hooks.

---

## 6. Interaction with the existing CLI command

`AvScreenshotsCommand` (`av screenshots <name>`) is unchanged. It remains a direct synchronous
path that bypasses the queue entirely and is useful for scripted / MCP use. The queue and the
CLI command are independent; running one while the other is active could cause duplicate work
on the same video, but `AvScreenshotService` is idempotent so this is harmless.

### 6.1 Coexistence with `BackgroundThumbnailWorker` (JAV)

The JAV side already runs a background FFmpeg worker
(`com.organizer3.media.BackgroundThumbnailWorker`) that generates per-video thumbnails for
the JAV title library. The two workers are independent at every layer that matters for
correctness:

| Concern | JAV worker | AV screenshot worker |
|---|---|---|
| Tables | `videos`, `title_videos` | `av_videos`, `av_video_screenshots` |
| Stream endpoint | `/api/stream/{id}` | `/api/av/stream/{id}` |
| Output dir | `<dataDir>/thumbnails/...` | `<dataDir>/av_screenshots/...` |
| Executor | "bg-thumb-ffmpeg" single-thread | "av-screenshot-worker" single-thread |
| In-flight set | Private to `ThumbnailService` | Private to `AvScreenshotService` |

JavaCV instantiates a fresh `FFmpegFrameGrabber` per call, so there is no shared FFmpeg
state and no global lock between the two workers.

**Real shared resources:** CPU/disk I/O during FFmpeg work, and the local Javalin web server
(both workers stream video bytes through it). Worst case is two concurrent FFmpeg processes
plus a user playing a video — three simultaneous stream reads. On modern hardware this is
acceptable; if it ever becomes a problem the mitigation is a shared semaphore around
`generateForVideo`-style calls, not a structural change to either queue.

**General quiet-period gating — explicit decision: NO.** `BackgroundThumbnailWorker` waits
for *any* user inactivity (`UserActivityTracker.bump()` fires on every `/api/**` request)
because it runs opportunistically against the entire library; spending CPU while the user is
browsing would be rude. The AV screenshot queue is the opposite case: every entry is the
result of an explicit "Generate screenshots" click. Clicking the button **is** the user's
consent to spend resources. Gating on general inactivity would surprise the user (work
disappears the moment they touch the keyboard or the progress poller fires) and defeats the
point of the progress bar.

**Active-playback gating — explicit decision: YES.** This is narrower and orthogonal. See §6.2.

### 6.2 Pause queue while a video is playing

When the user is actively watching a video, two more concurrent FFmpeg streams competing for
the same Javalin pipeline can cause playback to stutter. The user opted into screenshot work
when they clicked the button; they did not opt into stuttering their current viewing. The
worker therefore defers between videos while playback is active.

**The existing `UserActivityTracker` cannot be reused for this** — it bumps on every
`/api/**` hit, including the queue's own 2s progress poll, which would keep the tracker
permanently "active" and the worker would never run.

**New class: `StreamActivityTracker`** — narrow signal, bumped only from the two video
stream endpoints.

```java
package com.organizer3.media;  // sits next to UserActivityTracker

public class StreamActivityTracker {
    private final AtomicLong lastStreamByteAt = new AtomicLong(0);
    public void bump() { lastStreamByteAt.set(System.currentTimeMillis()); }
    public boolean isPlaying(long withinMillis) {
        return System.currentTimeMillis() - lastStreamByteAt.get() < withinMillis;
    }
}
```

**Wire-up:**

- Construct in `Application.java` alongside `UserActivityTracker`.
- Pass to `WebServer` via a `registerStreamActivityTracker(...)` method that adds a Javalin
  `before` filter scoped to the two stream paths only:
  ```java
  app.before("/api/stream/{id}",   ctx -> tracker.bump());
  app.before("/api/av/stream/{id}", ctx -> tracker.bump());
  ```
  HTML5 video issues a Range request every few seconds while playing and stops when the
  player is paused, the tab is closed, or the user navigates away. A 30s window cleanly
  distinguishes "actively watching" from "stale tab."
- Inject into `AvScreenshotWorker`. The check sits at the top of `runOneStep` (see §3.2),
  *after* the operator-pause check and *before* claiming a row.

**Behavior:**

- The in-flight video always finishes — same semantics as operator pause. Worst case the user
  starts a video mid-screenshot and tolerates one more video's contention (≤120s with the
  per-video timeout) before the worker yields.
- `GET /worker/state` should expose the gating state so the profile UI can show "waiting —
  video playing" instead of an unexplained stall. Add a `streamActive` boolean to the
  response, and surface it as a sub-state of the running progress bar (e.g., italicized
  "paused — video playing" beneath the bar).

**Self-bump caveat.** The worker pulls video bytes from the same `/api/av/stream/{id}`
endpoint that browser playback uses, so the worker's own FFmpeg call bumps the tracker. The
check sits *between* videos (top of `runOneStep`), after the in-flight grabber has stopped,
but the worker's traffic is still fresh — so the worker will defer ~5s, recheck, and proceed
once the 30s window ages out. Net cost: roughly a 30s gap between videos. Acceptable for
v1; if it becomes annoying, the fix is to skip the bump for the worker's own requests (e.g.
FFmpeg's distinctive User-Agent, or a custom header set on the `FFmpegFrameGrabber`). Keep
the spec simpler and only add this if observed.

**Out of scope:** retrofitting `BackgroundThumbnailWorker` to use `StreamActivityTracker`
instead of (or in addition to) `UserActivityTracker`. The JAV worker has the same latent
issue — its quiet-period check fires on any API noise, not specifically playback — but
fixing it is a separate change.

---

## 7. Resolved decisions

1. **Polling, not SSE** — see §3.5 for rationale.
2. **Schema version 42** — bump `SchemaUpgrader.CURRENT_VERSION` from 41 to 42; add `applyV42()`.
3. **Worker lifecycle** — instantiate after the schema upgrade and after `AvScreenshotService`
   is constructed; call `worker.start()` once, just before the web server boots. Register a JVM
   shutdown hook (or wire into the existing shutdown path the shell uses) calling
   `worker.stop()`. Mirror `EnrichmentRunner` wiring at `Application.java:441-450`.
4. **`DONE` / `FAILED` retention** — keep forever for now. Add a TODO in
   `AvScreenshotQueueRepository` referencing a future `prune(Duration olderThan)` method and a
   `POST /api/av/screenshots/queue/prune` route. Out of scope for the initial ship.
5. **Utilities-screen "Generate screenshots" controls** — in scope. See §5A. The same shared
   UI module renders the controls in both the profile screen and the Utilities AV Stars detail
   panel.
6. **Concurrency with `AvScreenshotService`** — no per-video lock needed. The early-exit at
   `AvScreenshotService.java:42-47` makes a duplicate call return existing URLs without any
   FFmpeg work. The narrow race (two callers both observing zero existing screenshots and both
   starting FFmpeg) writes to the same `screenshots/{videoId}/{i}.jpg` paths and the same
   `(video_id, seq)` repository rows; the second writer overwrites the first's bytes and the
   second `screenshotRepo.insert` either updates or fails on a unique constraint. **Action:**
   confirm `screenshotRepo.insert` uses `INSERT OR REPLACE` (or equivalent upsert). If it
   doesn't, switch it to upsert. That single change is the entire mitigation.

---

## 8. Phasing

### Phase 1 — Backend
1. Schema migration: `applyV42()` adds `av_screenshot_queue` + indexes (idempotent
   `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`).
2. `AvScreenshotQueueRepository` interface + `JdbiAvScreenshotQueueRepository`.
   Methods: `enqueueIfAbsent(actressId, videoId)`, `claimNextPending()`, `markDone(id)`,
   `markFailed(id, error)`, `resetOrphanedInFlightJobs()`,
   `pauseActress(actressId)` (PENDING→PAUSED), `resumeActress(actressId)` (PAUSED→PENDING),
   `clearForActress(actressId)` (delete PENDING + PAUSED),
   `progressForActress(actressId)`, `globalDepth()`.
3. `StreamActivityTracker` (`com.organizer3.media`) + Javalin `before` filters on
   `/api/stream/{id}` and `/api/av/stream/{id}` — see §6.2.
4. `AvScreenshotWorker` background thread — start/stop/pause/resume; per-video 120s timeout
   with executor-replace-on-timeout (mirrors `AvScreenshotsCommand:97-128`); playback-gating
   check via `StreamActivityTracker.isPlaying(30_000)`.
5. `AvScreenshotQueueRoutes` — endpoints from §4 (enqueue, pause, resume, stop, progress,
   worker/state).
6. Wire tracker + worker + routes in `Application.java` (after schema upgrade, before web
   server boot).
7. Tests:
   - `JdbiAvScreenshotQueueRepositoryTest` — in-memory SQLite, full schema; idempotent enqueue,
     atomic claim, status transitions, orphan reset, per-actress clear.
   - `AvScreenshotWorkerTest` — mocked `AvScreenshotService` and `StreamActivityTracker`;
     verify FIFO ordering, pause halts between videos but not mid-video, playback-gating
     defers between videos but does not interrupt the in-flight video, timeout marks FAILED
     with timeout message, exception marks FAILED, idle sleep when queue empty.
   - `StreamActivityTrackerTest` — bump/isPlaying with controllable clock.
   - `AvScreenshotQueueRoutesTest` — HTTP-level tests against a Javalin instance, mocked repo
     and worker.

### Phase 2 — Shared UI module (`av-screenshot-controls.js`)
Build the screenshot-controls UI as a single shared module so the profile screen and the
Utilities detail panel render identical behavior with no duplication.

1. Module API: `mount(containerEl, actressId)`, `unmount()`. The module owns the state
   machine, polling timer, and event dispatch.
2. Render all five states from §5.1.
3. Enqueue / pause / resume / stop wiring against §4 endpoints. Optimistic transitions on
   click, reconciled by the next poll.
4. 2s `setInterval` poll loop against `/screenshots/progress`, started on mount when the
   actress has `pending + inProgress + paused > 0`, and on enqueue. Cleared on completion
   and in `unmount()`.
5. Surface the `streamActive` flag from `/worker/state` (cross-checked against the actress's
   `currentVideoId` from `/progress`) as a sub-label under the progress bar — "paused —
   video playing" when the worker is gated *and* this actress is the in-flight one.
   Otherwise the bar looks unexplained-stalled. Polling can fold this into the same 2s tick
   by hitting both endpoints in parallel, or expose `streamActive` directly in the progress
   response — implementor's call.
6. Dispatch `av-screenshots-generated` CustomEvent on completion; videos grid re-fetches.

### Phase 3 — Host the controls in two screens
1. **`av-actress-detail.js`** — `renderActionBar()` adds a controls container; mount the
   shared module after favorite/bookmark.
2. **`utilities-av-stars.js`** — `renderDetail()` (line 150) adds a controls container
   immediately below the favorite/bookmark toggle row (line 182); mount the shared module
   when the row selection changes; `unmount()` on selection change and on screen teardown.

### Phase 4 — Confirm screenshot upsert (one-line change, blocks Phase 1 ship)
Inspect `AvScreenshotRepository.insert(videoId, seq, path)`. If it is a plain `INSERT`, switch
to `INSERT ... ON CONFLICT(video_id, seq) DO UPDATE SET path = excluded.path`. This protects
against the race in §7 item 6. Add a regression test that calls `insert` twice for the same
`(videoId, seq)` and asserts no exception.

---

## 9. Non-goals

- No standalone queue management UI (list of all queued/done/failed videos). May come later.
- No priority ordering within the queue. FIFO is sufficient.
- No cross-actress parallelism. One video at a time; hardware is the bottleneck anyway.
- No global pause / resume / stop in v1. May be added later — the worker keeps an internal
  `paused` flag for forward compatibility, but no endpoint exposes it.
- No integration with the `utilities/task` framework. This is a persistent queue, not a
  one-shot task, and the task runner's single-job-at-a-time lock would conflict.
