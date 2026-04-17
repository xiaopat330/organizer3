# Where We Left Off — 2026-04-17 end of day

Working notes for resuming tomorrow.

## What shipped today

Two commits on `main` (pushed):

**`0228d3c`** — Prep phase hardened against real corpus:
- **Suffix-label exception branch** in `FreshPrepService.planOne`. Codes ending
  in `-1PON` or `-CARIB` are kept literal (no uppercasing, no canonicalization) —
  separator preserved as-is (`_`, `-`, or space). Emitted via a small whitelist
  regex checked before the standard `CODE_REGION` pattern.
- **`FC2PPV ` → `FC2PPV-`** replacelist entry. Normalizes the space-separated
  variant into the dashed canonical form so existing FC2PPV handling catches it.
- 4 new unit tests.

**`f5298fe`** — Post-prep diagnostic:
- **`FreshAuditService`** — classifies each `(CODE)` folder in a queue partition
  into `READY` / `NEEDS_COVER` / `NEEDS_ACTRESS` / `EMPTY` / `OTHER`.
  Readiness signals: actress prefix in folder name + cover at base + video inside.
- **`audit-fresh <partitionId>`** shell command (read-only).
- **`audit_fresh_skeletons`** MCP tool (read-only, unconditional — no `allowFileOps`
  gate required).
- 10 new unit tests.

## Proven live

**Prep execute on the 37 new raws in `unsorted/fresh`:** 37/37 moved, 0 skipped,
0 failed. Includes the first live 1PON case (`hhd800.com@041126_001-1PON-h265.mkv`
→ `(041126_001-1PON)/h265/...`). No raw `hhd800.com@*` files remain.

**Folder audit of the 156 pre-existing prepped folders:** algorithm reproduces
145 exactly, 1 intentional mismatch (actress workspace folder `_JAV_Ameri_Ichinose`),
10 unparseable (7 Western dotted names + 3 freeform — all intentionally ignored
per user rules). After the fix: net coverage 152/156 (97%).

**Live audit of `unsorted/fresh` (post-prep):** 193 folders total. 192 in
`NEEDS_ACTRESS`, 1 in `OTHER` (`_JAV_Ameri_Ichinose`), 0 `READY` / `NEEDS_COVER` /
`EMPTY`. Matches expected state — curation hasn't happened yet.

## Uncommitted local-only

`src/main/resources/organizer-config.yaml` still carries local dev tweaks out
of git:
- per-server `trash: _trash`, `sandbox: _sandbox`
- `mcp.allowMutations / allowNetworkOps / allowFileOps: true`

The `FC2PPV ` → `FC2PPV-` replacelist line ships in today's commit; selective
`git apply` was used to keep the other local tweaks out.

## Loose ends carried forward

- **`spec/USAGE.md` updated today** — `prep-fresh` and `audit-fresh` both
  documented. MANUAL not yet updated for audit (optional; MANUAL's post-prep
  walkthrough could add an audit example).
- **`arm`/`test` shell toggle still not wired** — the shell remains dry-run-locked.
  Pipeline execution happens via MCP or test harness.
- **Claude Desktop MCP** needs an app restart to pick up both `prep_fresh_videos`
  (from yesterday) and `audit_fresh_skeletons` (today).
- **Human curation is the blocker** — 192 `NEEDS_ACTRESS` folders now sit in
  `unsorted/fresh`. Until some of them get curated, there's no way to validate
  the READY / NEEDS_COVER / graduation pipeline end-to-end against real data.

## Three likely next moves

Ranked by payoff.

### 1. `arm`/`test` shell toggle

The shell has been dry-run-locked since prep shipped. Every mutation path runs
through MCP or a test. Wiring a `arm` / `test` pair of commands (and a session
flag) would let a human drive the pipeline directly from the shell. Scope:
- Session flag (already exists conceptually via `ctx.isDryRun()`).
- Two new commands: `arm` flips the flag off (live mode), `test` flips it on.
- Every mutation command already checks the flag — audit to confirm.
- Prompt indicator already renders `[*DRYRUN*]` when armed-down.

Not strictly required (MCP covers the mutation path), but it closes the gap
that made yesterday's "prep-fresh in the shell is read-only-in-practice" note
necessary.

### 2. Extend audit with auto-graduation routing (deferred "option B")

Once some folders enter `READY`, the next friction is computing each one's
destination letter-volume + queue path. A small extension to `FreshAuditService`
would include the routing target per READY entry:
`Yua Aida (ONED-1234) → //pandora/jav_TZ/queue/...`. Reuses existing
letter→volume mapping. ~30 lines.

Deferred today because `READY` count is 0; only worth building once the curation
queue has produced some candidates.

### 3. `size_bytes` + multi-file dedup (inherited)

The older architecture debt. Adding `size()` to `VolumeFileSystem`, a schema
column backfill, and the dedup policy (`PROPOSAL_ORGANIZE_PIPELINE.md §6.2`).
Blocked-in-concept on probe-backfill as before. Independent work stream.

## Open design questions (still valid, inherited)

- **`size_bytes` column on videos** — deferred from schema v18; needs
  `VolumeFileSystem.size()` added across implementations.
- **Multi-file title dedup** — `PROPOSAL_ORGANIZE_PIPELINE.md §6.2` policy;
  blocked on probe-backfill.

## One-line recap

Prep generalized against the real corpus (152/156 coverage, 37/37 live proven)
and post-prep audit shipped. Next friction point is human actress-curation
throughput, not code. Pick up `arm`/`test` toggle, auto-graduation routing, or
probe-backfill when returning.
