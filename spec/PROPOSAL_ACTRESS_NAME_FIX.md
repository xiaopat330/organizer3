# Proposal: Agentic Actress Name Typo Fix via MCP

**Status:** Code shipped on `actress-merge-command` — 2026-04-26
**Remaining for handoff:** local config wiring (`.claude/settings.json`, `organizer-config.yaml`) + one-shot manual smoke test
**Scope:** Detection → DB merge → folder rename, orchestrated by Claude via organizer3 MCP

---

## Status Summary

The code half of this proposal has shipped in three commits on the
`actress-merge-command` branch (not yet merged to `main`):

| Commit | What it adds |
|--------|-------------|
| `feat(actress): add 'actress merge' command for typo cleanup` | Shell command `actress merge <suspect> > <canonical>` + `ActressMergeService` (DB merge + folder rename in one shot) + 13 service tests |
| `refactor(actress): extract renameOnly + planRenamesFor for post-merge cleanup` | Splits the FS rename half into reusable `planRenamesFor()` / `renameOnly()` so it can run without re-doing the DB merge; alias-aware folder matching (longest-prefix wins); +14 tests |
| `feat(mcp): add rename_actress_folders tool` | MCP tool wired into `Application.java` under `fileOpsAllowed`; +9 tool tests with fake `SessionContext` + `VolumeFileSystem` |

All 36 new tests pass. Full suite is green.

