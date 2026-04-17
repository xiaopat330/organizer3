# Proposal: Organize Pipeline

**Status:** Draft
**Prereq for multi-file work:** Probe-videos backfill (see `spec/MCP_NEXT_STEPS.md` option B)
**Legacy reference:** `legacy/operation-config.yaml`

---

## 1. Motivation

Organizer v2 handled the day-to-day library maintenance that Organizer3 has so far
delegated to manual intervention:

- New titles arrive in an ingest area, get normalized, and are filed under the
  appropriate actress's folder.
- Actresses get sorted into tier folders based on title count.
- Edge cases (name corrections that break volume-letter alignment, post-merge
  orphaned folders, etc.) get routed to a human-review area.

Organizer3 has the persistence, mount/sync/probe plumbing, and Phase 2 MCP mutation
primitives in place. What's missing is the composed pipeline that uses these
primitives to actually run the library forward each time new content lands.

This proposal defines that pipeline.

---

## 2. Title lifecycle

The full intended path of a title from arrival to home:

```
[unsorted queue]     ingestion (raw video files)            ← deferred scope
      │                                                       (future work)
      ▼
[global pool]        normalized titles waiting for
      │              cross-volume assignment by a person
      │              (manual — volumes are on different
      │               shares and we never cross shares)
      ▼
[letter-volume pool]  per-volume staging, managed by the
      │               organize pipeline (this proposal)
      ▼
[stars/{tier}/       final home; tier set by actress's
  {actress}/          current title count
  {title-folder}]
```

The boundary the app is responsible for: **letter-volume pool → final home**.
Cross-volume assignment stays manual; the unsorted-queue ingestion stage is
deferred to a future spec.

**Volume-letter mapping** is a pre-existing convention: volume `a` holds actresses
whose canonical name starts with A; `bg` holds B–G; `hj` holds H–J; etc. Volume
`collections` is the home for multi-actress titles.

---

## 3. Pipeline phases

Four phases, each its own operation with clean unit contracts. Each phase is
dry-run-default and transactional where the DB is involved. Phases are composable:

- Phases 1–3 act **per-title** — they operate on the title's folder.
- Phase 4 acts **per-actress** — it operates on the actress's folder.

### 3.1 Phase 1 — normalize

Cleans up filenames inside a title's folder. Pure intra-folder work.

- **Token strip** (remove patterns): delete substrings matching the `removelist`
  (e.g. `hhd800.com@`, `-1080P`, watermark strings).
- **Token replace** (pattern rewrite): `replacelist` applies one-for-one rewrites
  (e.g. `FC2-PPV` → `FC2PPV`, `-Uncen` → `_U`).
- **Cover rename** to canonical `{CODE}.{ext}` (e.g. `ONED-555.jpg`). If the title
  folder has exactly one cover, rename it. If it has multiple, skip and let
  `trash_duplicate_cover` handle it first.
- **Video rename** — single-file titles only: rename to `{CODE}.{ext}`. Multi-file
  titles are punted (see §6.2).

All operations: `fs.rename(path, newName)` — intra-folder, atomic.

### 3.2 Phase 2 — restructure

Arranges the title's folder contents per layout convention: cover at base, videos
in a child subfolder.

- For each video file at the title's base folder, move it into a subfolder chosen
  by **filename hint**:
  - `-4K` in name → `4K/`
  - `-h265` in name → `h265/`
  - anything else → `video/`
- `createDirectories` the child subfolder if absent, then `move` the file.
- **No VideoProbe invocation** at this phase. Filename hints alone are good enough;
  the subfolder name is cosmetic. Probing is reserved for deduplication where
  real signal matters (see §6.2).
- Covers stay at base.

### 3.3 Phase 3 — sort

Moves the title folder from the volume's pool partition into its final home under
the filing actress.

- **Input:** a title folder at `/<pool-partition>/<title-folder>` on the mounted
  volume.
- **Target:** `/stars/{tier}/{actressName}/<title-folder>`, where tier is the
  actress's current tier and actressName is the DB's canonical name.
