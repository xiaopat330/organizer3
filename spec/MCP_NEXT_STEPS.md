# Where We Left Off — 2026-04-16 end of day

Working notes for resuming tomorrow.

## What shipped today

Commit `b764db8` on `main` (pushed):

- **Phase 0 — prep** (`FreshPrepService`): raw videos in a queue partition →
  `(CODE)/<video|h265>/{file}` skeletons. Algorithm strips legacy junk-prefix
  tokens, parses product code, handles digit-prefix labels (300MIUM), 7-digit
  FC2PPV, reorders underscore suffixes around encoding tokens
  (`ONED-999-h265_4K` → `(ONED-999_4K)`).
- **`prep_fresh_videos`** MCP tool (gated on `allowFileOps` + `allowMutations`).
- **`prep-fresh <partitionId> [limit] [offset]`** shell command.
- **PROPOSAL_ORGANIZE_PIPELINE.md §3.0** added; **INSTRUCTION_MANUAL.md** Session B
  rewritten as the prep walkthrough.
- 16 new unit tests; full suite green.

## Proven live

Smoke-ran `execute()` against `/Volumes/jav_unsorted-1/fresh`:
26 raw `hhd800.com@{CODE}-h265.mkv` files → 26 `(CODE)/h265/{CODE}-h265.mkv`
skeletons. 0 failed, 0 skipped. No `hhd800.com@*` files remain in `fresh/`.

Digit-prefix label `(300MIUM-1353)` parsed correctly. All JUR/FFT/IENF/NGOD/
RKI/SVGAL/URE/DLDSS/HZGD/ACHJ codes resolved cleanly.

## Uncommitted local-only

`src/main/resources/organizer-config.yaml` carries local dev tweaks that stay
out of git:
- per-server `trash: _trash`, `sandbox: _sandbox`
- `mcp.allowMutations / allowNetworkOps / allowFileOps: true`

## Loose ends carried forward

- **`spec/USAGE.md` not updated** — today's MANUAL update covered the same
  ground in more depth, but USAGE.md still lacks `prep-fresh`. Small cleanup.
- **`arm`/`test` shell toggle still not wired** — the shell is dry-run-locked.
  All pipeline execution happens via MCP. `prep-fresh` in the shell is
  read-only-in-practice.
- **Claude Desktop MCP** needs an app restart to pick up `prep_fresh_videos`.
  The stale PID 40173 was killed today; next time the app starts, the new
  tool will be in the catalog.
- **Human curation step after prep** — the 26 newly-prepped skeletons now
  sitting in `fresh/` need actress assignment + covers before they can be
  moved to letter-volume queues for the rest of the pipeline. That's manual
  work (not app-managed).

## Three likely next moves

Ranked by payoff.

### 1. Generalize the prep beyond the legacy pattern

Today's live batch was 100% the `hhd800.com@{CODE}-h265.mkv` shape — easy
mode. The real `fresh/` inbox over time will be messier:
- non-h265 encodings (plain, 4K without h265 hint)
- paired `_a`/`_b` files (algorithm handles them but not yet seen live)
- Japanese-text folder names in the preserved `(JDXA-57536-)` pattern —
  the trailing dash case worth noting
- codes the regex doesn't yet cover (low-confidence edge cases)

Audit the other 156 already-prepped folders in `fresh/` against what our
algorithm *would have* produced. Mismatches are signal — either the
algorithm is wrong, or the existing folders were hand-curated off-pattern.

### 2. Post-prep workflow tooling

Once a human curates a prepped skeleton (actress + cover), moving it to a
letter-volume's queue is currently manual cross-volume work (always manual
per intra-volume invariant). But we could add a *diagnostic* MCP tool that
audits the `fresh/` folder for "ready to graduate" skeletons — has an
actress-ish sibling folder or cover file, has been stable N days, etc.

### 3. `spec/USAGE.md` + manual cross-check

Low-urgency housekeeping. Treat as a background task.

## Open design questions (still valid, inherited)

- **`size_bytes` column on videos** — deferred from schema v18; needs
  `VolumeFileSystem.size()` added across implementations.
- **Multi-file title dedup** — `PROPOSAL_ORGANIZE_PIPELINE.md §6.2` policy;
  blocked on probe-backfill.

## One-line recap

Phase 0 prep shipped + proven on real data (26/26). Pipeline now covers the
full flow from raw ingest through actress re-tier. Next session is open
ground — generalize the prep, or pivot back to probe-backfill / data-quality
cleanups.
