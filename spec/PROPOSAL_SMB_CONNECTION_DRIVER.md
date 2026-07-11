# PROPOSAL: Centralized SMB connection driver (staleness self-healing)

**Status:** DRAFT — design only. **Stage 0 CONFIRMED 2026-07-11** (see Stage 0 findings below): the
staleness is a client-side network-reconfiguration event (VPN switching), verified by log analysis +
user ground-truth. Ready for an implementation planning pass on Stages 1–4.
**Motivation:** SMB connections go stale and the app hangs onto them too long: an operation
blocks until a timeout fires before the dead connection is evicted. The 2026-07-11 bulk-promote
audit (see `spec/PROPOSAL_RESILIENCY_HARDENING.md`) traced a `pandora.local` **SMB session-table
exhaustion** — the host answered TCP :445 instantly while new `SESSION_SETUP` hung, and the app's
liveness checks reported the host/connection "healthy" throughout. Stage 0 confirmed the generator is
**VPN switching** (used to bypass javdb rate limiting): each switch reconfigures the routing table and
**severs existing SMB TCP connections mid-flight** (even direct-LAN ones under split-tunnel), leaving
sessions orphaned on the NAS. This proposal centralizes SMB connection ownership behind a single
driver that detects staleness with a **real SMB liveness probe** (not a TCP/`isConnected()` check),
self-heals by clean-teardown + re-dial, and reacts to the known VPN-switch event.

## Background — the current state (why this is a real seam, not gold-plating)

There are **two independent `SMBClient` lifecycles**, sharing no state or health story:
- **`SmbjConnector` → `SmbVolumeConnection`** — its own `SMBClient`/`Session`/`DiskShare`, used for
  **interactive mounts**, stored in `SessionContext.activeConnection`.
- **`SmbConnectionFactory`** — a *separate* per-volume `SMBClient` **pool** (`Map<volumeId,
  PooledShare>`), used for **all background/server ops** via `withRetry` / `withForceRetry`
  (reconciler, cover writes, streaming).

During the audit these diverged: the mounted `SmbVolumeConnection` kept working (covers healed
through it) while the factory pool was evicted and could not re-dial. Two connections to the same
volume, opposite fates, no coordination.

**Liveness checks today are shallow and mislead:**
- `SmbConnectionFactory.open()` evicts on `!connection.isConnected()` — but smbj's `isConnected()`
  reflects only local socket state and returns `true` for a **half-open / black-holed** socket.
- `NasAvailabilityMonitor` probes each host by **TCP-connecting to port 445** every 30 s. During the
  outage pandora's :445 answered in 0.01 s while SMB auth hung — so the monitor would have reported
  pandora **available** the entire time. **TCP-reachable ≠ SMB-usable.**

**VPN switching is external to the app.** The app only detects rate limiting (`429`/`403`) and
**logs** "consider switching VPN" (`EnrichmentRunner` ~359/370); the user switches manually. There
is already a **manual "resume after switching VPN"** affordance (`EnrichmentRunner` ~228). So a
teardown hook cannot be a direct callback from the switch itself — it must piggyback a user-initiated
signal and/or a detection trigger.

### Confirmed failure chain (VPN switch → transition-time TCP severance → NAS session orphaning)
**Refined from the Stage 0 measurement.** The setup is **split-tunnel**, not full-tunnel: the NAS
(`pandora.local` = `192.168.86.58`) routes **direct via `en7`** (LAN) while internet routes through
the VPN (`utun11`). So the mechanism is *not* a routing black-hole. Instead, a VPN connect/switch
**reconfigures the whole routing table**, and even direct-LAN SMB sockets are **severed during the
transition** — observed as a `broken pipe` on the live connection, then a failed/timed-out re-dial.
Because the severed socket is never cleanly closed (no SMB `LOGOFF`/FIN reaches the NAS), the **NAS
holds the session** until its own idle timeout; repeated switches during a long scrape-and-process run
→ **orphaned sessions accumulate → session table exhausts → new logins hang** — the audited failure.

