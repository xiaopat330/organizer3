# PLAN: SMB connection driver — implementation (Phase A)

**Companion to** `spec/PROPOSAL_SMB_CONNECTION_DRIVER.md` (Stage 0 CONFIRMED: VPN-switch-driven,
transition-time TCP severance).
**Workflow:** Opus orchestrates + gates; Sonnet codes each wave. Testing mandatory per wave.
**Scope of this plan:** **Phase A — harden the connection *pool*** (`SmbConnectionFactory`), which is the
path the reconciler/cover-write/background work uses. **Phase B (unify the mounted `SmbjConnector` path
into the pool — the proposal's Stage 3) is deferred to its own planning pass.**

## Emphasis correction (read before implementing)

The plan is written against the **confirmed** failure signature, not the original hypothesis. Audit
evidence: onset was `broken pipe → evict → re-dial TIMES OUT`, and the reconciler thread was
`TIMED_WAITING on a dial future`. So **stale *detection* already works** (the OS surfaced EPIPE and
`withRetry` evicted). The damage was downstream:
1. **Re-dial thrashing** during the transition (10 s × N items, every reconcile pass), and
2. **NAS session orphaning → session-table exhaustion** (sever leaves sessions the NAS reclaims only
   on its own idle timeout).

Therefore the priority is **stop thrashing + tear down/re-establish cleanly**, NOT "detect stale
faster." `validate-on-borrow` (which targets *silent half-open*, a rarer mode that now costs 45 s not
5 min, and mostly bites the *deferred* mount path) is demoted to an optional last wave.

**Correction to carry into the doc + proposal:** a clean SMB `LOGOFF` only works on a *live*
connection. In the sever-first case the socket is already dead when we react, so LOGOFF fails and the
NAS reclaims those sessions on its own idle timeout regardless. **Teardown's real value is "stop
thrashing + re-establish cleanly," not "reclaim sessions."** Exhaustion is mitigated by **max-idle
recycling** (fewer idle sessions to orphan at switch time) + **backoff** (fewer new sessions created
during the storm) — not by teardown. Do not sell teardown as the exhaustion fix.
(`PROPOSAL_SMB_CONNECTION_DRIVER.md` Stage 2 overstates the LOGOFF benefit — fix when convenient.)

## Deferred residual (user must sign off)

After Phase A, the **pool path self-heals** (backoff + teardown + recycling). The **interactive
mounted path (`SmbjConnector` → `SessionContext`) does NOT** — on a VPN switch, interactive ops still
hang-to-timeout (≤45 s) or fail until the user re-mounts. Closing that gap is Phase B (unify).

## Foundational seams (land in Wave 1, used by all waves)

- **Injectable time source.** Add a `LongSupplier nowMillis` (default `System::currentTimeMillis`) to
  `SmbConnectionFactory`, mirroring the existing `setDialTimeoutMillisForTesting` pattern. Every TTL /
  cooldown / max-age / max-idle test advances this instead of sleeping (avoids flaky sleep tests).
- **Bounded-close executor.** A small daemon `ExecutorService` (or reuse a dedicated one) so
  connection close never runs on a caller/sweeper thread unbounded.
- **`SmbSettings` additions** — all nullable with safe defaults + `organizer-config.yaml` doc lines:
  `closeTimeoutSeconds` (5), `dialBackoffThreshold` (3), `dialBackoffWindowSeconds` (60),
  `dialBackoffCooldownSeconds` (30), `poolSweepIntervalSeconds` (30), `maxIdleMinutes` (10),
  `maxConnectionAgeMinutes` (30). (Wave 5 only, if built: `livenessProbeTimeoutSeconds` (3),
  `livenessValidationTtlSeconds` (20).)

## Probe primitive (for the sweep sensor, Wave 3+)

smbj 0.13.0 exposes **no** `Connection.echo()`. Use a **bounded share-stat** as the liveness op:
`DiskShare.getShareInformation()` (no path) or `folderExists("")` on the share root — both are real
SMB2 round-trips already used in the codebase. Run under the bounded-close/probe executor with a short
timeout (`livenessProbeTimeoutSeconds`). (SMB2 ECHO via `connection.send(new SMB2Echo(...))` is
possible but lower-level; only pursue if share-stat proves too heavy.)

**Bounded close must stay GRACEFUL, not force — critical finding (Wave 1 bytecode verification).**
smbj's `SMBClient.connect(host)` keys a `connectionTable` by `host:port`: a live entry is `lease()`d
(refcount++) and **shared**; a new `Connection` is built only when absent/disconnected. So with ~15
volumes across 2 hosts, **many pooled shares share one `Connection` per host.** `Connection.close(true)`
is the FORCE path — it skips `release()` and jumps straight to `transport.disconnect()`, so force-closing
*one* evicted volume **yanks the shared transport out from under every sibling volume on that host**
(their next `isHealthy()` fails → they all re-dial), amplifying the exact thrash Wave 2 exists to kill —
and it gets worse in Wave 4 when idle/age recycling evicts *healthy* shared connections. `close()` /
`close(false)` (GRACEFUL) is refcount-aware: `release()` decrements the lease and **returns without
disconnecting while siblings still hold it**; only the last lease-holder runs the session LOGOFF loop +
`disconnect()`. Therefore: **use graceful `close()`. The bounded executor + timeout — NOT force-close —
is the hang safety net for the LOGOFF loop.** (Earlier drafts of this plan wrongly preferred force-close;
corrected after decoding `SMBClient.connect`/`Connection.close(boolean)` bytecode.)

