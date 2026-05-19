# Proposal: SQLite busy_timeout + WAL mode + auto-pause background writers

**Status:** Draft 2026-05-07 — for discussion, no implementation yet.
**Origin:** First long-running coherent sync (2026-05-07 22:30, 50-min run) hit `SQLiteException: [SQLITE_BUSY] database is locked` mid-run. vol.qnap completed its scan successfully but failed during the post-scan save loop on `INSERT INTO videos`. The next 5 volumes (`qnap_archive`, `classic`, `pool`, `classic_pool`, `collections`) failed in quick succession on `DELETE FROM videos` — a cascading lock-contention failure. End result: 11/17 volumes synced successfully, 6 marked failed, partial-failure handling correctly skipped the global prune.

This is not a coherent-sync bug. The same failure can happen during any sync, any background sweeper run, any concurrent admin operation. It just shows up dramatically during long runs because every minute of activity is another minute of exposure to lock contention.

---

## 1. Problem statement

The app's SQLite connection is created with default JDBC driver settings:

```java
Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + dbDir.resolve("organizer.db"));
```

Default behavior of the SQLite JDBC driver:
- **`busy_timeout = 0`** — any contention with another writer = immediate `SQLITE_BUSY` exception.
- **`journal_mode = delete`** — readers and writers conflict (one reader can block a writer).
- No shared cache, no specific WAL settings.

This app has multiple concurrent write paths:
- Sync pipeline (mark-stale, scan, save videos, finalize) — bursty writes during a sync.
- `TitleTranslationSweeper` — periodic background pass over titles.
- `JavDB EnrichmentRunner` — rate-limited but writes enrichment rows continuously.
- Web requests (favorites, bookmarks, custom avatars, manual edits).
- Reconcile sweep (when invoked).

With `busy_timeout = 0`, any time two of these collide, one fails immediately. Most user-facing operations either retry implicitly via UI clicks or are short enough that the collision window is small. The sync pipeline's exposure window is much longer (50 minutes for a coherent run) and the failure surfaces dramatically — partial sync, failed volumes, ghost rows on those volumes still live.

---

## 2. Root cause analysis (from the 22:30 run)

Timeline:
- 22:30:47 — vol.qnap phase started.
- 22:32:17 — vol.qnap scan progressed to 100% (122/122 folders discovered).
- 22:40:25 — vol.qnap save loop failed: `INSERT INTO videos` → `SQLITE_BUSY`. Phase ended `failed` after 9m 38s total.
- 22:40:28–22:40:45 — vol.qnap_archive, classic, pool, classic_pool, collections each failed within 3–6 seconds on `DELETE FROM videos` calls. This is the per-volume sync's mark-stale prelude — a cheap operation that nonetheless contended with the same lock holder.

Conclusion: a writer outside the sync (almost certainly the translation sweeper or javdb enrichment) held a write lock for ~8+ minutes during vol.qnap's save loop. Without `busy_timeout`, the sync's writes had no retry budget and failed immediately.

---

## 3. Proposed changes

### 3.1 SQLite connection configuration

Replace the bare `Jdbi.create(jdbcUrl)` with a configured `SQLiteDataSource`:

```java
SQLiteConfig sqliteConfig = new SQLiteConfig();
sqliteConfig.setBusyTimeout(60_000);                       // ms; was 0
sqliteConfig.setJournalMode(JournalMode.WAL);              // readers don't block writers
sqliteConfig.setSynchronous(SynchronousMode.NORMAL);       // safe with WAL; faster than FULL
sqliteConfig.setSharedCache(true);                         // small win for short-lived connections

SQLiteDataSource ds = new SQLiteDataSource(sqliteConfig);
ds.setUrl("jdbc:sqlite:" + dbDir.resolve("organizer.db"));

Jdbi jdbi = Jdbi.create(ds);
```

Effects:
- **`busy_timeout=60000`** — when a write hits a locked DB, SQLite waits up to 60 sec before giving up. Internal retry loop with exponential backoff. Most contentions resolve in seconds; this gives plenty of headroom.
- **`WAL` journal mode** — separate write-ahead log file. Readers see a snapshot; writers append. Readers and writers no longer block each other. The single-writer constraint remains but with much shorter contention windows.
- **`synchronous=NORMAL`** — safe pairing with WAL (the SQLite docs recommend it). Slightly less paranoid than `FULL` but no data-loss risk for committed transactions in normal use.
- **`shared_cache=true`** — connection cache hit improvements; minor win.

### 3.2 Connection-level customizer (alternative if datasource path is awkward)

If JDBI/datasource wiring is messier than expected, use a JDBI plugin/customizer that sets PRAGMAs on every connection acquisition:

```java
jdbi.installPlugin(new JdbiPlugin() {
    @Override
    public Handle customizeHandle(Handle handle) {
        handle.execute("PRAGMA busy_timeout = 60000");
        handle.execute("PRAGMA journal_mode = WAL");
        handle.execute("PRAGMA synchronous = NORMAL");
        return handle;
    }
});
```