What's NOT done (and why it can't be code): see [§Remaining work](#remaining-work) — both items live in user-local files that are gitignored or
outside the repo.

---

## Problem

Actress name typos appear in two places:

1. **The database** — a misspelled canonical name on an `actresses` row, with titles incorrectly attributed to it.
2. **The filesystem** — title folders named after the typo actress (`Rin Hatchimitsu (FNS-052)/`) that remain after (or instead of) a DB correction.

Both were manual. The DB side already had `merge_actresses`, but no tool renamed the actual folders on disk. Scanning for typos across a large collection was also tedious without automated detection.

---

## What Now Exists

### MCP tools (read-only — already shipped before this branch)

| Tool | What it does |
|------|-------------|
| `find_similar_actresses` | Levenshtein-distance pairwise scan across all canonical names and aliases. Surfaces misspelling candidates (distance ≤ 2 by default). |
| `find_name_order_variants` | Given/family name swap detection. |
| `merge_actresses` | Full DB-side actress merge: reassigns `titles`, rewrites `title_actresses`, migrates aliases, merges engagement flags, deletes the source row. Defaults to `dryRun:true`. |
| `list_actresses_with_misnamed_folders` | Returns actresses whose filed-title folder paths don't contain their canonical name — the post-merge cleanup worklist, ranked by mismatch count. |
| `find_misnamed_folders_for_actress` | Drills into a single actress: returns each mismatched path, flagging which alias (if any) appears in it. |

### MCP tool added on this branch

| Tool | What it does |
|------|-------------|
| `rename_actress_folders` | Renames every misnamed title folder for one actress on the currently mounted volume. Locations on other volumes appear in `skipped`; folders whose names don't start with any known alias appear in `unresolvable`. Default `dryRun:true`, gated on `fileOpsAllowed`. |

### Shell command added on this branch

`actress merge <suspect> > <canonical>` — same end-to-end flow as the MCP path, usable from the interactive shell. Documented in `spec/USAGE.md`. Honours session dry-run.

---

## Code Map (for picking up the work)

| Concern | File |
|---------|------|
| Service (DB merge + FS rename) | `src/main/java/com/organizer3/command/ActressMergeService.java` |
| Shell command | `src/main/java/com/organizer3/command/MergeActressCommand.java` |
| MCP tool | `src/main/java/com/organizer3/mcp/tools/RenameActressFoldersTool.java` |
| Tool registration | `Application.java` line ~764 (under `mutationsAllowed && fileOpsAllowed` block) |
| Service tests | `src/test/java/com/organizer3/command/ActressMergeServiceTest.java` (27 tests) |
| Tool tests | `src/test/java/com/organizer3/mcp/tools/RenameActressFoldersToolTest.java` (9 tests) |
| User docs | `spec/USAGE.md` — `actress merge` section |

### Service API surface (what the MCP tool calls)

```java
public class ActressMergeService {
    // Existing — full merge: DB rewrites + folder renames in one transaction-ish flow
    MergePreview preview(Actress suspect, Actress canonical);
    MergeResult execute(MergePreview, mountedVolumeId, VolumeFileSystem, boolean dry);

    // New — post-merge cleanup: folder renames only, alias-aware
    RenamePlan planRenamesFor(Actress actress);
    RenameResult renameOnly(RenamePlan, mountedVolumeId, VolumeFileSystem, boolean dry);
}
```

`planRenamesFor` queries filing `title_locations` for the actress where the path doesn't contain her canonical name (`instr(LOWER(path), LOWER(canonical)) = 0`), then matches each folder name against her aliases longest-first to compute a new path. Unmatched paths land in `RenamePlan.unresolved()`.

### `rename_actress_folders` input/output

**Input:**
```
actress_id   integer   Either this or 'name' required.
name         string    Canonical name or alias to resolve.
dryRun       boolean   Default true.
```

**Output:** `Result { actressId, canonicalName, dryRun, mountedVolumeId, renamedCount, skippedCount, unresolvableCount, renamed[], skipped[], unresolvable[] }`

---

## Remaining Work

These are **local user-config edits** — they cannot live in the repo because the files are either outside it or gitignored. The next session/operator should:

### 1. Wire organizer3 MCP into Claude Code

Edit `.claude/settings.json` (currently only has `permissions`):

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

### 2. Enable file ops in organizer-config.yaml

The active config is gitignored. Add (or confirm):

```yaml
mcp:
  allowMutations: true
  allowFileOps: true
```

Both flags are required: `rename_actress_folders` is registered inside the `mutationsAllowed && fileOpsAllowed` block in `Application.java`.

### 3. Smoke test

With organizer3 running and a volume mounted:

1. From Claude Code, call `find_similar_actresses` to surface a known-typo pair.
2. `merge_actresses(from=..., into=..., dryRun=true)` then `dryRun=false`.
3. `find_misnamed_folders_for_actress(name="<canonical>")` to confirm leftover folders.
4. `rename_actress_folders(name="<canonical>", dryRun=true)` to preview.
5. `rename_actress_folders(name="<canonical>", dryRun=false)` to execute.
6. Verify on disk + via DB that `title_locations.path` was updated.

### 4. Merge the branch

`actress-merge-command` → `main` once the smoke test passes.

---

## End-to-End Workflows (already supported by the shipped tools)

### Case A: Known typo, one actress

User says: *"actress merge Rin Hatchimitsu → Rin Hachimitsu"*

1. `merge_actresses(from=<suspect_id>, into=<canonical_id>, dryRun=true)` — show plan.
2. User confirms.
3. `merge_actresses(from=<suspect_id>, into=<canonical_id>, dryRun=false)` — DB merge executed; suspect's canonical name added as alias of canonical.
4. `find_misnamed_folders_for_actress(name="Rin Hachimitsu")` — shows which folder paths still need renaming and on which volumes.
5. For each volume with mismatches:
   - `mount_volume(volume_id=...)` if not already mounted.
   - `rename_actress_folders(name="Rin Hachimitsu", dryRun=true)` — preview renames.
   - User confirms.
   - `rename_actress_folders(name="Rin Hachimitsu", dryRun=false)` — renames executed.
6. Report any volumes that couldn't be reached (user mounts and re-runs later).

**Shell shortcut:** `actress merge "Rin Hatchimitsu" > "Rin Hachimitsu"` does steps 1–5 in one go for the currently mounted volume.

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

- [x] Add `renameOnly(...)` method to `ActressMergeService` (or expose rename logic separately) — `planRenamesFor()` + `renameOnly()`, sharing a private `performFsRenames` helper with `execute()`
- [x] Implement `RenameActressFoldersTool` under `fileOpsAllowed`
- [x] Register tool in `Application.java` (same pattern as other fileOps tools)
- [x] Tests: unit tests for path-resolution logic; integration test for the full rename path with in-memory SQLite + mock `VolumeFileSystem` — 27 service tests + 9 tool tests
- [x] Document `actress merge` in `spec/USAGE.md`
- [ ] Update `.claude/settings.json` with `mcpServers.organizer3` entry (user config — outside repo)
- [ ] Enable `mcp.allowMutations: true` and `mcp.allowFileOps: true` in `organizer-config.yaml` (user config — gitignored)
- [ ] Smoke test the full agentic flow against a real mounted volume
- [ ] Merge `actress-merge-command` → `main`
