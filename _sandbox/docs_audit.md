# Documentation Staleness Audit — 2026-05-04

## Summary

13 docs scanned (CLAUDE.md, README.md, 9 spec files + 2 UTILITIES sub-specs sampled,
PROPOSAL_HOUSEKEEPING_2026_05.md, docs/USER_MANUAL.md). 5 flagged for update, 2 for archive.
4 are essentially fine with minor notes.

---

## High priority — substantively wrong or misleading

### `CLAUDE.md` — UPDATE

**"Not yet implemented" section is 4-for-5 wrong.**

| Claim | Reality |
|---|---|
| "`arm`/`test` mode toggle not yet wired" | `ArmCommand.java` and `TestCommand.java` exist and are registered in `Application.java:311-312`. Shipped. |
| "File operations (move, rename, mkdir) and `DryRunFileSystem`" not implemented | `DryRunFileSystem.java` exists at `src/main/java/com/organizer3/filesystem/DryRunFileSystem.java`; wired in `VolumeFileSystem.java`. Shipped. |
| "Tab completion" not implemented | `OrganizerShell.java:103-114` wires `AggregateCompleter` with `StringsCompleter` for commands and arguments. Shipped. |
| "`actress <name>` detail command" not implemented | `ActressSearchCommand.java` covers `actress search <name>`; the basic `actress <name>` command may still be absent, but CLAUDE.md doesn't distinguish. Needs verification and update. |

**Package structure is stale.**
- `db/` is described as "SchemaInitializer (drop-and-recreate schema)" but `SchemaInitializer.java:10` comments *"No incremental migrations — just drop and recreate as needed during development"* — this is the old docstring, while `SchemaUpgrader.java` is the real migration path. The `db/` package also has `ActressCompaniesService`, `LabelSeeder`, `TagSeeder`, and `TitleEffectiveTagsService` not mentioned.
- Entire packages are missing from the listing: `ai/`, `avatars/`, `enrichment/`, `javdb/`, `mcp/`, `media/`, `organize/`, `rating/`, `translation/`, `trash/`, `utilities/`. These are large, active packages.

**Recommendation:** Replace the "Not yet implemented" list with what actually remains (at minimum `list`/`partitions` commands, `run <action>` organize workflow from the shell). Update the package structure table.

---

### `docs/USER_MANUAL.md` — UPDATE (significant staleness)

This file describes an early version of the app and is misleading on several points:

- **Line 125–136:** States credentials are in the macOS Keychain and `mount` calls `mount_smbfs` at the OS level. Reality (confirmed in `IMPLEMENTATION_NOTES.md` and `CLAUDE.md`): credentials are in `organizer-config.yaml`; smbj is used, no OS-level mounts, no Keychain.
- **Lines 85–112:** Volume structure types listed are only `conventional`, `queue`, `collections`. Missing: `exhibition`, `sort_pool`, `avstars`. The `Actress Tiers` table shows `stars/favorites/` and `stars/archive/` as tier folders, which are not tier folders in the codebase (favorites and archive are not computed tiers).
- **Lines 195–197:** Lists `currentVolume`, `list`, and `partitions` as implemented commands. `list` and `partitions` are explicitly noted as not implemented in `CLAUDE.md`.
- **Lines 215–235:** `run organize`, `run sortActresses`, `run sortTitles`, `run normalizeTitles`, `run moveConverted` commands listed. These do not exist as shell commands — the organize pipeline runs via MCP tools, not `run <action>` shell commands.
- The manual covers none of the app's current features: web UI, MCP server, enrichment, duplicate triage, utilities screens, AV Stars, translation, etc.

**Recommendation:** This is a legacy stub; either archive it or rewrite it from scratch. The `spec/INSTRUCTION_MANUAL.md` is the accurate user-facing doc; this file adds confusion.

---

## Medium priority — outdated but harmless if read carefully

### `spec/FUNCTIONAL_SPEC.md` — UPDATE (§1.3 tier table + §7 schema)

**§1.3 tier table** (lines 56–62) is missing the `Pool` tier:
- Claim: lowest tier is `LIBRARY | < 5`
- Reality: `LibraryConfig.java:13,58` documents and implements `pool` (< 3 titles, no folder). `INSTRUCTION_MANUAL.md:133` correctly shows Pool.
- The tier table in FUNCTIONAL_SPEC should add the Pool row (< 3) and note that the threshold is configurable.

**§7 schema** (lines 249–294) lists ~15 tables but the schema now has 35+ tables. Missing entirely:
`rating_curve`, `av_screenshot_queue`, `draft_titles`, `draft_actresses`, `draft_title_actresses`, `draft_title_javdb_enrichment`, `title_path_history`, `translation_strategy`, `translation_cache`, `translation_queue`, `stage_name_lookup`, `stage_name_suggestion`, `enrichment_review_queue`, `title_javdb_enrichment_history`, `revalidation_pending`. These represent entire shipped features (Draft Mode, Translation Service, Sync × Metadata Preservation, AV Screenshot Queue).