- Steps:
  1. Resolve filing actress from the title's DB row (`titles.actress_id`).
  2. If unresolvable (actressless title) → route to `attention` (see §4).
  3. Verify the actress's **canonical name starts with a letter covered by this
     volume**. If not (e.g. a rename turned `Aino Nami` into `Nami Aino` on
     volume `a`) → route to `attention`.
  4. `createDirectories(/stars/{tier}/{actressName})` if absent.
  5. `move(source, targetPath)`.
  6. Update `title_locations` with the new path.

The tier at this point is the actress's **current** tier — if she's a new actress
with one title she may be below the `star` threshold (3 titles) and the sort
may defer until she crosses the threshold. See §3.4.

### 3.4 Phase 4 — classify

Re-tier actresses by their current title count.

- For each actress whose titles count crossed a threshold upward since last run:
  1. Compute target tier from thresholds (§5.1).
  2. If target tier > current tier: `move /stars/{oldTier}/{actressName}` →
     `/stars/{newTier}/{actressName}`, updating all affected `title_locations`.
  3. Never demote: if title count drops (merge, rare case), leave the tier alone.
- If an actress is below the `starThreshold`, her titles stay in the volume pool;
  no folder is created until she qualifies. (Same behavior as Organizer v2.)

---

## 3.5 Timestamp correction (integrated into sort)

Title folder timestamps as stored on the NAS often don't reflect *when the title
joined the catalog* — they reflect the most recent copy/move operation, which is
usually meaningless. When sorting by age in a file browser (Finder / QNAP UI),
this makes the listing useless.

Fix: set the title folder's creation and modification time to the earliest
creation-time among the folder's contents. The cover image is the most reliable
signal — it's added by a human curator at the time the title enters the catalog
— and all other child files (videos, thumbnails, sidecars) serve as fallbacks.

**Algorithm:**

1. Walk the title folder's immediate children (non-recursive is enough).
2. Collect each child's `creationTime`.
3. Pick the **earliest** timestamp.
4. Set the title folder's `creationTime` and `lastWriteTime` to that value.
5. Leave `lastAccessTime` alone.