## Stage 0 — Confirm before building (measurement, no code) — **DONE 2026-07-11**

**Method:** parsed ~385 K log lines across 4 rotated files (07-10 04:31 → 07-11 01:19), extracting
SMB connection-failure events (evict / dial-timeout / broken-pipe, with host+volume) and rate-limit
`429`/`403` events (the only in-log VPN-switch proxy).

**Findings:**
- **9 314 SMB failure events in 12 clusters; 8 clusters are MULTI-HOST** — both independent NAS hosts
  (`pandora.local` *and* `qnap2.local`) failing simultaneously across ~all 15 volumes. A per-NAS
  fault cannot do this → the cause is **client/network-level**.
- **Every multi-host cluster onset = `broken pipe → evict → re-dial times out`** — live TCP
  connections *severed*, not a NAS refusing fresh dials from cold.
- **Not sleep/wake** — logging is continuous up to each onset (no suspend gap).
- **Recurring ~every 1–4 h** over the 20 h run.
- The in-log rate-limit proxy was too sparse to prove causation on its own (2 bursts vs 8 clusters,
  loose timing), **but user ground-truth confirmed the multi-host cluster times mostly match manual
  VPN switches.** → **VPN switching is the confirmed primary trigger.**
- **Tunnel mode = split** (LAN direct via `en7`; internet via `utun11`).

**Design consequence:** the trigger is a *network-reconfiguration* event. The app cannot hook the
external VPN switch directly, but it **can** observe the reconfiguration it causes — so Stage 2 leads
with an **OS network-change signal** (more general than a VPN-specific hook; also catches WiFi flap /
wake / DHCP renew).

## Design principle

One driver **owns every SMB connection**. All SMB access — interactive mount and background ops —
routes through it. It maintains per-volume connections, validates them with a real SMB round-trip,
self-heals stale ones by **bounded clean-close + re-dial**, exposes passive health, and reacts to
invalidation events (VPN switch). Evolve the existing `SmbConnectionFactory` into this driver — it is
already ~80% there (per-volume pool, in-flight dial coalescing, `withRetry`, `evict`, bounded
read/dial/transact timeouts). **Not** a generic pool library (commons-pool2): SMB sessions are
volume- and auth-bound, not fungible like DB connections — a generic pool is an impedance mismatch.

## Staged plan (highest value first)

### Stage 1 — Real SMB liveness (fixes "hang onto stale too long")
- **Replace the shallow checks with a bounded SMB round-trip.** A cheap liveness op — an SMB2 ECHO
  (`Connection.send` of a keepalive) or a `stat` of a known path (e.g. share root) — with a **short,
  dedicated timeout** (~2–3 s, well under the 45 s read timeout). This is the single source of
  "is this connection actually usable."