`PRAGMA journal_mode = WAL` is persistent (DB-level), so subsequent runs preserve it. `busy_timeout` is per-connection and must be set every time. Equivalent functional outcome to 3.1; slightly less efficient.

Prefer 3.1 if the wiring is clean. Fall back to 3.2 if not.

### 3.3 Verify migration safety

Switching to WAL on an existing DB:
- WAL mode is set with `PRAGMA journal_mode = WAL` and persists. The first connection that issues this PRAGMA triggers the mode change.
- Existing rows and indexes are unaffected.
- New `.db-wal` and `.db-shm` files appear next to `organizer.db`. Backup tooling must include them.

Check `UserDataBackup` / `UserDataBackupService` to confirm they capture the entire DB directory, not just the `.db` file. If they hardcode `organizer.db` only, extend to also copy `.db-wal` and `.db-shm` when present (with a `PRAGMA wal_checkpoint(FULL)` first to flush WAL into the main DB before snapshot).

### 3.4 Auto-pause background writers during sync

`busy_timeout` makes contention non-fatal — but contention still slows sync down. With bg writers competing for locks throughout a 50-min coherent run, every sync write may wait several seconds for the lock holder to release. Multiplied across thousands of writes, sync runtime balloons even though no writes fail.

The fix: pause background writers (translation sweeper, javdb enrichment runner) while a sync task is active. They auto-resume when the sync ends. Web-request writes (favorites, manual edits) are NOT paused — they're short and infrequent, and the user expects them to be responsive.

#### Existing infrastructure

`EnrichmentRunner` (`src/main/java/com/organizer3/javdb/enrichment/EnrichmentRunner.java`) already has the mechanism via `PAUSE_ISSUING_TASKS`:

```java
private static final Set<String> PAUSE_ISSUING_TASKS = Set.of(
    "enrichment.bulk_enrich_to_draft"
);
```

The runner's loop checks `taskRunner.currentlyRunning()` against this set every 1 sec; if a matching task is active, `isPaused()` returns true and the runner sleeps without dequeuing. Self-healing on resume.

#### Changes needed

1. **`EnrichmentRunner.PAUSE_ISSUING_TASKS`** — add the sync task IDs:
   ```java
   private static final Set<String> PAUSE_ISSUING_TASKS = Set.of(
       "enrichment.bulk_enrich_to_draft",
       "volume.sync",                       // single-volume sync
       "volume.sync_coherent",              // coherent multi-volume sync
       "volume.clean_stale_locations"       // legacy stale-row cleaner; also writes heavily
   );
   ```

2. **`TitleTranslationSweeper`** — currently has no pause awareness; runs unconditionally on its scheduled tick. Add a TaskRunner check at the top of `run()`:
   ```java
   public void run() {
       if (!enabled) return;
       if (taskRunner != null && isSyncActive(taskRunner)) {
           log.debug("TitleTranslationSweeper: sync task active, skipping tick");
           return;
       }
       sweepOnce(batchSize);
   }
   ```
   Inject `TaskRunner` via setter (mirroring `EnrichmentRunner.setTaskRunner`) — null-safe, no-op when not yet wired during boot.

3. **Optional — central `BackgroundWriterCoordinator`.** If more bg writers appear later, factor the "is sync active?" predicate into a tiny helper rather than duplicating the check. For v1, two-call-site duplication is fine.

#### What stays NOT paused

- **Web-request writes** — favorites toggle, manual title edit, custom avatar upload, etc. Short transactions; user-initiated; expected to be responsive. Rely on `busy_timeout` to absorb the rare collision.
- **Manual enrichment kicked off via web** (single-title "Enrich now" button) — user-initiated; runs once and ends. Same rationale.
- **Reconcile auto-run at end of coherent sync** — runs after the volumes are scanned, after the global prune; the coherent task is still active so `currentlyRunning()` still returns sync, but the reconcile is a read-mostly pass. No conflict.

#### Why it's complementary to busy_timeout

- **busy_timeout** prevents catastrophic failure when contention does happen (mid-sync user click, race between two short writes, etc.).
- **auto-pause** *minimizes* contention during long-running operations so sync runs at full speed.

Both are needed:
- busy_timeout alone → no failures, but sync runs slowly waiting on bg writer locks.
- auto-pause alone → fast sync, but unrelated short writes (web requests) still occasionally fail.
- Both → fast sync, no failures.

### 3.5 Test plan

