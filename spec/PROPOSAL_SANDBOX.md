# Sandbox Mechanism

> **Status: PROPOSAL — peer to `PROPOSAL_TRASH.md`, not yet implemented**

A per-volume scratch area owned exclusively by the app. Anything placed in sandbox is volatile by convention — the app may create, move, or overwrite items there at any time, and users are expected to treat its contents as disposable.

---

## 1. Motivation

The app needs a safe place on every volume to do real work that touches the SMB share without risking user data:

- **Integration testing.** Validating file ops against a real server — not a fake FS — requires a location where the test can create synthetic structures, exercise the op, and verify the outcome without touching real library folders.
- **Staging / buffering.** Future operations (transcodes, sync-stage writes, partial downloads) need a known-safe scratch area before results land in their final location.
- **Parity with Trash.** Both are app-owned, per-volume, at share root. Symmetric design.

Without sandbox, any live-FS smoke test either touches real data (risky) or contorts itself to avoid side effects. Sandbox makes "hit the real share, do a real op, throw it away" the obvious path.

---

## 2. Design Principles

- **App-owned, volatile by convention.** The user is expected to treat sandbox contents as disposable. The app never promises longevity; items may be overwritten or removed on subsequent app actions.
- **Per-volume physically, per-server in config.** Each share gets its own `_sandbox` folder at root. Configured once per server; applies to all volumes on that box.
- **No sidecar metadata.** Volatility means metadata is meaningless. If an item needs to persist with context, it doesn't belong in sandbox — use Trash or the library proper.
- **No auto-cleanup.** The app does not wipe sandbox on startup, shutdown, or idle. Cleanup is explicit — either via a dedicated command (future) or by the user via their NAS UI. This keeps the mechanism simple and gives the app freedom to use sandbox for longer-lived buffers across sessions if needed.

---

## 3. Configuration

Sandbox is enabled per server in the server block of `organizer-config.yaml`. Omitting the `sandbox` key disables sandbox on that server.

```yaml
servers:
  - id: pandora
    host: pandora
    trash: _trash
    sandbox: _sandbox
  - id: qnap2
    host: qnap2
    trash: _trash
    sandbox: _sandbox
```

Each volume on a configured server gets its own `_sandbox` folder at share root on first use. Folder name is taken from the server's `sandbox` setting.

---

## 4. Usage

Sandbox is a writable directory the app can use freely. No special operation; any file op the app already supports (move, rename, createDirectories) accepts a sandbox-relative path.

For integration testing specifically:

1. `sandbox.createDirectories("test-run-<uuid>/")` — isolate each test run
2. Create synthetic structures, exercise the op under test, verify the outcome
3. Leave contents in place at end of run (volatile-by-convention — no cleanup required)

Each test run uses a unique sub-path so concurrent runs don't collide, but sandbox itself does not enforce isolation — that's the caller's responsibility.

---

## 5. Applicability

Sandbox exists wherever app-side scratch space is useful:

- Integration tests that need to exercise real SMB move/rename/trash semantics
- Transcode working directory (future)
- Sync-time staging area (future)
- Download / partial-file buffer (future)

It is a general-purpose scratch primitive. Higher-level features may own subpaths within sandbox (e.g. `_sandbox/transcode/`, `_sandbox/tests/`).

---

## 6. Non-Goals

- The app does not enforce that sandbox is empty at any point
- The app does not track sandbox contents or report size
- No cross-volume sandbox (intra-volume invariant applies)
- No restore semantics — items in sandbox are not meant to be retrieved as authoritative state