- **Two layers:**
  - *Validate-on-borrow*: before handing a pooled connection to `open()`/`withRetry`, run the probe;
    on failure, evict + re-dial (backstop; adds a small bounded latency per borrow — measure and
    consider a "recently validated within T seconds" cache to avoid probing on every call).
  - *Passive background probe*: upgrade `NasAvailabilityMonitor` from TCP-445 to the **SMB-level**
    probe, so staleness is caught before an operation needs the connection. Keep it cheap and
    infrequent, and **back off when a host is already degraded** (don't add load to a struggling NAS).
- **Bounded clean-close on evict.** Closing a hung socket can itself hang → evictions must close with
  a timeout / off-thread, never blocking the caller. (Order per `SmbVolumeConnection`: share →
  session → connection → client.)
- **Value:** stale connections are detected proactively and dropped+reestablished, instead of being
  discovered by a hanging operation. Directly answers the user's "drop stale + reestablish" question.

### Stage 2 — React to the network-change event (addresses NAS session orphaning)
Stage 0 confirmed the trigger is a **network reconfiguration** (VPN switch), which the app cannot
hook directly but *can* observe.
- **Add `invalidateAll()` to the driver:** bounded clean-close (SMB `LOGOFF` so the NAS reclaims the
  session) + evict every pooled connection; next use re-dials lazily once the network has settled.
- **Primary trigger — OS network-change signal:** watch macOS primary-interface / default-route
  changes (SCNetworkReachability / `SCDynamicStore`, or a lightweight default-route poll). On a
  change, debounce briefly (let routing settle) then `invalidateAll()`. This is general — it catches
  the confirmed VPN switches *and* WiFi flap / wake / DHCP renew — and needs no VPN integration.
- **Backstop trigger — user-initiated:** extend the existing "resume after switching VPN" action (and
  optionally a dedicated "reset connections" button/endpoint) to also call `invalidateAll()`, for
  when detection misses or the user wants to force it.
- **Corroborating auto-detector:** a liveness-probe-failure spike across *all* hosts at once is the
  Stage-0 multi-host signature — usable to confirm a route change vs a single-NAS blip and avoid
  invalidating everything for one flaky volume.
- **Value:** turns the sever-and-orphan chain into a clean teardown at the moment the network changes
  — reacting to the event instead of hanging on its aftermath. Most likely to prevent recurrence of
  the audited session exhaustion.

### Stage 3 — Unify the two clients (structural coherence)
- Make the driver the **single** `SMBClient` owner. `SmbjConnector` / `SmbVolumeConnection` (mount)
  becomes a thin consumer that **borrows** a per-volume connection from the driver instead of
  building its own. `SessionContext` holds a handle/reference, not an independent connection.
- Preserve the mount UX: `MountProgressListener` phases (connecting → authenticating → opening share)
  are driven by the driver's dial.
- **Value:** one connection per volume, one health story; eliminates the diverging-fates behavior the
  audit exposed. Larger change than 1–2, so it lands after the self-healing wins.

### Stage 4 — Recycling policy (leak insurance)
- **Max-age:** recycle any connection older than N minutes regardless of health — cheap insurance
  against slow leaks / gradual server-side session drift.
- **Max-idle:** close connections unused for M minutes so the NAS session count stays low when the
  app is idle (relieves the exhaustion pressure between bursts).
- Both use the Stage 1 bounded-close path.

## Relationship to other proposals
This **absorbs Item 1** of `PROPOSAL_RESILIENCY_HARDENING.md` (reconciler defeated when fresh dials
fail despite a live connection): once every op routes through one driver with real liveness +
self-heal, the reconciler no longer has a private, separately-dying connection. Items 2 (cover-flag
undercount) and 3 (logging death) remain independent and still stand.

## Acceptance criteria
- A black-holed / half-open connection is detected by the liveness probe and dropped+reestablished
  **before** an operation hangs on it (no more "hang until timeout, then evict").
- `NasAvailabilityMonitor` reflects SMB usability, not just TCP-445 reachability (a session-exhausted
  host that still answers :445 reports **unavailable**).
- A VPN-switch signal cleanly tears down all SMB connections (NAS sessions released) and subsequent
  ops transparently re-dial once the network settles.
- Interactive mount and background ops share one connection per volume; killing/degrading one does
  not leave the other stranded.
- Every close/evict is bounded — a hung socket never blocks a caller or the driver.

## Tests
- Liveness probe: stub a connection whose `isConnected()==true` but whose probe round-trip
  times out → driver treats it as dead, evicts, re-dials.
- Validate-on-borrow returns a *fresh* connection after a simulated stale one; "recently validated"
  cache suppresses redundant probes within T.
- `invalidateAll()` closes + evicts all volumes and the next `open()` re-dials; close is bounded even
  when the underlying close hangs (injected).
- Monitor reports unavailable when the SMB probe fails though TCP-445 is up (mirrors the audit).
- Unified path: a mount and a background `withRetry` on the same volume use the same pooled
  connection; eviction heals both.
- Recycling: a connection past max-age is recycled on next borrow; idle past max-idle is closed.

## Out of scope
- Fixing pandora's SMB service / VPN configuration (operational, NAS/host-side).
- Automatic VPN switching by the app (remains external/manual; we only react to it).
- A generic connection-pool dependency.
- Multi-connection-per-volume / channel bonding (single connection per volume retained).
