# PLAN: SMB connection driver — Phase B (unify the mounted path into the pool)

**Companion to** `spec/PLAN_SMB_CONNECTION_DRIVER.md` (Phase A, Waves 1–4, shipped & pushed) and
`spec/PROPOSAL_SMB_CONNECTION_DRIVER.md` (Stage 3 = this).
**Workflow:** Opus orchestrates + gates; Sonnet codes each wave; testing mandatory per wave; every wave
gated by diff review + build/test + advisor-vet the tricky bits + live SMB smoke — same discipline as
Phase A.

## What Phase B closes (the deferred residual)

After Phase A, the **factory pool** (`SmbConnectionFactory`) self-heals: bounded close, dial
backoff, network-change teardown, idle/age recycling. But there are **two independent `SMBClient`
lifecycles**. The **interactive mount** path — `SmbjConnector` → `SmbVolumeConnection` →
`SessionContext.activeConnection` — is a *separate* connection that does NOT go through the pool, so on
a VPN switch interactive/mount ops still hang-to-timeout (≤45 s) or fail until the user re-mounts. The
2026-07-11 audit saw exactly this divergence: covers healed through the mounted session while the pool
couldn't re-dial (or vice-versa). Phase B makes the driver the **single connection owner**: the mount
becomes a thin consumer that **borrows per-volume from the pool**, so mount ops inherit Phase A's
self-healing.

## ROI — read before deciding to *execute* (not just approve the design)

Phase B is a **bigger, riskier refactor than any single Phase A wave, with a narrower payoff.** The
background path where the bulk-promote pain actually lived (reconciler, cover-writes, streaming) is
**already** hardened. Phase B buys resilience for the **interactive** path — where a human is present
and can re-mount. The design below is minimal-churn (the ~69 consumers are untouched), but it retires
two classes and changes the semantics of the interactive filesystem. **Recommendation:** land Phase B
as its own reviewed effort, not bundled with anything else. Planning it now is correct regardless; the
execute/defer call is the user's.

## Blast radius (from the mount-path map)

- **~69 consumer call sites** reach the mount via `session.getActiveConnection().fileSystem()` (45 MCP
  tools, 3 id-only MCP tools, 17 shell commands incl. avstars, 4 utility tasks). **All stay unchanged**
  — they only use the 3-method `VolumeConnection` interface + the 14-method `VolumeFileSystem`, both of
  which we preserve. This is the whole reason the adapter approach is viable.
- **Escape sites (fs held across a full-tree walk):** the sync/organize/prep ops
  (`AbstractSyncOperation` & friends, `*BaseTask`, `AvStarsSyncOperation`, `CoherentMultiVolumeSyncTask`)
  capture the mount fs once and call many methods on it during a walk. Under Phase B each method call
  self-heals independently — a **resilience win** (a mid-sync severance re-dials per op instead of
  failing the whole sync), with no cross-call state to break (every `VolumeFileSystem` method is an
  independent SMB round-trip; the one stateful return, `openFile`'s `InputStream`, gets the lease
  treatment — see below).
- **Retired:** `SmbVolumeConnection` (its own SMBClient/Session/DiskShare) and `SmbjConnector`'s
  SMBClient-building. `SmbConnector` / `MountProgressListener` interfaces are kept for the mount UX.
- **Dry-run is SAFE:** resolved during planning — dry-run is a *consumer-level* concern (commands read
  `ctx.isDryRun()` and short-circuit or thread a `boolean dry` into their service). It is **not** wrapped
  inside the retired classes, so retiring them does not disable dry-run. "Consumers unchanged" holds.

## Design — delegating adapter, per-op `withRetry` (advisor-endorsed "Approach B")

Rejected alternative (**Approach A — hold one fixed borrow for the mounted duration**): the held share
goes stale on `invalidateAll()` (VPN switch) with **no auto-recovery**, which defeats the entire point
of Phase B. Do NOT use it.

Two new classes in `com.organizer3.smb` (package-private, constructed by the mount wiring):

### `PooledVolumeConnection implements VolumeConnection`
Fields: `SmbConnectionFactory factory`, `String volumeId`. Methods:
- `fileSystem()` → a `PooledVolumeFileSystem(factory, volumeId)`.
- `isConnected()` → `factory.isVolumeAvailable(volumeId)` (see the redefinition note below).
- `close()` → no-op / release. The factory owns the connection lifecycle; unmount does not tear down
  the pooled connection (it lives on, shared with background ops + subject to recycling).