If the folder has no children (shouldn't happen, but defensively): no change;
surface as a warning in the result.

**When it runs:**

- **Phase 3 (sort):** after the `move(pool → stars/{tier}/{actress}/{title})`
  completes, apply the timestamp correction as a final step. Natural time to
  normalize — we're already touching the folder.
- **Audit mode:** a separate `audit_volume_timestamps` tool walks an existing
  volume's title folders and applies the same correction to already-filed titles.
  Useful for one-time backfills and periodic cleanup.

**Filesystem primitive required.** `VolumeFileSystem` gains two methods:

```java
FileTimestamps getTimestamps(Path path) throws IOException;
void setTimestamps(Path path, Instant created, Instant modified) throws IOException;
// Instant args nullable — null means "leave this one alone".
```

Implementations:
- **SmbFileSystem:** `SMB2_SET_INFO` with `FileBasicInformation`; required access
  mask is `FILE_WRITE_ATTRIBUTES`. Folders open with `SMB2CreateOptions.FILE_DIRECTORY_FILE`.
- **LocalFileSystem:** `BasicFileAttributeView.setTimes(...)`; creation time
  supported on macOS APFS.
- **DryRunFileSystem:** logs the intended change.

## 4. Attention partition

The `attention` partition (an unstructured partition on every conventional
structure, already defined in `organizer-config.yaml`) is the human-intervention
queue. The app **moves in; the person decides what to do**.

Triggers for attention routing:
- Sort phase found the title's actress name doesn't start with a letter covered
  by the current volume (post-merge / post-rename case).
- Sort phase found the title has no filing actress (actressless title — e.g.
  amateur-code that couldn't be attributed).
- Any phase encountered an ambiguity it can't resolve (future: e.g. duplicate
  title-code conflict).

**Reason sidecar.** When the app moves a title to `attention`, it writes a file
at the title's base describing why:

```
/attention/<title-folder>/REASON.txt
```

Plain text, one-paragraph explanation, machine-readable header at the top for
tooling:

```
reason: actress-letter-mismatch
volume: a
expected-letter: A
actual-actress: Nami Aino
moved-at: 2026-04-17T04:12:00Z

Title was filed under actress id 4506 "Aino Nami" which was merged into
id 51 "Nami Aino" on 2026-04-16. The canonical name now starts with "N",
which is not covered by volume "a" (letters A only). Move this title
folder manually to volume "n".
```

Rationale: the tag file travels with the folder if the human moves it via the
NAS UI, so context never gets lost.

**Actress-level routing.** The same convention applies when an *entire actress
folder* is routed to attention — e.g. a cross-letter rename that would put her
on the wrong volume (see §7.3). Target path is `/attention/{actressName}/`,
sidecar is `/attention/{actressName}/REASON.txt`. Same format, same semantics;
title folders are just carried along inside.

---

## 5. Configuration

### 5.1 Tier thresholds

Externalized per the legacy `operation-config.yaml` pattern. New block in
`organizer-config.yaml`:

```yaml
library:
  tierThresholds:
    star:      3    # <3 titles → stays in pool, no folder created
    minor:     5    # [3, 5)   → library
    popular:   20   # [5, 20)  → minor
    superstar: 50   # [20, 50) → popular
    goddess:   100  # [50, 100)→ superstar ; >=100 → goddess
```

Thresholds are upward-only by design — only affects moves, never demotes.

### 5.2 Filename normalization rules

Also externalized, sourced from `legacy/operation-config.yaml`. New block:

```yaml
normalize:
  removelist:
    - 'hhd800.com@'
    - '[Thz.la]'
    - '-h264'
    - # ... etc.
  replacelist:
    - { from: 'FC2-PPV',  to: 'FC2PPV' }
    - { from: '-Uncen',   to: '_U' }
    - # ... etc.
```

Initial content is copied verbatim from legacy; we don't re-curate in this
proposal. Future sessions can trim obsolete patterns.

### 5.3 Media extensions

Carried forward from legacy:

```yaml
media:
  videoExtensions: [mp4, mkv, mov, avi, mpg, mpeg, wmv, asf, m4v, m2ts, ts, divx, rmvb, m4v, mp4v]
  coverExtensions: [jpg, jpeg, png, webp]
```

---

## 6. Invariants + constraints

### 6.1 One-way tier movement

Actresses only move up. Never down. The only way a folder moves to a lower tier is
a catalog-wide disposal (out of scope for this pipeline).

### 6.2 Multi-file video handling is gated on probe-backfill

Until `duration_sec` + `width`/`height` + `video_codec` are populated across the
library (option B in MCP_NEXT_STEPS), multi-file titles stay as-is:

- Phase 1 cover-rename: still runs (cover logic is independent of video count).
- Phase 1 video-rename: **skipped** for titles with >1 video file.
- Phase 2 restructure: still runs (moves all videos into a subfolder regardless
  of count).
- Duplicate detection / canonical-pick for videos: deferred entirely.

Post-backfill, we can revisit with real signal:
- Same duration ± 1s + same resolution + same codec → duplicate (pick one).
- Same duration + different codec/resolution → quality tiers (keep best by
  resolution DESC, codec h265 > h264, mtime DESC).
- Different durations → legit compilation set; leave alone.

### 6.3 Never-delete

The pipeline never deletes files. All removals go through Trash
(`spec/PROPOSAL_TRASH.md`).

### 6.4 Intra-volume only

Every move in this pipeline is intra-volume, consistent with the project
invariant. Cross-volume assignment (global pool → letter-volume) remains manual.

---

## 7. Tool surface

### 7.1 Policy: shell vs. MCP

Two surfaces with a clean rule:

- **Shell + MCP** — mechanical operations that a human operator runs in bulk or
  routine. Deterministic given inputs. An agent can also invoke them, and does
  when orchestrating a larger flow, but the primary user is the operator at their
  terminal. Implementation: service class → thin `Command` (shell) + thin `Tool`
  (MCP) both delegate to the service.
- **MCP-only** — judgment operations that benefit from agent reasoning (fuzzy
  matching, candidate weighing, name-pair adjudication). No shell surface —
  the operator has no realistic reason to type these at a prompt, and omitting
  the shell command prevents accidental hand-typed misuse.

The rule of thumb: *if you'd ever want to write "I think X might be a typo for Y"
before invoking the command, it's MCP-only.*

This is consistent with what's already in the codebase: `merge_actresses`,
`find_similar_actresses`, `find_name_order_variants`, `find_alias_conflicts`,
`list_actresses_with_misnamed_folders` are all MCP-only. `sync`, `mount`,
`unmount`, `actresses` are shell + MCP. This proposal adopts the same pattern.

### 7.2 Shell + MCP (this proposal)

All pipeline operations are mechanical — given the DB state and the current file
layout, the correct action is deterministic. Each gets both surfaces.

Per-title:
- `normalize_title { titleCode, dryRun }`
- `restructure_title { titleCode, dryRun }`
- `sort_title { titleCode, dryRun }` — includes timestamp correction as final step
- `fix_title_timestamps { titleCode, dryRun }` — run the correction in isolation,
  for already-filed titles that sort won't revisit.

Per-actress:
- `classify_actress { actressId, dryRun }`

Composite (per-volume):
- `organize_volume { volumeId, dryRun, phases?: [...] }` — walk the pool,
  run phases 1–3 per title, then phase 4 over affected actresses. `phases`
  optional arg lets the caller select a subset (e.g. `["normalize"]` to only
  normalize without moving).
- `audit_volume_timestamps { volumeId, limit?, offset?, dryRun }` — walk every
  title folder on the volume and apply timestamp correction where folder-time ≠
  earliest-child-time. Reports hit count + changes on dry-run. Paginated for
  large volumes.

MCP gating: all require `mcp.allowMutations` + `mcp.allowFileOps`. Each tool
returns a structured plan on dry-run; on execute it returns per-step ok/failure.

### 7.3 MCP-only (related, specified elsewhere)

Actress-name corrections — typo fixes, name-order swaps, alias reassignment —
are MCP-only because the decision *whether* to rename is a judgment call. They
interact with this pipeline via the `attention` partition: a name correction
that changes the canonical's starting letter creates a letter-volume mismatch,
which the sort phase routes to `attention` (§4).

The full spec for those tools (`rename_actress`, `fix_actress_folder`,
`add_alias`, `remove_alias`, `reassign_alias`, `attribute_title`) belongs in a
separate proposal — they're agent-judgment work, not pipeline work. This
proposal only commits to the policy that says they'll be MCP-only and
references the attention-routing mechanism they depend on.

---

## 8. Safety model

- **Dry-run defaults:** every tool defaults to `dryRun: true`.
- **Transaction scope:** DB writes (title_locations updates) go in a single
  SQLite transaction per title.
- **Failure isolation:** a failure on one title does not abort a composite
  run; the composite collects per-title results and reports.
- **Stop-on-first option:** composite accepts `stopOnError: true` for debug
  runs.

---

## 9. Delivery order

Each step a separate PR; tests required for each.

1. **Config ingestion** — add `library.tierThresholds`, `normalize`,
   `media` blocks to `organizer-config.yaml`; load legacy patterns;
   surface via `AppConfig`.
2. **Timestamp primitives** — extend `VolumeFileSystem` with `getTimestamps` /
   `setTimestamps`; implement on Local / Smb / DryRun; add `fix_title_timestamps`
   + `audit_volume_timestamps` tools. Ship standalone; earns value on its own
   (one-off backfill) and unblocks the sort-phase integration later.
3. **Phase 1: `normalize_title`** — removelist/replacelist/cover rename,
   single-file video rename. Real-data smoke on a volume's pool.
4. **Phase 2: `restructure_title`** — filename-hint-based subfolder move.
5. **Phase 3: `sort_title`** — pool → actress-folder move; attention routing;
   REASON.txt sidecar; apply timestamp correction as final step (reuses step 2).
6. **Phase 4: `classify_actress`** — upward tier re-assignment.
7. **Composite `organize_volume`** — orchestrate phases 1–4 across a volume.
8. **Multi-file handling** (after probe-backfill): extend §6.2 policies into
   phases 1 and a new `dedupe_videos` tool.

---

## 10. Out of scope

- **Ingest / unsorted queue processing** — separate future spec. The unsorted
  volume is set aside entirely in this pipeline.
- **Cross-volume movement** — forever manual per intra-volume invariant.
- **Tier demotions** — by design.
- **Actress catalog disposal** — separate workflow.
- **Multi-file video dedup + canonical rename** — deferred until probe-backfill
  completes, then revisit §6.2.
