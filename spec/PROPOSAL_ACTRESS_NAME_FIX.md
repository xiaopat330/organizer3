# Proposal: Agentic Actress Name Typo Fix via MCP

**Status:** Draft — 2026-04-26
**Scope:** Detection → DB merge → folder rename, orchestrated by Claude via organizer3 MCP

---

## Problem

Actress name typos appear in two places:

1. **The database** — a misspelled canonical name on an `actresses` row, with titles incorrectly attributed to it.
2. **The filesystem** — title folders named after the typo actress (`Rin Hatchimitsu (FNS-052)/`) that remain after (or instead of) a DB correction.

Both are currently manual. The DB side already has tooling (`merge_actresses`), but there is no tool that renames the actual folders on disk. Scanning for typos across a large collection is also tedious without automated detection.

---

## What Already Exists

The MCP server already has the pieces for detection and DB correction:

| Tool | What it does |
|------|-------------|
| `find_similar_actresses` | Levenshtein-distance pairwise scan across all canonical names and aliases. Surfaces misspelling candidates (distance ≤ 2 by default). |
| `find_name_order_variants` | Given/family name swap detection. |
| `merge_actresses` | Full DB-side actress merge: reassigns `titles`, rewrites `title_actresses`, migrates aliases, merges engagement flags, deletes the source row. Defaults to `dryRun:true`. |
| `list_actresses_with_misnamed_folders` | Returns actresses whose filed-title folder paths don't contain their canonical name — the post-merge cleanup worklist, ranked by mismatch count. |
| `find_misnamed_folders_for_actress` | Drills into a single actress: returns each mismatched path, flagging which alias (if any) appears in it. |

**The gap:** There is no tool to rename the folders on disk. After a `merge_actresses` call, the DB is correct but the folders still reflect the old name. A volume must be mounted and a `VolumeFileSystem` must be wired in to execute renames.

---

## Proposed Changes

### 1. New tool: `rename_actress_folders`

A new `fileOpsAllowed` tool that renames every misnamed title folder for one actress on the currently mounted volume.

**Input schema:**

```
actress_id   integer   Actress id to fix. Either this or 'name' required.
name         string    Canonical name or alias to resolve.
dry_run      boolean   If true (default), return the plan without executing.
```

**Logic:**

1. Resolve actress + fetch all her aliases from the DB.
2. Query every `title_locations` row where `path` does not contain the actress's canonical name (`instr(LOWER(path), LOWER(canonical)) = 0`).
3. For each row, scan the folder name for any known alias. Compute the new folder name by replacing the matched alias with the canonical name.
4. Partition results by volume: rows on the currently mounted volume are `actionable`; others are `skipped`.
5. On `dry_run:false`: for each actionable row, call `VolumeFileSystem.rename(currentPath, newFolderName)`, then update `title_locations.path` in the DB.
6. Return `{renamed: [...], skipped: [...], unresolvable: [...]}` where `unresolvable` contains paths that don't match any known alias (needs manual inspection).

**Permission gate:** `fileOpsAllowed` — same gate as other FS-mutating tools.

**Implementation note:** This tool needs a reference to `SessionContext` (for the active `VolumeFileSystem` and mounted volume id), which is already the pattern used by `MountVolumeTool` and `OrganizeVolumeTool`.

### 2. Wire `ActressMergeService` to the MCP layer

`ActressMergeService` (introduced on the `actress-merge-command` branch) already contains the filesystem rename logic and the `computeNewPath` pure function. The new tool should delegate to it rather than duplicate the path-computation logic. The service's `execute()` method can be called with the actresses resolved to the same record (merge with `suspect == canonical`) or we can expose just the rename half as a dedicated method on the service.

The cleanest option: add a `renameOnly(Actress actress, String mountedVolumeId, VolumeFileSystem fs, boolean dry)` method to `ActressMergeService` that skips the DB merge step and only handles filesystem renames + path updates. The new MCP tool calls that.