---

## Wave 1 — Bounded close + foundational seams  *(tiny, pure win, de-risks everything)*

**Why first:** a synchronous `PooledShare.closeQuietly()` on a wedged socket is itself part of the
stall (each of `share/session/connection.close()` is an SMB round-trip that can hang). Making close
bounded is a small, self-contained correctness win that every later wave depends on.

**Changes (`SmbConnectionFactory` + `PooledShare`):**
- Add the injectable `nowMillis` time source and the bounded-close executor.
- `PooledShare.closeQuietly()` → run the share/session/connection close on the bounded executor with
  `closeTimeoutSeconds`; on timeout, abandon it (log) — never block the caller. Prefer
  `connection.close(false)` for the force path.
- `evict()` and `shutdown()` use the bounded close. `shutdown()` still drains best-effort.
- Add `SmbSettings.closeTimeoutSeconds` (+ accessor + defaults + config doc).

**Tests:** injected close that hangs → `evict()` returns within the bound and marks the entry gone;
normal close still completes; `shutdown()` bounded even when a close hangs.

**Gate:** build+test green · Opus diff review · **live smoke**: mount a volume, run a cover backfill +
one folder rename + a video stream — confirm no regression on the happy path.

---

## Wave 2 — Re-dial circuit-breaker / backoff  *(highest value — this is hardening-proposal Item 1)*

**Why:** turns "N items × 10 s failing dials, every 10-min pass" (the audited thrash) into a single
probe per cooldown, and stops the reconciler from hammering an already-degraded NAS.

**Changes (`SmbConnectionFactory.dial()` / `acquire()`):**
- Per-**host** (not per-volume — a VPN switch downs the host) failure counter with timestamps. On the
  Nth (`dialBackoffThreshold`) dial failure within `dialBackoffWindowSeconds`, **open the breaker**
  for `dialBackoffCooldownSeconds`.
- While open: `acquire()`/`dial()` for any volume on that host **fail fast** with a clear
  `IOException("host X in dial-backoff until T")` instead of submitting a dial that will burn the
  timeout. One **half-open** probe dial is allowed after the cooldown; success closes the breaker,
  failure re-opens it (optionally with capped exponential growth).
