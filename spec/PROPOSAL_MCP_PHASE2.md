# Proposal: MCP Phase 2 — Mutations, Video Metadata, Folder Cleanup

**Status:** Draft
**Prereq:** `spec/PROPOSAL_MCP_SERVER.md` (Phase 1, shipped)
**Blocked on for Phase 3:** `spec/PROPOSAL_TRASH.md` (undo path for file ops)

---

## 1. Motivation

Phase 1 shipped 20 read-only diagnostic tools. Smoke-testing against the real collection
(53k titles, 6.8k actresses) surfaced a consistent shape of issue that the diagnostic
tools can only describe, not fix:

- **Duplicate actress rows from name-order inversions** (e.g. `Aino Nami` ↔ `Nami Aino`,
  10 pairs found) — both rows have titles attributed, tier calcs, favorite flags. Merging
  them is a deterministic DB op; a pure aliasing fix leaves the duplicate row in place.
- **Alias collisions** (e.g. `Hono Wakamiya` canonical on one actress, alias on another)
  where two *different* people share a spelling — needs alias removal, not merge.
- **Base-code duplicate title rows** (e.g. `BDD-002` + `BDD-02`, `BNDV-00675` + `BNDV-675`)
  — same release indexed under different code spellings.
- **Actressless titles** (10 found) with codes the parser couldn't attribute — parser
  expansion is out of MCP scope, but MCP should be able to fix-up by hand.

Beyond DB state, the user raised two physical-organization problems Phase 1 can't help
with at all:

- **Multi-video titles** where extra videos are accidental quality duplicates, not parts
  of a legitimate compilation set. Current `videos` schema records filename + path only;
  no signal strong enough for an agent to reason about.
- **Filename normalization** (e.g. `ONED-100-h265.mkv` → `ONED-100.h265.mkv`). The legacy
  regex-based approach in Organizer v2 was partially effective; an AI with the right
  metadata can do better.

Phase 2 delivers the plumbing, diagnostics, and DB-layer write tools that address all of
the above. Phase 3 is reserved for on-disk operations once Trash exists.

---

## 2. Physical layout recap (convention)

All Phase 2+ scan/cleanup tools assume this layout:

```
<TITLE_CODE>/              ← base folder (named by title code)
├── cover.jpg              ← cover image(s) live here, at base only
├── video/                 ← video subfolder (legacy name)
│   └── TITLE-100.mkv
└── (or h265/, 4K/)        ← alternative subfolder names; cosmetic, format hint only
```

Derived signals:
- >1 cover at base → duplicates warning
- Cover inside a video subfolder → misfiled
- Child-folder name is a format hint, never authoritative — verify with `VideoProbe`

---

## 3. Foundation: video metadata plumbing

### 3.1 Schema change

Add to the `videos` table:
- `size_bytes INTEGER`
- `duration_sec INTEGER`
- `width INTEGER`
- `height INTEGER`
- `video_codec TEXT`
- `audio_codec TEXT`
- `container TEXT`

All nullable (existing rows are backfilled lazily). Added via `SchemaUpgrader` migration.

### 3.2 Sync-time capture

`VideoProbe` already runs for thumbnail generation; extend sync to probe and write these
fields. One-time backfill command iterates existing rows.

### 3.3 Why in the DB, not on-demand

- Queryable (agents can `sql_query` to find short-duration titles, oversized files, etc.)
- Works offline — no SMB round-trip to answer duplicate questions
- Probing 60k videos once at sync is cheaper than probing per-query

`probe_video` exists as an on-demand escape hatch for spot-checks.

---

## 4. Phase 1b — diagnostic tools (read-only, ship with foundation)

### 4.1 Video-set triage

- `list_multi_video_titles { min_videos?, limit? }` — candidate pool
- `analyze_title_videos { code }` — per-title report: filenames, sizes, durations, codecs,
  resolutions, plus a heuristic verdict `"likely_duplicates" | "likely_set" | "ambiguous"`
  with supporting evidence
- `find_duplicate_candidates { size_tolerance_pct?, duration_tolerance_sec?, limit? }` —
  mass scan flagging multi-video titles whose files look like quality duplicates
- `probe_video { volumeId, path }` — live VideoProbe call for one file

### 4.2 Folder-structure anomalies

- `find_multi_cover_titles { volumeId?, limit? }` — >1 cover at base (duplicate signal)
- `find_misfiled_covers { volumeId?, limit? }` — covers inside video subfolders
- `scan_title_folder_anomalies { code }` — per-title report of all layout deviations

### 4.3 Filename normalization preview

- `propose_rename_video { video_id }` — dry-run only at this phase; returns proposed
  canonical filename + rationale + list of unresolved tokens. AI-assisted, pulls title
  metadata (label, code, base_code) and video metadata (codec, resolution).

---

## 5. Phase 2 — mutation tools