### `PooledVolumeFileSystem implements VolumeFileSystem`
The 14 methods split into two cases:

- **The 13 materialized-return methods** (`listDirectory`, `walk`, `exists`, `isDirectory`,
  `getLastModifiedDate`, `size`, `move`, `rename`, `createDirectories`, `writeFile`, `delete`,
  `getTimestamps`, `setTimestamps`) → the **trivial** case: each is
  `factory.withRetry(volumeId, h -> h.fileSystem().<method>(args))`. They return a value/void that
  fully materializes inside the borrow, so `inUse` is held only for the call. `walk`/`listDirectory`
  run as one borrow (correct — the whole traversal is one self-healing unit; a mid-walk severance
  retries the walk once).

- **`openFile` is the crux — the ONE method that returns something outliving the borrow** (an
  `InputStream` the caller reads over time). This is the *exact* resource-outlives-lease bug that bit
  `/api/stream` and `/api/av/stream` in Wave 4. It gets the **lease-backed** treatment, reusing the
  Wave 4 idiom verbatim:
  ```
  SmbShareHandle h = factory.open(volumeId);            // inUse++
  try {
      InputStream in = h.fileSystem().openFile(path);
      return new FilterInputStream(in) {                // close BOTH, exactly once
          @Override public void close() throws IOException { try { super.close(); } finally { h.close(); } }
      };                                                 // h.close() → inUse-- (idempotent, fail-safe)
  } catch (…) { h.close(); throw; }                      // don't leak the lease on setup failure
  ```
  `h.close()` is the Wave 4 idempotent lease release (decrement exactly once; forget-to-close fails
  SAFE = just not recycled). Correct for any file size. **Frame it this way in the code + review: 13
  trivial wraps, 1 lease-backed — so the implementer can't get it backwards.**

## Wiring changes (mount / unmount)

- **`MountVolumeTool` / `MountCommand`**: replace `smbjConnector.connect(...)` (which built its own
  SMBClient) with: (a) a **factory probe-dial** to validate reachability + auth and drive the
  `MountProgressListener` phases, then (b) `session.setMount(volume, new PooledVolumeConnection(factory,
  volumeId), index)` (atomic swap, unchanged). Idempotent same-id short-circuit and switch-drops-prior
  logic: switching volumes no longer needs to *close* a separate connection (the pool owns per-volume
  connections) — just swap the `SessionContext` pointer.
- **`UnmountVolumeTool` / `UnmountCommand`**: use the atomic **`clearMount()`** (which today has *zero*
  callers — fixes the existing non-atomic 3-setter teardown a concurrent MCP reader can observe torn).
  Do **not** `factory.evict(volumeId)` on unmount — the pooled connection is shared with background ops
  and recyclable; unmount just clears the interactive pointer.
- **`SessionContext.shutdown()`**: `activeConnection.close()` becomes the adapter's no-op; that's fine
  (factory `shutdown()` owns real teardown). Confirm nothing double-closes.

## Verify-items (advisor-flagged — landmines if skipped)

1. **`isConnected()` redefinition.** Goes from `share.isConnected()` (real socket) to "host-reachable"
   via `factory.isVolumeAvailable` — which is the **TCP:445 probe the audit showed returns `true` while
   SMB auth hangs**. The ~46 consumer guards flip from "skip when socket dead" to "proceed →
   `withRetry`/breaker handle it" — acceptable (op fails cleanly vs silently skipping). **Confirm no
   consumer treats `isConnected()==false` as "no mount at all"** — that check must be
   `getMountedVolumeId() != null`. Grep + fix any that conflate them.