### 3. Connect organizer3 MCP to Claude Code

Add the MCP server entry to `.claude/settings.json`:

```json
{
  "permissions": { "defaultMode": "bypassPermissions" },
  "mcpServers": {
    "organizer3": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

In `organizer-config.yaml`, enable file ops:

```yaml
mcp:
  allowMutations: true
  allowFileOps: true
```

**Prerequisites at session time:** organizer3 must be running, and (for folder renames) a volume must be mounted. Use the `mount_status` tool to check the current state; `mount_volume` to mount one.

---

## End-to-End Workflow

### Case A: Known typo, one actress

User says: *"actress merge Rin Hatchimitsu → Rin Hachimitsu"*

1. `merge_actresses(from=<suspect_id>, into=<canonical_id>, dryRun=true)` — show plan.
2. User confirms.
3. `merge_actresses(from=<suspect_id>, into=<canonical_id>, dryRun=false)` — DB merge executed; suspect's canonical name added as alias of canonical.
4. `find_misnamed_folders_for_actress(name="Rin Hachimitsu")` — shows which folder paths still need renaming and on which volumes.
5. For each volume with mismatches:
   - `mount_volume(volume_id=...)` if not already mounted.
   - `rename_actress_folders(name="Rin Hachimitsu", dry_run=true)` — preview renames.
   - User confirms.
   - `rename_actress_folders(name="Rin Hachimitsu", dry_run=false)` — renames executed.
6. Report any volumes that couldn't be reached (user mounts and re-runs later).

### Case B: Full typo sweep (agentic loop)

User says: *"smell out actress name typos and fix the obvious ones"*

1. `find_similar_actresses(max_distance=1)` — emit all name-similar pairs with distance 1.
2. `find_name_order_variants()` — emit all given/family swap candidates.
3. For each pair where one side has ≤ 3 titles (high confidence it's the typo entry):
   - Show the pair to the user with counts.
   - On confirmation: run Case A workflow above.
4. For pairs where both sides have significant title counts (ambiguous): surface for manual review, don't auto-fix.

### Case C: Post-merge cleanup sweep

After several merges have accumulated, run the cleanup pass:

1. `list_actresses_with_misnamed_folders()` — ranked worklist of actresses with stale paths.
2. For each actress in the list: `find_misnamed_folders_for_actress(actress_id=...)` — confirm the aliases match the paths.
3. Mount each affected volume and run `rename_actress_folders` for each actress.

---

## Multi-Volume Handling

Only one volume is mountable at a time in organizer3. The tool respects this constraint:

- The DB merge (`merge_actresses`) always runs in full regardless of mount state.
- `rename_actress_folders` renames only the currently mounted volume's paths.
- Paths on unmounted volumes appear in the `skipped` response field with their volume ids.
- After mounting a different volume, the user runs `rename_actress_folders` again; it will find remaining mismatches and handle them.
- `find_misnamed_folders_for_actress` remains readable at any time (no mount required) so the full picture of outstanding work is always visible.

---

## Out of Scope

- **Bulk auto-fix without review.** Edit distance is imperfect and the library is large. Any name pair with distance > 1 or both sides with > 5 titles must be user-confirmed before merging.
- **Cross-volume atomic renames.** Files never move between volumes; renames are always intra-volume and atomic.
- **Non-actress path components.** The rename logic only replaces actress name segments; title codes and other path parts are untouched.

---

## Implementation Checklist

- [ ] Add `renameOnly(...)` method to `ActressMergeService` (or expose rename logic separately)
- [ ] Implement `RenameActressFoldersTool` under `fileOpsAllowed`
- [ ] Register tool in `Application.java` (same pattern as other fileOps tools)
- [ ] Tests: unit tests for path-resolution logic; integration test for the full rename path with in-memory SQLite + mock `VolumeFileSystem`
- [ ] Update `.claude/settings.json` with `mcpServers.organizer3` entry
- [ ] Document `rename_actress_folders` in `spec/USAGE.md`