**Recommendation:** Add Pool to tier table. Update §7 to acknowledge the schema has grown significantly and point to `SchemaInitializer.java` for the canonical list.

---

### `spec/IMPLEMENTATION_NOTES.md` — UPDATE (package structure)

**Lines 30–51:** Package structure table lists ~9 packages. The real tree has 21 top-level packages under `com.organizer3`. Missing: `ai/`, `avatars/`, `enrichment/`, `javdb/`, `mcp/`, `media/`, `organize/`, `rating/`, `translation/`, `trash/`, `utilities/`.

**Repository table** (lines 114–125): Missing newer repositories. The `translation/`, `enrichment/`, and `organize/` packages each have their own repositories not listed.

This is cosmetically stale — the notes are accurate about what is listed, just incomplete. Low risk of causing errors.

---

### `spec/ENRICHMENT_TAG_OPS.md` — UPDATE (broken cross-reference)

**Line 11:** `"For the full design rationale see spec/PROPOSAL_TAG_SYSTEM_REVAMP.md"` — this file was archived to `spec/completed/PROPOSAL_TAG_SYSTEM_REVAMP.md`. The path is broken.

**Recommendation:** Update the reference to `spec/completed/PROPOSAL_TAG_SYSTEM_REVAMP.md` or drop the link.

---

### `spec/USAGE.md` — UPDATE (broken cross-reference)

**Line 117:** `"see spec/PROPOSAL_BACKGROUND_THUMBNAILS.md"` — this file does not exist at `spec/PROPOSAL_BACKGROUND_THUMBNAILS.md`. It is in `spec/completed/PROPOSAL_BACKGROUND_THUMBNAILS.md`.

**Recommendation:** Update link to `spec/completed/PROPOSAL_BACKGROUND_THUMBNAILS.md`.

---

## Archive candidates (proposals that have fully shipped)

### `spec/PROPOSAL_TRANSLATION_SERVICE.md` — ARCHIVE when ready

All 5 phases shipped: Phase 0 (complete per line 419), Phases 1–5 confirmed via git PRs #37–40 (`feat(translation): Phase 1` through `feat(translation): Phase 5`). The proposal status header still says `"Draft 2026-05-03 — for discussion, no implementation yet"` which is now wrong.

Move to `spec/completed/`. The proposal is a useful design record.

### `spec/PROPOSAL_HOUSEKEEPING_2026_05.md` — ARCHIVE (per task brief)

All phases landed as of this audit date:
- Phase 1: `UtilitiesRoutesTest` — PR #42
- Phase 2: `av-tools.css` split — PRs #43, #44
- Phase 3: `utilities-javdb-discovery.js` split — PR #45
- Phase 4: `title-browse.js` + `actress-browse.js` splits — PR #46
- Phase 5: `TitleDashboardBuilder` + `ActressDashboardBuilder` extraction — PR #48

Status header still reads `"Status: PROPOSED"`. Move to `spec/completed/`.

---

## Looks fine

- `spec/FUNCTIONAL_SPEC.md §2-6` — Commands, sync flows, web UI, backup/restore sections are accurate. AV Stars content model (§1.4) is accurate.
- `spec/IMPLEMENTATION_NOTES.md` (tech stack, SMB, CommandIO, config shape) — accurate.
- `spec/USAGE.md` (bulk of it) — command descriptions for implemented commands are accurate.
- `spec/INSTRUCTION_MANUAL.md` — accurate, covers the real app including MCP server, organize pipeline, AV Stars, utilities.
- `spec/PROPOSAL_SYNC_METADATA_PRESERVATION.md` — accurately describes phases and status.
- `spec/PROPOSAL_CROSS_VOLUME_MOVES.md` — draft status is accurate; not yet implemented.
- `spec/MISPLACED_FOLDERS.md` — operational tracking doc, not a spec; no accuracy claim needed.
- `spec/MCP_NEXT_STEPS.md` — working notes from 2026-04-17; stale as a "next steps" doc but harmless.
- `spec/ENRICHMENT_TAG_OPS.md` — core content (data model, maintenance tasks) is accurate aside from the one broken link noted above.
- `UTILITIES_*.md` docs — sampled `UTILITIES_LIBRARY_HEALTH.md` and `UTILITIES_VOLUMES.md`; both are marked DRAFT but describe shipped screens. Low-risk staleness.

## Negative results

- `README.md` — single line (`# organizer3`), no claims to verify.