2. **Mount mutations become one-retry.** Folder `move`/`rename`/`writeFile`/`delete` across the MCP
   tools go from single-attempt to `withRetry`'s broken-pipe retry — the same false-negative-on-
   post-op-broken-pipe edge accepted for Wave 4 trash (no double-mutation: the retry hits "source not
   found"; just an occasional false-negative). Document it in the tool docs / plan.
3. **`MountProgressListener` phases.** Routing through `factory.open()` loses the granular
   Connecting/Authenticating/Opening-share callbacks unless a listener is threaded into
   `factory.dial()`. Decide: coarse phases (emit around a probe-dial) vs. teach `dial()` an optional
   listener. Minor UX — coarse is acceptable for B2; the dial-listener is a nice-to-have.
4. **Concurrency.** Mount consumers + background ops now share **one pooled Session per volume**, and a
   long sync/`walk` holds `inUse` for minutes alongside background cover-writes on that same session.
   The pool is already used concurrently; the *new* thing is long-held mount ops on a shared session.
   Confirm smbj Session multiplexing tolerates concurrent operations (it does — separate message IDs);
   note it and watch for it in the B3 smoke.

## Waves

### Wave B1 — the delegating adapter (pure, no wiring)
Build `PooledVolumeConnection` + `PooledVolumeFileSystem`. Not installed anywhere yet.
**Tests:** each of the 14 methods delegates correctly; each self-heals — a stub factory whose first
`withRetry` attempt throws a broken-pipe and second succeeds → the method returns the second result.
`openFile` returns a stream that closes both the inner stream and the lease exactly once (idempotent
double-close); a setup failure does not leak the lease (`inUse` back to 0). `isConnected()` reflects
`factory.isVolumeAvailable`.
**Gate:** build+test green · Opus diff review · advisor-vet the `openFile` lease (the crux).

### Wave B2 — mount via the factory; retire the second SMBClient
Rewire `MountVolumeTool`/`MountCommand` (install `PooledVolumeConnection`, probe-dial for phases) and
`UnmountVolumeTool`/`UnmountCommand` (atomic `clearMount()`). Retire `SmbVolumeConnection` and
`SmbjConnector`'s SMBClient build (keep `SmbConnector`/`MountProgressListener` as the UX seam; a thin
probe-based connector or fold into the factory). Resolve verify-item #1 (grep `isConnected()` misuse).
No consumer changes.
**Tests:** `MountVolumeToolsTest`/`MountCommandTest`/`UnmountCommandTest` updated — mount installs a
`PooledVolumeConnection`; switch swaps the pointer without a separate close; unmount uses `clearMount()`;
mount failure preserves the prior mount. `SmbjConnectorTest` retired/repurposed. A representative
consumer (e.g. `ListDirectoryTool` or a sync op) works unchanged over the adapter.
**Gate:** build+test green · Opus diff review · advisor-vet the wiring (idempotency, switch, unmount
atomicity) · targeted consumer regression.

### Wave B3 — live smoke (the completion gate) + dead-code removal
Remove now-dead code (`SmbVolumeConnection`, retired connector bits; `clearMount()` now used).
**THE acceptance gate — a mid-operation teardown (mirrors Wave 4's forced-recycle proof):** mount a
volume, start a heavy mount op (ideally a **sync**, the deepest fs consumer), and trigger
`invalidateAll()` **mid-operation** (via `POST /api/smb/reset`, or a real VPN switch). Confirm the
in-flight walk + the next op **re-dial transparently and complete**, instead of failing until re-mount.
Also: normal mount → list/move/rename/read all work; unmount clears cleanly; no double-close; no leaked
`inUse` (idle mount connection is eventually recycled after unmount). **This single test is the only
thing that demonstrates the residual is actually closed** — without it, "unified" is unverified.
**Gate:** build+test green · Opus diff review · the mid-op teardown smoke above.

## Acceptance (Phase B)
- Interactive mount and background ops share **one connection per volume**; degrading/killing one does
  not strand the other (the audited divergence is gone).
- A network-reconfiguration event (or `invalidateAll`) during a mount op is transparently recovered —
  the op re-dials and completes; no hang-until-remount.
- All ~69 mount consumers behave identically to today on the happy path (verified: adapter preserves
  the `VolumeConnection`/`VolumeFileSystem` contract; dry-run unaffected).
- `openFile` streams survive recycling for their whole read (lease-backed), matching the Wave 4 fix.

## Out of scope
- Multi-active mounts / multi-connection-per-volume (single active mount UX retained; the pool remains
  one connection per volume).
- Changing the ~69 consumers' call shape (the adapter exists precisely to avoid that).
- The remaining resiliency-hardening items (cover-flag undercount, logging death) — independent.
- Wave 5 (validate-on-borrow) — still optional, still only if a silent half-open surfaces.