1. **Connection config test.** New connection has `busy_timeout > 0`. Smoke check via `PRAGMA busy_timeout` query.
2. **Concurrent-writer regression test.** Two threads, both trying to UPDATE the same row in a tight loop for 5 sec. Without busy_timeout, one fails ~instantly. With it, both succeed (one gets serialized).
3. **WAL mode active test.** After connection setup, `PRAGMA journal_mode` returns `wal`.
4. **Backup includes WAL files.** Trigger a backup, assert the snapshot includes `.db-wal`/`.db-shm` if present, OR contains a fully-checkpointed DB.
5. **Sync pipeline regression.** Run a single-volume sync test with a concurrent writer thread holding a brief lock on `videos` — sync should succeed (contention absorbed by busy_timeout).
6. **Auto-pause: EnrichmentRunner.** Start a fake `volume.sync` run via TaskRunner; assert `enrichmentRunner.isPaused() == true`. End the task; assert `isPaused()` returns to false within one tick.
7. **Auto-pause: TitleTranslationSweeper.** Same setup; `sweeper.run()` skips with a debug log; resumes after task ends.
8. **Web-request writes still flow during sync.** With a fake sync task active, a manual UPDATE through a web route succeeds (busy_timeout absorbs any brief contention). Confirms we didn't accidentally global-pause everything.

---

## 4. Phasing

Single PR, ~2–3 hours.

1. Add `SQLiteConfig`/`SQLiteDataSource` setup in `Application.java` (§3.1).
2. Verify backup includes WAL files (or checkpoint before snapshot) (§3.3).
3. Extend `EnrichmentRunner.PAUSE_ISSUING_TASKS` with sync task IDs (§3.4).
4. Add `setTaskRunner` + sync-active check to `TitleTranslationSweeper` (§3.4).
5. Add tests (8 cases in §3.5).
6. Update `spec/IMPLEMENTATION_NOTES.md` with the new SQLite config + auto-pause behavior.

§3.1–3.3 (timeouts + WAL) and §3.4 (auto-pause) can be split into two PRs if preferred — they're independent and either ships value alone. §3.4 alone reduces contention but doesn't fix the failure-on-collision case; §3.1–3.3 alone fixes failure but leaves sync slower than necessary. Together they're complete.

---

## 5. Open questions

1. **busy_timeout default — 60 sec vs. higher?** 60 sec is generous; production systems often go 5–10 sec. For an interactive web app, 60 sec means a stuck writer freezes UI requests for up to a minute. But: most of our writes are short; legitimate contention should resolve in <1 sec; 60 sec is a backstop, not a typical wait. Lean 60 sec for safety.
2. **Should the catastrophic-delete guard's threshold need adjusting?** With WAL, more concurrent activity is possible — e.g., reconcile sweep running while a sync is in progress. The guard's `max(50, 10%)` threshold is computed per-call; if state changes between calls, the threshold shifts. Probably fine but worth a thought.
3. **Should we also tune `cache_size` and `mmap_size`?** Larger cache improves read perf at the cost of memory. Out of scope here; revisit if perf becomes a concern.
4. **Single-instance lock.** WAL mode allows multiple processes to read/write concurrently. The app is single-instance by design; nothing changes here. But if the user ever runs two organizer3 processes simultaneously (e.g., dev + prod against the same DB), WAL won't prevent corruption — the DB is single-writer at the SQL level even with WAL. Not a new concern; just noting.
5. **Auto-pause coverage — which tasks belong in `PAUSE_ISSUING_TASKS`?** Current proposed set: `volume.sync`, `volume.sync_coherent`, `volume.clean_stale_locations`. Should organize-pipeline tasks (`utility.organize_*`) also be included? They write but typically run for shorter durations. Easy to add later if needed; start conservative.
6. **Manual enrichment via web — pause or not?** Single-title "Enrich now" buttons run a brief, user-initiated enrichment. Currently NOT paused. If the user clicks during a sync, it competes with sync writes. Probably fine (busy_timeout absorbs), but worth measuring; if it becomes a real issue, gate the manual-enrich web route on the same predicate.
7. **Reverse direction: should sync pause if enrichment is mid-flight?** The pause is one-directional today (enrichment yields to sync). Should a sync starting while enrichment is processing wait for enrichment to finish its current item? Probably no — sync is admin-triggered and should start promptly; the existing item finishes within seconds and busy_timeout handles any race.

---

## 6. Why this matters

- **Phase 1–3 of the sync reconciliation feature is correct but fragile** without this fix. Coherent sync's whole value (multi-volume reconciliation in one pass) is undermined when 30%+ of volumes fail on lock contention.
- **The bug exists app-wide**, not just in sync. Every background sweeper run, every reconcile run, every concurrent web user action is exposed. We've been masking it with retries and short transactions, but the failure mode lurks everywhere.
- **The fix is small and well-understood** — SQLite's busy_timeout + WAL is a textbook Java+SQLite recipe. Low risk, high leverage.

---

## 7. Workaround in the meantime

For the user's current state with 6 failed volumes from the 22:30 run:
- **Run per-volume sync on each of the 6 failed volumes individually.** Each is a 1–5 minute run; far less exposure to contention. Same code path as coherent's per-volume scan, but isolated. Idempotent.
- The combined run will close the loop on the ghost rows for those volumes. After all 6 succeed, run reconcile to see the cleaned-up duplicate-live count.
