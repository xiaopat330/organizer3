# Proposal: MCP Tools for AI-Driven Volume Curation

**Status:** Phase 1 SHIPPED 2026-05; Layer D ABANDONED 2026-05-19 — manual curation is sufficient. Will revisit only if real friction emerges.
**Last updated:** 2026-05-19
**Source incidents:** Volume `s` actress-typo cleanup session 2026-05-08 (`memory/project_volume_s_typo_session_2026_05_08.md`) + cross-volume backlog (tasks #2–#11)
**Estimated total effort:** ~28 dev-days across 5 phases

---

## 1. Executive summary

The current MCP tool surface assumes the on-disk folder layout is already canonical. When typo-driven actress duplicates, romanization drift (Western names through Japanese), and multi-actress basename mistakes appear, the AI has to fall back on raw SQL + manual SMB shell operations to clean up. This proposal closes the gap with **17 new tools + 1 primitive + 1 architectural mandate**, organized into five build phases.

The destructive composites (#10, #12, #14, #16) are gated by mandatory pre-flight verification (#15) and a structured curation log so partial failures are recoverable.

**Phase 1 alone retires the active manual backlog** (s-session task #8 + cross-volume tasks #2–#11) and is the highest-priority deliverable.

---

## 2. Goals & non-goals

### Goals

- An AI agent can perform end-to-end actress + title curation on a mounted volume without dropping into raw SMB shell operations.
- All destructive operations have a `dryRun:true` default and a structured plan output before mutation.
- Every destructive run leaves an audit record sufficient for manual rollback.
- Pre-flight checks catch DB↔disk drift before it corrupts state.

### Non-goals (explicitly out of scope)

- **Cross-volume operations.** Memory rule "all file operations are within a single volume" stays in force. Any inter-volume movement remains a manual user step.
- **Auto-detecting typos** as a deterministic operation. Discovery tools surface candidates; AI inference + user confirmation decide.
- **Touching tier-root directories** (`/stars/`, `/stars/<tier>/`, `/queue/` root, `/attention/` root) directly outside the actress-folder unit.
- **Replacing the organize pipeline.** These tools are for curation/cleanup, not initial sorting.
- **Backwards-compatibility shims** for the existing `rename_actress_folders` quirks. New tools supersede; old behavior preserved by default flags.

---

## 3. Problem catalog (real cases that drove this)

| Situation | Current tool gap |
|---|---|
| Parent folder `Shien Fujimoto/` misnamed; canonical is `Shion Fujimoto`, same tier | No in-place actress-folder rename; existing tools force detour through `/_attention/` |
| `rename_actress_folders` skips entire actress when parent doesn't match canonical | Inner-folder renames coupled to parent state |
| `Sumire Yuki` is a substring of `Sumire Yukie`; misnamed-detection reports clean | No token-boundary mode |
| Multi-actress folder `Sumume Mino, Aoi (DLDSS-289)` reported as unresolvable | No multi-actress-aware basename rebuild |
| Need to drop `NOTES.md` in `/attention/` summarizing manual followups | No general write-text on share |
| Title for actress A filed inside actress B's folder, same volume | No intra-volume title re-parent |
| Cross-volume planning — where does this actress live? | Only per-volume `lookup_actress` |
| Shelly Fuji: 3 misspelled folders (`Sherry Fujii`, `Sheiri Fuji`, `Shelly`), no canonical folder, on multiple volumes | No fold-into-canonical workflow |
| Multi-actress title `W, X, Y, Z (CODE)` with X and/or Z misspelled | No basename rebuild from DB credits |
| Same title code appearing in two folders on the same volume | Duplicate-base-codes finder exists, but no fold |
| Folder on disk with no DB row at all (sync drift) | `find_orphan_titles` covers DB-side only |

---

## 4. Architectural mandates (apply to all destructive tools)

### 4.1 `dryRun:true` default

Every destructive tool defaults to `dryRun:true` and returns a complete plan. Callers explicitly opt into mutation with `dryRun:false`.

### 4.2 Single-volume invariant

Every destructive tool operates on **the currently-mounted volume only**. Tools refuse with a clear error if any input path resolves to a different volume.

### 4.3 Mandatory pre-flight via #15

Composites (#10, #12, #14, #16) call the verify tools (#15) as the first plan step. Blockers refuse the plan; warnings are surfaced in the plan output but allow execution.

### 4.4 Structured curation log

Every destructive invocation (real or `dryRun`) appends one JSONL record to:

```
<dataDir>/curation-log/<volumeId>/<YYYY-MM-DD>.jsonl
```

Schema:

```json
{
  "ts": "2026-05-09T20:14:33Z",
  "tool": "consolidate_actress_folders",
  "actor": "mcp",
  "sessionId": "...",
  "inputs": { ... },
  "plan": { ... },
  "before": { /* relevant on-disk + DB state snapshot */ },
  "after":  { /* same shape, post-execution; null on dry-run */ },
  "status": "ok" | "partial" | "failed" | "dry-run",
  "errors": [ ... ]
}
```

Read-only tools (`find_*`, `list_*`, `verify_*`) do **not** log. Retrofit applies to existing destructive tools (`merge_actresses`, `rename_actress`, `rename_actress_folders`, `move_actress_folder_to_attention`, etc.) — they must emit log records too.

### 4.5 Test discipline

Per memory rule (`feedback_testing.md`): every new tool ships with repository tests against in-memory SQLite and command tests using Mockito. Composites get integration tests covering: dry-run shape, single-volume refusal, blocker refusal, conflict refusal, idempotent re-run.

### 4.6 Observability

Per memory rule (`project_logs_viewer.md`): destructive tools log richly at INFO+ on critical paths so the in-app Logs viewer can tail behavior during long curation sessions.

---

## 5. Tool catalog

Tools are numbered for cross-reference. Build order is in §7.

### Layer A — Primitives

#### A1. `write_text_file` *(was #4)*

Drop `NOTES.md`, breadcrumbs, audit logs into safe top-level dirs on the mounted volume.

```
write_text_file(
  volumeId: string,
  path: string,                     // volume-relative
  content: string,
  overwrite: bool = false
) -> { path, bytes, status }
```

Allowlist of writable top-level prefixes: `/attention/`, `/queue/`, `/_sandbox/`. Extensions: `.md`/`.txt`/`.json`/`.yaml`/`.log`. Refuses when `overwrite=false` and path exists.

#### A2. `delete_empty_folder` *(primitive)*

```
delete_empty_folder(
  path: string,
  allowSidecars: bool = true       // .DS_Store, REASON.txt, sidecar metadata are deletable
) -> { path, deleted: bool, reason }
```

Refuses on non-empty folders, tier roots, volume root, or any path still appearing as a `title_locations.path` prefix.

#### A3. `rename_actress_folder` *(was #1)* ⭐

Rename actress parent folder in-place within its current tier. Eliminates the `/_attention/` detour.

```
rename_actress_folder(
  actress_id: int,
  fromName: string | null,         // optional override if folder isn't reachable via known aliases
  dryRun: bool = true
) -> { mountedVolumeId, from, to, updatedPaths[], status, errors[] }
```

Refuses on collision, on actress folder not found, or on folder containing no DB-tracked title.

#### A4. `rename_title_folder` *(was #2)*

Force-mode single-title rename. Caller provides full new basename. Independent of actress canonicalization rules.

```
rename_title_folder(
  titleCode: string,
  newFolderName: string,           // basename only
  dryRun: bool = true
) -> { from, to, status }
```

#### A5. `move_title_folder` *(was #3)*

Intra-volume re-parent.

```
move_title_folder(
  titleCode: string,
  toActressId: int | null,         // resolves to canonical → /stars/<her tier>/<her name>/
  toAbsolutePath: string | null,   // explicit, for edge cases (mutually exclusive with toActressId)
  dryRun: bool = true
) -> { from, to, status }
```

Auto-creates destination actress folder if missing. Updates `title_locations`.

### Layer B — Small extensions to existing tools

#### B1. `find_misnamed_folders_for_actress` strict-mode flag *(was #5)*

```
find_misnamed_folders_for_actress(
  actress_id: int,
  strict: bool = false             // default preserves current behavior
)
```

When `strict=true`: a path is misnamed unless `<canonical name>` appears bounded by `/`, `(`, or end-of-segment on both sides. Catches the `Yuki/Yukie` substring class.

#### B2. `find_similar_actresses` volume scope *(was #6)*

```
find_similar_actresses(
  max_distance: int = 2,
  min_length: int = 4,
  limit: int = 100,
  volume_id: string | null,        // NEW: only return pairs where ≥1 side has titles on this volume
  require_full_name: bool = false  // NEW: both sides have ≥2 tokens
)
```

### Layer C — Discovery (read-only)

#### C1. `list_actress_locations` *(was #7)*

Multi-volume view; drives session planning.

```
list_actress_locations(actress_id: int)
-> {
  canonicalName,
  aliases[],
  perVolume: [{
    volumeId,
    parentFolderPath,
    parentFolderMatchesCanonical: bool,
    titleCount,
    titles: [{code, path, folderMatchesCanonical}]
  }]
}
```

#### C2. `find_actress_folder_candidates`

Folder-shaped discovery for fold-into-canonical workflows. Catches cases where `find_similar_actresses` misses (titles already DB-credited to canonical but living in misspelled folders).

```
find_actress_folder_candidates(
  actress_id: int,
  volume_id: string | null,
  min_score: float = 0.3
) -> {
  canonicalName, aliases[],
  candidates: [{
    folderPath, titleCount,
    nameSimilarity: float,           // best Levenshtein-normalized score vs canonical + alias
    creditOverlap: float,            // titles-in-folder DB-credited to actress_id ÷ titleCount
    dbCreditedTitleCount: int,
    sampleTitleCodes: [string],
    score: float                     // combined; sort key
  }]
}
```

Walks every actress-shaped folder on the volume + `/queue/` flat residents grouped by leading actress token.

#### C3. `find_multi_actress_folder_drift`

Detects basename ↔ DB-cast mismatches across the volume regardless of folder location.

```
find_multi_actress_folder_drift(volume_id: string | null)
-> {
  drifts: [{
    titleCode, folderPath,
    parsed: {
      cast: [{ position, raw, resolvedActressId, resolvedVia, closestActress?, closestDistance? }],
      description: string | null,
      code: string
    },
    dbCredits: [{actressId, canonicalName, position}],
    issues: ["misspelled-position" | "missing-cast-member" | "extra-cast-member" | "code-mismatch" | "unresolvable-name"],
    severity: float
  }]
}
```

#### C4. `find_fs_only_titles`

Folders on disk with no DB row. Sync drift detection.

```
find_fs_only_titles(volume_id: string | null)
-> {
  results: [{
    folderPath, parsedCode: string | null, parsedCast: [string],
    sizeBytes, childFileCount
  }]
}
```

#### C5. `verify_actress_folder_state` / `verify_title_folder_state`

Pre-flight checks. Called by composites; also exposed standalone.

```
verify_actress_folder_state(actress_id: int)
verify_title_folder_state(titleCode: string)
-> { blockers: [...], warnings: [...] }
```

Checks include: sync drift, stale `title_locations`, unexpected children, multi-cover-at-base, unparseable basename. Reuses existing `scan_title_folder_anomalies` internally — does not reimplement.

### Layer D — Composites (destructive)

#### D1. `merge_actresses_with_folder_plan` *(was #8)*

Composite for one-loser → canonical merge with auto folder strategy.

```
merge_actresses_with_folder_plan(
  from: int, into: int,
  folder_strategy: "auto" | "skip" | "attention" = "auto",
  dryRun: bool = true
)
```

`auto`: detects layout per-volume — uses `rename_actress_folder` (A3) when loser-side parent exists with no canonical conflict; otherwise `move_actress_folder_to_attention`. For queue-flat layouts uses `rename_actress_folders` (existing).

#### D2. `homogenize_actress_folder`

Composite cleanup of one actress's folder: rename parent + normalize every inner title basename, with DB-driven classification for un-prefixed and wrong-prefix folders.

```
homogenize_actress_folder(
  actress_id: int,
  parentFolderName: string | null,
  renameParent: bool = true,
  reparentForeignTitles: bool = false,
  dryRun: bool = true
) -> {
  parent: {from, to, willRename},
  children: [{
    titleCode, fromBasename, toBasename,
    classification: "canonical" | "alias-rewrite" | "missing-prefix" | "wrong-prefix" | "foreign-title" | "unknown-code" | "multi-actress",
    dbCanonicalActresses: [{id, name}],
    action: "rename" | "skip" | "report-only" | "propose-reparent",
    notes
  }],
  errors[]
}
```

Per-child classification (DB-driven, not just string match):
- Title code unknown in DB → `unknown-code`, report-only.
- Credits include `actress_id`, basename starts with canonical → `canonical`, skip.
- Credits include `actress_id`, basename starts with known alias → `alias-rewrite`, rename.
- Credits include `actress_id`, basename has no actress prefix → `missing-prefix`, prepend canonical.
- Credits include `actress_id`, basename starts with non-alias string → `wrong-prefix`, rename to canonical.
- Credits do **not** include `actress_id` → `foreign-title`. Report-only by default; `reparentForeignTitles=true` emits `move_title_folder` plan.
- Multi-actress credit → `multi-actress`, rename to `<canonical>, <co-stars> (CODE)`.

Order: children first, parent last.

#### D3. `consolidate_actress_folders`

Fold misspelled actress folders into canonical, then delete emptied sources. See §8 case study (Shelly Fuji).

```
consolidate_actress_folders(
  actress_id: int,
  sourceFolderPaths: [string],
  destinationStrategy: "rename-source" | "create-fresh" = "rename-source",
  homogenizeAfter: bool = true,
  reparentForeignTitles: bool = false,
  dryRun: bool = true
) -> {
  destination: {path, createdHow: "renamed-from" | "created" | "exists"},
  perSource: [{
    sourcePath,
    perChild: [{titleCode, fromBasename, toBasename, classification, action, notes}],
    sourceWillBeDeleted: bool, blockers[]
  }],
  homogenizePlan: {...} | null,
  errors[]
}
```

`rename-source`: pick largest source, rename to canonical, fold remaining sources in. `create-fresh`: mkdir canonical, move every title in.

After consolidation, deletes empty sources via A2. If `homogenizeAfter:true`, chains into D2 on the destination.

#### D4. `rebuild_title_folder_from_db`

Multi-actress basename correction. Preserves on-disk cast ordering.

```
rebuild_title_folder_from_db(
  titleCode: string,
  preserveDescription: bool = true,
  castOrder: "preserve-basename" = "preserve-basename",
  dryRun: bool = true
) -> {
  from, to,
  parsed: {cast[], description, code},
  rebuilt: {cast[], description, code},
  unresolvedPositions[],
  status
}
```

Rationale for fixed `preserve-basename` order: project relies on alias system for normalization; canonical reordering is unnecessary and would risk reshuffling artifact-meaningful order.

Refuses on unresolvable positions (drops back to a report so user can add an alias first), no-op renames, and sibling collisions.

#### D5. `fold_duplicate_title_folders`

Same title code in 2+ folders on a single volume.

```
fold_duplicate_title_folders(
  titleCode: string,
  keeperPath: string | null,       // null → auto-pick by heuristic
  dryRun: bool = true
) -> {
  keeper, losers[],
  fileMoves: [{from, to, reason}],
  conflicts: [{name, keeperSize, loserSize}],
  losersDeleted: bool[],
  status
}
```

Auto-keeper heuristic: deepest actress-folder-resident → most videos → most recent mtime. Refuses on file-content conflicts.

### Layer E — Reporting

#### E1. `volume_curation_report` *(was #9)*

End-of-session dashboard. Surfaces every actress on the mounted volume whose state would benefit from cleanup.

```
volume_curation_report() -> {
  mountedVolumeId,
  misnamedParents: [{actress_id, expected, actual, titleCount}],
  unresolvableMultiActress: [{path, candidates[]}],
  queueResidents: [{titleCode, folder, actresses[]}],
  fsOnlyTitles: [...],
  driftedMultiActress: [...],
  duplicateBaseCodes: [...]
}
```

---

## 6. Case study: Shelly Fuji (volume `s`, also `classic_pool`, `qnap`)

A real example motivating C2 + D3 + A2.

- **Canonical (DB):** `Shelly Fuji`
- **On-disk folders observed:** `Sherry Fujii`, `Sheiri Fuji`, `Shelly`. **No** `Shelly Fuji/` folder exists on some volumes.
- **Why current tools fail:**
  - `find_similar_actresses` only catches it if each misspelling is its own DB entity. If titles inside are already credited to canonical, similarity scan returns nothing.
  - `find_misnamed_folders_for_actress` looks for canonical/alias matches in basename — these don't have any.
  - `rename_actress_folder` (A3) renames *one* source to canonical but doesn't fold multiple sources together.
  - The pattern recurs frequently for Western names romanized through Japanese and back: Sherry/Sheiri/Shelly, Sora/Sola, Saryu/Saru, Aisa/Arisa.
- **Workflow with new tools:**
  1. `find_actress_folder_candidates(actress_id=Shelly, volume_id=s)` → ranked list with credit-overlap signal exposes all three folders even when name similarity is weak.
  2. User/AI confirms candidate set.
  3. (If any are separate DB entities) `merge_actresses` → canonical Shelly.
  4. `consolidate_actress_folders(Shelly, [...], destinationStrategy="rename-source")` — picks largest source, renames to `Shelly Fuji`, folds others in, deletes empty husks.
  5. Auto-chained `homogenize_actress_folder(Shelly)` normalizes inner title basenames.

Becomes the canonical workflow for any "Western-name romanization drift" actress. Runnable once per volume.

---

## 7. Implementation plan

### Sonnet compatibility scale

- 🟢 **Sonnet-direct.** Clear primitive, straightforward CRUD shape. Sonnet implements + tests with normal review.
- 🟡 **Sonnet-with-review.** Multi-step logic, parsing, or ordering. Sonnet implements; pre-merge review by human or Opus-class model recommended.
- 🔴 **Opus-or-careful.** Composite with classification logic, transactional concerns, or many call sites. Opus-class model handles design + implementation, or sonnet implements behind a tight design doc with mandatory pre-merge Opus review.

### Phase 1 — Foundation (week 1, ~5 dev-days)

Unblocks the active manual backlog (s-session task #8 + cross-volume tasks #2–#11). Highest-priority deliverable.

| ID | Tool | Est | Sonnet |
|---|---|---|---|
| A1 | `write_text_file` | 0.5d | 🟢 |
| A2 | `delete_empty_folder` | 0.5d | 🟢 |
| A3 | `rename_actress_folder` | 1d | 🟢 |
| A4 | `rename_title_folder` | 0.5d | 🟢 |
| A5 | `move_title_folder` | 1d | 🟢 |
| §4.4 | Curation-log JSONL writer + retrofit existing destructive tools | 1.5d | 🟡 |

**Dispatch:** all 🟢 except curation-log retrofit (🟡). Sonnet implements primitives directly. Curation-log retrofit touches many existing tools — Sonnet writes the writer + adds it to one tool as a pattern, human reviews, then Sonnet propagates to remaining call sites.

**Phase exit criteria:** s-session task #8 + cross-volume tasks #2–#11 can be processed end-to-end without raw SMB shell ops. Curation log emits valid JSONL on every destructive call.

### Phase 2 — Small extensions (~1 dev-day)

| ID | Tool | Est | Sonnet |
|---|---|---|---|
| B1 | `find_misnamed_folders_for_actress` strict-mode | 0.5d | 🟢 |
| B2 | `find_similar_actresses` volume + full-name scope | 0.5d | 🟢 |

**Dispatch:** Sonnet-direct. Can interleave with Phase 1 as filler.

### Phase 3 — Discovery & verification (~7.5 dev-days)

Enables informed action on Phase 4 composites.

| ID | Tool | Est | Sonnet |
|---|---|---|---|
| C1 | `list_actress_locations` | 1d | 🟢 |
| C5 | `verify_actress_folder_state` / `verify_title_folder_state` | 1.5d | 🟡 |
| C4 | `find_fs_only_titles` | 1d | 🟢 |
| C2 | `find_actress_folder_candidates` | 2d | 🟡 |
| C3 | `find_multi_actress_folder_drift` | 2d | 🟡 |

**Dispatch:**
- C1, C4: Sonnet-direct.
- C5: Sonnet implements; calls existing `scan_title_folder_anomalies` + DB cross-check. Pre-merge review for the blocker/warning taxonomy.
- C2: Sonnet implements ranking math; pre-merge review for the score weighting between name-similarity and credit-overlap (Shelly case must rank correctly).
- C3: Sonnet implements basename parser + DB-credit comparator. Pre-merge review for parser robustness on edge cases (descriptions containing commas, codes with non-standard prefixes).

### Phase 4 — Composites (~12.5 dev-days)

Heaviest phase. Order matters: each builds on Phase 1 + 3.

| ID | Tool | Est | Sonnet |
|---|---|---|---|
| D1 | `merge_actresses_with_folder_plan` | 2d | 🟡 |
| D2 | `homogenize_actress_folder` | 3d | 🔴 |
| D4 | `rebuild_title_folder_from_db` | 1.5d | 🟡 |
| D5 | `fold_duplicate_title_folders` | 2d | 🟡 |
| D3 | `consolidate_actress_folders` | 4d | 🔴 |

**Dispatch:**
- D1: Sonnet implements composition; pre-merge review for the auto-strategy decision tree.
- D2 (🔴): **Opus-class for design + implementation, or Sonnet behind a tight design doc + mandatory Opus pre-merge review.** Per-child classification logic (7 classifications), DB-driven for 4 of them, dryRun↔execute parity, curation-log integration. Highest-leverage composite — getting it right matters.
- D4: Sonnet implements with the existing parser from C3. Pre-merge review for unresolvable-position handling.
- D5: Sonnet implements with the auto-keeper heuristic. Pre-merge review for file-conflict refusal logic.
- D3 (🔴): **Opus-class.** Largest composite. Two destination strategies, recursive use of D2, source deletion ordering, transactional semantics across N source folders. Build last — it's the most likely to need rework.

### Phase 5 — Reporting (~2 dev-days)

| ID | Tool | Est | Sonnet |
|---|---|---|---|
| E1 | `volume_curation_report` | 2d | 🟡 |

**Dispatch:** Sonnet implements as an aggregation over Phase 3 discovery tools. Pre-merge review for output completeness.

---

## 8. Risks & open questions

### Risks

- **Curation-log retrofit blast radius.** Adding logging to every existing destructive tool touches ~6–8 files. Mitigation: ship the writer as a small reusable component; one PR per retrofit batch.
- **D2/D3 classification drift.** If the 7-way classification taxonomy in D2 needs to evolve, every test using its output churns. Mitigation: codify the taxonomy as an enum + JSON schema in Phase 4 kickoff.
- **D3 partial failure recovery.** If `consolidate_actress_folders` fails halfway through fold of N source folders, state is heterogeneous. Mitigation: per-source atomic boundary (each source completes fully before moving to the next), curation log as audit trail, refusal to retry without explicit `resume` flag.
- **`find_multi_actress_folder_drift` parser fragility.** Basenames like `Cast - Description with - hyphens (CODE)` ambiguous. Mitigation: parser walks back from trailing `(CODE)` and uses last ` - ` as separator; surfaces ambiguous parses as warnings rather than guessing.
- **Sonnet underestimation of dryRun↔execute parity.** A common failure mode: dryRun output doesn't match execute behavior. Mitigation: every composite ships an integration test asserting `plan(dryRun) == record_of(execute)` for a fixture scenario.

### Open questions to resolve at planning time

- Should the curation log live under `<dataDir>/curation-log/` or under a per-volume location on the share itself? Memory rule "application data lives under dataDir" suggests dataDir; cross-machine continuity may argue for share. **Default recommendation: dataDir** (consistent with existing convention; user can tail across machines via the existing logs viewer).
- Should D3's `homogenizeAfter:true` chain into D2 *within the same MCP call* or return a follow-up plan that the AI must execute as a second call? **Default recommendation: same call** for atomicity, with both phases in one curation-log record.
- Should A3 `rename_actress_folder` accept `actress_id` only, or also a free-form `fromName` for un-DB'd folders? **Default recommendation: both, with `actress_id` required** — `fromName` is the override for folders that don't match any alias.
- Should `find_actress_folder_candidates` (C2) score weighting be tunable per call, or fixed? **Default recommendation: fixed weights initially** (60/40 credit-overlap to name-similarity); revisit if Shelly-class cases mis-rank.

---

## 9. Build sequencing summary

```
Phase 1 (5d, week 1) ──────────────► retires manual backlog
   │
   ├── Phase 2 (1d, filler)
   │
   └── Phase 3 (7.5d, weeks 2-3) ──► enables informed action
         │
         └── Phase 4 (12.5d, weeks 3-5) ──► high-leverage composites
               │
               └── Phase 5 (2d, week 5) ──► dashboard
```

**Critical path:** Phase 1 → Phase 3 verify (C5) → Phase 4 D2 → Phase 4 D3.

**Parallelizable:** Phase 2 anywhere; C1/C4 can run alongside Phase 1; D1/D4/D5 can run in parallel within Phase 4 after C5 lands; E1 last.
