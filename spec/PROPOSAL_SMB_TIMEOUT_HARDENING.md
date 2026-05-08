# Proposal: SMB Timeout / Hang-on-Dead-Connection Hardening

**Status:** Draft 2026-05-07 — for discussion, no implementation yet.
**Origin:** First real-world coherent sync run (2026-05-07 21:21 UTC) hung indefinitely on volume `a` after a `com.hierynomus.protocol.transport.TransportException: EOF while reading packet` from smbj's `PacketReader` background thread. The save loop completed (99/99 titles), the SMB connection died on the server side, and the task thread blocked forever waiting on a follow-up SMB call (likely the unmount). No phase failure was emitted; the task simply stopped advancing. User had to kill the app to recover.

This breaks the core value proposition of coherent sync — "set it and forget it overnight" — because a single dropped TCP connection currently turns the run into a hung process instead of a partial-failure outcome that the existing failure handling could recover from.

---

## 1. Problem statement

Three concrete failure modes observed or anticipated:

1. **Read hang on dead connection.** The `SMBClient` in `SmbConnectionFactory` is constructed with default `SmbConfig`, which has no socket read timeout (`readTimeout = 0` → unlimited). When the NAS closes the TCP connection mid-operation (server timeout, network blip, NAS reboot), the next SMB read on the task thread blocks forever instead of throwing.
2. **Background reader thread dies silently.** smbj's `PacketReader` runs on its own thread; when the underlying socket closes, the reader logs and exits. The task thread doesn't see this — it's blocked on a separate write/read cycle that's now orphaned.
3. **No per-volume watchdog.** Coherent sync's failure-handling logic catches `IOException | RuntimeException` from `FullSyncOperation.execute(...)`, marks the volume failed, and continues. But it can only do that if `execute(...)` actually returns or throws. A hang means no return, no throw, no advance.

Result: a 17-volume coherent sync that should run for 1–4 hours can hang indefinitely on volume 1 with no detection or recovery.

---

## 2. Design principles

1. **Fail loud, not silent.** A dropped SMB connection should produce a `phaseEnd(failed)` within seconds, not block forever. Coherent sync's existing partial-failure handling is correct — it just needs to be *triggered*.
2. **Bounded waits everywhere.** Every SMB I/O call must have a timeout. Same for the unmount step at the end of each volume's phase.
3. **Layered defense.** smbj-level read timeout catches most cases. A per-volume watchdog catches the rest (deadlocks, JVM-internal stalls). One layer alone is not enough.
4. **Conservative timeouts.** Better to fail-and-retry a slow-but-healthy scan than to wait forever on a dead one. SMB scans of large letter volumes can take many minutes; per-call timeouts should be in the **minutes** range, not seconds.

---

## 3. Proposed changes

### 3.1 SMB client config — read/write timeout

`SmbConnectionFactory` currently builds an `SMBClient` with no config:

```java
public SmbConnectionFactory(OrganizerConfig config) {
    this(config, new SMBClient(), null);
}
```

Replace with explicit `SmbConfig`:

```java
SmbConfig smbConfig = SmbConfig.builder()
    .withReadTimeout(5, TimeUnit.MINUTES)        // socket read; was 0 (unlimited)
    .withWriteTimeout(5, TimeUnit.MINUTES)       // socket write
    .withTransactTimeout(5, TimeUnit.MINUTES)    // overall request
    .withSoTimeout(5 * 60 * 1000)                // TCP-level; backstop
    .build();
new SMBClient(smbConfig);
```

Rationale for 5 minutes: a single SMB call (list directory, read file metadata) should never legitimately take more than a few minutes even on a sluggish NAS. Anything longer is a dead connection. 5 minutes is conservative enough to not false-positive on real slow operations.

These should be configurable in `organizer-config.yaml` under a `smb:` section so the user can tune for their network without a code change:

```yaml
smb:
  readTimeoutMinutes: 5
  writeTimeoutMinutes: 5
  transactTimeoutMinutes: 5
```

### 3.2 Per-volume watchdog in CoherentMultiVolumeSyncTask

Even with smbj timeouts, a sufficiently-broken state could still hang the task thread (deadlock, native stall, JVM bug). Add a watchdog timer around each volume's `execute(...)` call.

Approach: wrap the per-volume scan in a `Future` submitted to a single-thread executor; await with a hard timeout. If the timeout fires, cancel the future, mark the volume failed, and continue.

Rough sketch:

```java
ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
try {
    Future<Void> scanFuture = scanExecutor.submit(() -> {
        suppressedPruneOp.execute(volume, structure, fs, ctx, phaseIO);
        return null;
    });
    long volumeTimeoutMin = AppConfig.get().smb().perVolumeTimeoutMinutes();
    scanFuture.get(volumeTimeoutMin, TimeUnit.MINUTES);
    volumeOk = true;
} catch (TimeoutException te) {
    log.error("Coherent sync hard timeout on volume={} after {} min", volume.id(), volumeTimeoutMin);
    io.phaseLog(phaseId, "Hard timeout exceeded — marking volume failed");
    partialFailure = true;
    failedVolumes.add(volume.id());
    scanFuture.cancel(true);  // best-effort interrupt
} catch (ExecutionException ee) {
    // Existing IOException/RuntimeException handling
    Throwable cause = ee.getCause();
    log.error("Coherent sync scan failed for volume={}: {}", volume.id(), cause.getMessage(), cause);
    partialFailure = true;
    failedVolumes.add(volume.id());
}
```

Per-volume timeout default: **2 hours**. A genuinely large volume scan over slow SMB might legitimately take 30–60 min; doubling gives headroom. Configurable via `smb.perVolumeTimeoutMinutes`.

The single-thread executor is recreated per coherent run to avoid leaking state across runs. Shut it down in a `finally` block.

### 3.3 Unmount with timeout

Per-volume unmount currently runs unbounded in the task's `finally` block. If the connection is already dead, unmount can hang the same way. Wrap it with a short timeout (~30 sec) using the same executor pattern. Failure to unmount is non-blocking — log and move on.

### 3.4 Idle/sweep heartbeat in logs

Today the only way to tell a hung run from a slow one is to watch the log for activity. Add a periodic INFO heartbeat from the task — every 60 seconds, log "Coherent sync alive — current phase=vol.X (Nm elapsed)". This makes hangs obvious without grepping.

---

## 4. Test strategy

1. **Unit test for SmbConfig wiring.** Assert that the constructed `SMBClient` has the configured timeouts (read into `getConfig()`).
2. **Watchdog timeout test for `CoherentMultiVolumeSyncTask`.** Inject a mock `FullSyncOperation` whose `execute(...)` blocks indefinitely; configure a short timeout (1 sec) for the test; assert the task continues to the next volume and global prune is skipped (partial failure).
3. **Watchdog interrupt cleanup.** After timeout fires and the future is cancelled, assert no orphaned threads remain (count threads in the test executor, fail if non-zero after `.shutdown()`).
4. **Heartbeat test (optional).** Run a deliberately-slow mock execute; assert at least one heartbeat appears in the log within the expected window.

---

## 5. Phasing

Single PR; the three changes are tightly coupled. Total estimate ~3–4 hours.

1. Add `SmbSettings` config record + plumb through `OrganizerConfig.smbOrDefaults()`.
2. Build `SmbConfig` in `SmbConnectionFactory` from settings; existing constructors remain backwards-compatible.
3. Add watchdog wrapper + heartbeat in `CoherentMultiVolumeSyncTask`.
4. Tests (3 cases above).
5. Update `spec/IMPLEMENTATION_NOTES.md` if it mentions SMB defaults.

---

## 6. Open questions

1. **Default timeout values.** 5 min per SMB call / 2 hr per volume / 30 sec for unmount feel safe, but I haven't measured real-world tail latencies. Worth a brief observation pass first — log p99 SMB call duration over a normal sync to set defaults that won't false-positive.
2. **Interrupt on cancel.** Should explicit user cancel (`io.isCancellationRequested()`) also cancel the in-flight future? Probably yes — current cancel checks happen between volumes; adding mid-volume cancel via `future.cancel(true)` is cheap.
3. **Should the watchdog also apply to single-volume `SyncVolumeTask`?** The bug exists there too — a hung mount/scan would hang that task forever. Same fix should apply. Treating both as part of this proposal feels right.
4. **Connection pool eviction.** When a volume fails with a transport exception, the cached `Connection`/`Session`/`DiskShare` in `SmbConnectionFactory.pool` is now broken. Subsequent calls reuse it and fail again. The pool should evict failed connections so the next attempt creates a fresh session. Possibly already handled — verify in `SmbConnectionFactory`.

---

## 7. Why this is needed now

The first real-world coherent sync hung after vol-a on a single dropped TCP connection. Without these changes, every coherent run is fragile to any SMB transient — and SMB transients are normal on commodity NAS hardware. The feature is unusable for its intended overnight workflow until this is fixed.

---

## 8. Workaround in the meantime

Per-volume `[Sync]` works fine because the failure mode is "one volume hangs, you kill the app, you've lost only that one volume's progress." Coherent sync amplifies the failure: lose one volume → lose the entire multi-hour run → no progress on volumes 2–17.

Until this is fixed: prefer per-volume sync executed in a loop (or via the shell `sync all` per-volume sequence), or accept that coherent sync is a "try it and see" operation that may need to be killed and restarted.
