# PROPOSAL: Resiliency hardening (post bulk-promote audit)

**Status:** DRAFT (needs a planning pass — Opus plans, Sonnet codes)
**Motivation:** A large bulk-promote run against the `unsorted` volume (host `pandora.local`)
on 2026-07-11 was audited to verify the async-promote + cover-confirmation resiliency work.
**The design held — no data loss, promotes non-blocking, failures left recoverable state** — but
the audit surfaced three concrete gaps that let a recoverable NAS condition degrade badly and,
critically, *silently*. This proposal captures them for a future hardening pass.

## What happened (audit evidence, 2026-07-11)

`pandora.local` entered **SMB session-table exhaustion**: it kept answering TCP (`nc` to :445
returned in 0.01s, ping 1.7ms/0% loss) and kept the *already-authenticated* mounted session alive,
but **hung new SMB SESSION_SETUP/authentication** requests, which then hit the 10s dial timeout.
The condition persisted across an application restart (it was NAS-side, not app-side; fixed only by
restarting pandora's SMB service).

Observed end state on `unsorted` (781 promoted):
- Promotes: all committed; async post-commit pool **idle** (not backed up); no wedged threads. ✅
- Covers: **86 folders** had missing/zero-byte covers; healed via `backfill_folder_covers` over the
  *mounted session* connection (0 failed). 1 title (`JIMMY-001`) unrecoverable — no local cover
  source (needs re-enrichment).
- Folder renames: **69** stranded at `/fresh/(CODE)`; could **not** be healed because the rename
  path needs *fresh* dials, which pandora was refusing.
- Logging: **died at ~10 MB / 00:28** and stayed dead for ~4 h — the audit had to be run from the DB
  + a `jstack` thread dump.

## Connection-model context (relevant to Item 1)

`SmbConnectionFactory` keeps a **per-volume pooled `PooledShare`** (`Map<String, PooledShare>`),
reused by `open()`/`withRetry()`. `withRetry` evicts on a broken-pipe/transport error and re-dials
fresh. The **mounted `SessionContext.activeConnection` is a *separate* `VolumeConnection`** — not the
factory pool. So there are two independent connections to a mounted volume. During the outage the
session connection survived (covers healed through it) while the factory pool connection was evicted
and every re-dial hung — the reconciler had a perfectly good connection available but couldn't use it.

---

## Item 1 — Reconciler self-healing is defeated when *new* dials fail but a live connection exists

**Problem.** `PromotionFolderRenameReconciler` / `TitleFolderRenamer` / `PromotionCoverReconciler`
all reach the NAS through `smbFactory.withRetry` → fresh dials. When pandora refused new sessions,
every reconcile attempt burned the full 10s dial timeout and accomplished nothing — for hours — even
though a working session connection to the same volume existed the entire time. The reconciler also
kept hammering the already-exhausted NAS every 10 min (69 candidates × ~10–20 s each), adding load to
a host that needed *fewer* new connections, not more.

**Proposed direction** (pick during planning):
1. **Reuse a live connection before dialing fresh.** If the factory pool has a live `PooledShare`
   for the volume, `withRetry` already reuses it — the failure was that eviction discarded a
   connection that could not be re-established. Consider: on a *dial* failure (as opposed to a
   broken-pipe on an established connection), do **not** leave the volume connectionless — fall back
   to any other live connection for that volume (e.g. a factory-visible reference to the mounted
   session), or defer eviction until a replacement is in hand.
2. **Circuit-breaker + backoff per volume.** After K consecutive dial timeouts for a volume, mark it
   `dial-degraded`, skip reconcile attempts for that volume for an exponential-backoff cooldown, and
   stop per-candidate hammering. Resume on the next successful dial. This turns "23 min of failing
   dials every pass" into "one probe dial per backoff window."
3. **Surface a health signal.** Expose (status endpoint and/or a periodic single-line log summary)
   per-volume `{pendingRenames, pendingCovers, dialsFailingSince}` so a degraded NAS is visible
   instead of inferred from a thread dump.
4. **Widen reconciler scope to cover no-draft-curated titles.** The scheduled rename reconciler
   runs `PromotionFolderRenameReconciler.reconcile()`, scoped to `grade_source='enrichment'`
   (draft-promoted titles only). A **manually-curated (no-draft) title** — `curated_at` set,
   `grade_source` NULL, real cast credit — whose save-time folder rename fails during an outage is
   therefore **never self-healed** (observed 2026-07-11: `JIMMY-001` stayed at `/fresh/(JIMMY-001)`
   after the whole enrichment backlog cleared, and had to be renamed by hand via `rename_title_folder`).
   The class already has an all-scopes `backfill()` path (`findAllCandidates`, any `grade_source`,
   `actress_id IS NOT NULL`) — it is just not run on the scheduler. Fix: have the scheduler also run
   a bounded `backfill()` pass (or drop the `grade_source='enrichment'` predicate from the scheduled
   scope), so no-draft-curated strandings self-heal too. Guard against the documented dup-fresh-copy
   case (`grade_source IS NULL` + no real cast) via the existing empty-cast skip.

**Acceptance.** With new dials failing but a live connection present, the reconciler makes progress
(or, if genuinely impossible, backs off and emits a health signal) rather than silently burning the
timeout every pass. A degraded NAS is observable without a thread dump.

**Tests.** Reconciler with a stub factory whose fresh dials throw but whose pooled/session connection
succeeds → renames complete. Circuit-breaker opens after K failures and short-circuits subsequent
attempts within the cooldown. Health summary reflects the pending counts.

---

## Item 2 — Cover-confirmation flag undercounts real cover failures

**Problem.** `cover_pending_since` is a *go-forward* flag set at promote and cleared on a confirmed
write; `PromotionCoverReconciler` heals only `cover_pending_since IS NOT NULL`. The audit found **68
flagged pending but 86 actually missing on the NAS** — ~18 real failures the flag never captured
(pre-v72 promotes, cleared-then-regressed, etc.). NAS-reality (`backfill_folder_covers`, which stats
the folder) is the true check; the flag alone structurally misses the gap. Separately,
`backfill_folder_covers` writes the NAS cover but does **not** clear `cover_pending_since`, so after a
manual heal the flags remain stale (observed: 68 flags persisted after all 86 covers were healed).

**Proposed direction:**
1. **Periodic NAS-reality cover sweep.** Keep the flag as the fast path, but add a slower-cadence
   scheduled sweep (e.g. hourly / after N promotes / on-demand) running the `backfill_folder_covers`
   logic (`curatedOnly`, non-clobbering) over promoted titles — catching failures the flag missed.
2. **Make heal paths clear the flag.** `backfill_folder_covers` (and any NAS-reality healer) should
   `UPDATE cover_pending_since = NULL` when it confirms/pushes a folder cover, so flag state and NAS
   reality stay consistent.
3. Optional: fold the cover check into the reconciler as a bounded NAS-stat over recently-promoted
   titles (weigh SMB cost — the periodic sweep in (1) is the pragmatic middle).

**Acceptance.** A promoted title whose cover is missing on the NAS but *not* flagged still gets
healed within one sweep interval. After any heal, `cover_pending_since` is NULL for that row.

**Tests.** Sweep heals a missing-cover row that has no pending flag. Backfill clears the flag on
push/confirm. Sweep is non-clobbering (valid covers untouched).

---

## Item 3 — Logging silently died at the rollover boundary

**Problem.** `logback.xml` uses a **synchronous** `RollingFileAppender` with
`FixedWindowRollingPolicy` + `SizeBasedTriggeringPolicy` (10 MB). At the 10 MB boundary the rollover
stuck and logging stopped for ~4 h with no error surfaced. Because the appender is synchronous, a
stuck/slow rollover also **back-pressures every thread that calls `log.*`** — plausibly compounding
the reconciler crawl (it WARNs on each dial failure). The outage was invisible until diagnosed from
the DB + `jstack`.

**Proposed direction:**
1. **Wrap the file appender in `AsyncAppender`** with `neverBlock=true` (bounded queue, drop on
   saturation) so a slow/stuck rollover can never back-pressure application threads.
2. **Switch rolling policy** from `FixedWindowRollingPolicy` (rename-storm per rollover) to
   `TimeBasedRollingPolicy` + `SizeAndTimeBasedFNATP` with `maxFileSize`, `maxHistory`, and
   `totalSizeCap` — the standard robust size+time policy.
3. **Log-liveness watchdog** (belt-and-suspenders): a lightweight check that warns (via a channel
   independent of the file appender — e.g. console/stderr or a health flag) if no log line has been
   emitted in N minutes.

**Acceptance.** Rollover at the size/time boundary never stalls logging or blocks app threads; a
stalled appender is self-evident (watchdog) rather than silent.

**Tests.** Config smoke test asserting the async wrapper + time/size policy are active. (Rollover
behavior is largely a config change; validate by driving the appender past the size threshold in a
harness if feasible.)

---

## Priority / sequencing

1. **Item 3 (logging)** — cheapest, highest leverage: never be blind again. Pure config, low risk.
2. **Item 1 (reconciler degradation + observability)** — prevents the "silent 4-hour crawl" and the
   NAS-hammering; the circuit-breaker + health signal are the high-value parts.
3. **Item 2 (cover NAS-reality sweep + flag-clear)** — closes the undercount; the flag-clear on
   backfill is a quick correctness fix, the periodic sweep is the larger piece.

## Out of scope

- Fixing pandora's SMB session exhaustion (NAS-side; operational).
- Unifying the mounted-session and factory-pool connections into a single per-volume connection —
  noted as context for Item 1 but a larger refactor; only pursue if Item 1's lighter options prove
  insufficient.
- Re-enriching `JIMMY-001` (one-off data task, not hardening).