All tools here are gated on `mcp.allowMutations: true` in config. When the flag is off,
Phase 2 tools are hidden from `tools/list`. Every mutating tool:

- Accepts `dryRun` (default `true`)
- Returns a structured `plan` object before executing
- Commits in a single SQLite transaction
- Is idempotent where the operation allows

### 5.1 Actress-level

- `merge_actresses { into, from, dryRun }` — DB-only fold-in:
  - Reassign `titles.actress_id` from → into
  - Rewrite `title_actresses` rows (dedup against existing)
  - Add `from.canonical_name` as alias of `into`
  - Migrate `from`'s aliases to `into`
  - Delete `from`'s row
  - Merge flags per policy (favorite OR, bookmark OR, grade max, tier recalc)

- `add_alias { actressId, aliasName }`
- `remove_alias { actressId, aliasName }`
- `reassign_alias { aliasName, fromActressId, toActressId }` — fixes alias collisions
- `set_actress_flags { actressId, favorite?, bookmark?, rejected? }`

### 5.2 Title-level

- `merge_titles { into, from, dryRun }` — same-release consolidation (BDD-002 + BDD-02)
  - Rewrite `title_locations.title_id` from → into
  - Rewrite `videos.title_id` from → into
  - Rewrite `title_actresses` rows
  - Rewrite `title_tags` rows
  - Delete `from`'s row
- `tag_title { code, tag }` / `untag_title { code, tag }`
- `attribute_title { code, actressIds[] }` — fix actressless titles by manually setting
  `title_actresses` entries

### 5.3 Video-level

- `rename_video { video_id, new_filename, dryRun }` — **intra-folder atomic rename**.
  Same directory, same share. No directory traversal. Updates `videos.filename` + path.
- `mark_duplicate_video { video_id, note? }` — flags a video row as a suspected duplicate
  via a new `duplicate_flag` column, without deleting anything. Feeds Phase 3 cleanup.

---

## 6. Phase 3 — file operations (post-Trash)

Gated on both `mcp.allowMutations: true` AND a separate `mcp.allowFileOps: true`.
Every op is **intra-volume, single-volume-at-a-time, atomic**. No tool sees more than
one volume in a call.

- `rename_actress_folder { actressId, volumeId }` — folder → canonical name
- `consolidate_actress_folders { actressId, volumeId }` — collapse multiple folders for
  the same (merged) actress on one volume into one
- `relocate_by_tier { actressId, volumeId }` — move between tier buckets after merge
- `move_cover_to_base { code, volumeId }` — relocate misfiled covers
- `delete_duplicate_videos { title_id }` — via Trash; removes files flagged by
  `mark_duplicate_video`

---

## 7. Safety model

### 7.1 The dry-run plan object

Every mutating tool returns the same shape before execution:

```json
{
  "dryRun": true,
  "plan": {
    "summary": "Merge actress 4506 'Aino Nami' into 51 'Nami Aino'",
    "changes": [
      { "op": "update", "table": "titles", "rows": 3,
        "predicate": "actress_id = 4506", "setting": "actress_id = 51" },
      { "op": "update", "table": "title_actresses", "rows": 2, ... },
      { "op": "insert", "table": "actress_aliases", "rows": 1,
        "values": { "actress_id": 51, "alias_name": "Aino Nami" } },
      { "op": "delete", "table": "actresses", "rows": 1,
        "predicate": "id = 4506" }
    ],
    "flagsAfter": { "favorite": true, "bookmark": false, "grade": "A" }
  }
}
```

Only with `dryRun: false` does the tool commit.

### 7.2 Transaction scope

Every Phase 2 tool wraps its writes in a single SQLite transaction. If any step fails
the whole operation rolls back. No partial-state failures.

### 7.3 Config flags

```yaml
mcp:
  enabled: true           # Phase 1 default
  allowMutations: false   # Phase 2 gate
  allowFileOps: false     # Phase 3 gate (future)
```

Flipping `allowMutations: true` surfaces Phase 2 tools. Flipping back to `false` hides
them again — no separate unregistration needed; each request is re-checked.

---

## 8. Delivery order

1. **Foundation commit** — schema migration + sync-time video metadata capture + backfill
2. **Phase 1b diagnostics** — all §4 tools, read-only, ship with foundation
3. **Phase 2a** — `merge_actresses` alone, establishes the dry-run/plan pattern
4. **Phase 2b** — remaining actress + title mutation tools
5. **Phase 2c** — video-level mutations (`rename_video`, `mark_duplicate_video`)
6. **Phase 3** — deferred until `PROPOSAL_TRASH.md` is implemented

Each step is a separate PR with tests against the real-data patterns Phase 1 surfaced.

---

## 9. Out of scope

- `mount` / `unmount` / `sync` as MCP tools — network-effecting, deserve their own proposal
- Auth / remote exposure — still localhost-only
- Parser improvements for actressless amateur codes — separate workstream
- Any multi-volume operation — forever out of scope per project invariant