- A successful dial (any path) resets the host's counter.
- Reuse `NasAvailabilityMonitor` where natural, but the breaker lives in the factory (it keys on dial
  outcomes, which the monitor doesn't see).

**Tests (time-source driven):** K failures in window → breaker opens → next acquire fails fast without
calling `dial()`; after cooldown one probe is attempted; probe success closes breaker; probe failure
re-opens. Failures spread beyond the window don't trip it.

**Gate:** build+test green · Opus diff review · **advisor-vet** (breaker semantics are subtle) · live
smoke: force a dead host (e.g. wrong host briefly) and confirm fast-fail + recovery.

**Known limitation (accepted for Wave 2):** the breaker keys on **host**, but `dial()` fails the same
way whether the failure is host-level (`client.connect`/`authenticate` — the VPN-switch case we
optimize for) or volume-level (`connectShare` — a bad/permission-denied share name). So a single
persistently-misconfigured volume can open its host's breaker and briefly fast-fail *healthy sibling
volumes* on that host for a cooldown cycle. This is a deliberate tradeoff (a VPN switch genuinely
downs the whole host) and it self-recovers via the half-open probe. Optional future refinement: count
only `connect`/`authenticate` failures (host-level) toward the breaker, not `connectShare`.

---

## Wave 3 — Network-change teardown  *(the proposal's Stage 2)*

**Why:** react to the confirmed trigger (network reconfiguration) by clean-tearing-down + lazily
re-establishing, instead of discovering severance op-by-op. Value = stop thrashing + clean
re-establish (NOT session reclaim — see correction).

**Changes:**
- `invalidateAll()` on the factory: cancel in-flight dials + bounded-close & remove every pooled entry
  + reset breakers. Next `open()` re-dials lazily.
- **Background pool-sweep** (daemon, `poolSweepIntervalSeconds`): probes each pooled connection with
  the bounded share-stat; evicts dead ones proactively. This sweep is also the **sensor**: when it
  sees **≥2 distinct pooled hosts all fail** within one pass (the Stage-0 multi-host signature — "a
  per-NAS fault cannot down two independent NAS hosts at once"), it fires `invalidateAll()`
  (debounced, once per event). A single-host all-fail (or a pool holding only one host) just evicts
  the dead entries — the `≥2`-host gate is the whole discriminator between a network/VPN event and a
  single-NAS blip, so it must NOT auto-teardown on one host. (This matters more after Wave 4's
  idle-recycling narrows the pool to a single host as the common steady state.)
- **Manual trigger:** an `invalidateAll()` entry point wired to the existing "resume after switching
  VPN" enrichment action (`EnrichmentRunner` ~228) and, optionally, a small `POST /api/smb/reset`
  endpoint / MCP tool as the deterministic backstop.
- (Optional, later) an OS default-route/primary-interface poller as an additional trigger; not
  required for Wave 3 — the all-hosts-down sensor + manual trigger cover the confirmed case.

**Tests:** stubbed sweep where all hosts fail → `invalidateAll()` fires once (debounced); single-host
failure evicts only that host; `invalidateAll()` cancels in-flight + closes all + next open re-dials;
manual trigger works.

**Gate:** build+test green · Opus diff review · advisor-vet the all-hosts sensor (false-positive
analysis vs a real total-NAS outage — note: invalidateAll is still *correct* there, just re-dials) ·
live smoke.

---

## Wave 4 — Recycling: max-idle + max-age  *(the real exhaustion mitigation)*

**Why:** fewer idle sessions open at switch time = fewer to orphan on the NAS; bounded age caps slow
leaks. This — with Wave 2 backoff — is what actually relieves the session-table pressure.

**Changes:**
- `PooledShare` gains `createdAtMillis` + `volatile lastUsedAtMillis` (stamped on `acquire()` hit).
- The Wave-3 sweep also evicts (bounded close) any connection idle > `maxIdleMinutes` or older than
  `maxConnectionAgeMinutes`.
- **Prerequisite — fix the two raw-`fileSystem()` escape sites** that hold a `VolumeFileSystem` across
  operations without the handle (so recycling can't yank the share mid-use): `ExecuteDuplicateTrashTask.java:185`
  and `TitleRoutes.java:618`. Either wrap them in `withRetry`/`open` scopes or re-open per op. (Guard:
  the sweep must not recycle a connection with an op in flight — a simple in-use stamp/refcount, or
  recycle-only-idle, suffices.)

**Tests (time-source):** connection past max-idle is recycled on next sweep; past max-age recycled;
active connection (recent `lastUsedAtMillis`) is not; escape-site fix verified by existing task tests.

**Gate:** build+test green · Opus diff review · live smoke (leave idle, confirm recycle + transparent
re-dial on next use).

---

## Wave 5 (OPTIONAL) — validate-on-borrow  *(deferred refinement; build only if needed)*

Only if a **silent half-open** case actually surfaces after Phase A (op hangs to `readTimeout` with no
EPIPE). Then: cached bounded probe in `acquire()` — `PooledShare.lastValidatedAtMillis`; if
`now - lastValidated > livenessValidationTtlSeconds`, run the bounded share-stat probe; stale/timeout →
evict + re-dial; else fast-path. The TTL cache keeps the hot path cheap. Do **not** build speculatively.

---

## Cross-cutting

- **Public API stays stable** across Phase A (`open`/`withRetry`/`withForceRetry`/`evict`/
  `isVolumeAvailable`/`shutdown` unchanged) → **no caller changes** in Phase A (the ~13 pool call
  sites and ~66 mount consumers are untouched). Only `Application.java` wiring changes (start/stop the
  sweep; pass new settings).
- **Feature-flag** the Wave-3 auto-teardown (config toggle, default on) so it can be disabled if the
  all-hosts sensor misbehaves in the field.
- **Test seams already present:** `protected dial()` (override to simulate outcomes),
  `PooledShare(String subPath)` test ctor, `setDialTimeoutMillisForTesting`. Add the time-source seam
  in Wave 1 and everything else is unit-testable without real SMB.
- **Dispatch:** one Sonnet subagent per wave, sequential (each depends on the prior); Opus gates every
  wave (diff review + build/test + advisor-vet Waves 2–3 + live SMB smoke) before dispatching the next.
- **This plan closes** hardening-proposal Item 1 (Wave 2) and the reconciler-dial-degradation problem;
  the cover-flag-undercount and logging-death items remain independent.

## Acceptance (Phase A)
- A dead/degraded host no longer causes per-item dial thrashing — the breaker fast-fails and probes
  once per cooldown; the reconciler makes progress or backs off cleanly.
- `evict`/`invalidateAll`/`shutdown`/recycle never block a caller, even on a wedged socket.
- A network-reconfiguration event (all pooled hosts down at once, or the manual trigger) triggers a
  bounded clean teardown + lazy re-dial.
- Idle/aged connections are recycled, keeping the NAS session count low between bursts.
- Pool public API and all existing callers unchanged; happy-path SMB verified by live smoke each wave.
