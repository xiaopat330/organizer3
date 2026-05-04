# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

Organizer3 is a JAV media library manager across multiple NAS volumes over SMB. The primary surface is a local web UI (Javalin + vanilla JS modules); a JLine3 shell and an MCP server provide secondary interfaces. Shipped subsystems include: sync pipeline, persistence (SQLite via JDBI with versioned migrations), enrichment from javdb (with rate-limited queue + draft mode + review queue), translation pipeline (Ollama + tier-2 fallback), actress YAML metadata system, AV Stars (separate volume + IAFD profiles), duplicate triage, organize pipeline, trash sidecar, custom profile images, screenshot queue, and a logs viewer. Active work is housekeeping/stability rather than new features.

Before answering design or implementation questions, always read:
- `spec/FUNCTIONAL_SPEC.md` — what the tool does and how it behaves
- `spec/IMPLEMENTATION_NOTES.md` — technology choices, architecture decisions, and known deviations
- `spec/USAGE.md` — current command reference

## Key Constraints

- **No Spring.** All dependencies are wired manually in `Application.java`.
- **Testing is mandatory.** All new code must be modularized for testability. Repository tests use real in-memory SQLite. Command tests use mocks via Mockito.
- **File operations are always intra-volume.** Moves are atomic; no cross-share operations needed.

## Package Structure

```
com.organizer3
  ai/           ActressNameLookup (Claude API kanji-to-romaji lookup)
  avatars/      AvatarStore, custom actress profile image management
  avstars/      AV Stars feature: model, command, repository, service, iafd/
  backup/       UserDataBackup, UserDataBackupService, RestoreResult
  command/      Shell command implementations (one class per command)
  config/       AppConfig singleton, YAML model records
  covers/       CoverPath utility for local cover image path resolution
  db/           SchemaInitializer, SchemaUpgrader (incremental migrations)
  enrichment/   JavDB enrichment pipeline, review queue, tag definitions
  filesystem/   VolumeFileSystem interface + SmbFileSystem, DryRunFileSystem
  javdb/        JavDB scraping client, slug resolution, enrichment models
  mcp/          MCP server tool handlers
  media/        Video probing, thumbnail generation, streaming utilities
  model/        Domain records: Title, TitleLocation, Actress, ActressAlias, Video, Volume
  organize/     Organize pipeline operations (prep-fresh, sort-title, classify-actress)
  rating/       Rating curve, grade computation
  repository/   Repository interfaces + jdbi/ implementations
  shell/        SessionContext, OrganizerShell, PromptBuilder, CommandIO
  smb/          SmbConnector, SmbjConnector, VolumeConnection
  sync/         Sync operations, VolumeIndex, IndexLoader, TitleCodeParser
  translation/  Local LLM translation service (Ollama adapter, queue, cache, stage-name lookup)
  trash/        Trash sidecar contract, RestoreService, sweep scheduler
  utilities/    Utilities task runner and MCP utility operations
  web/          WebServer, browse services, dashboard builders, routes
```

## What's Implemented vs Pending

**Implemented:**
- Interactive shell (JLine3), prompt with dry-run/volume indicators
- `mount`, `unmount`, `volumes`, `sync`/`sync all`/`sync queue`, `actresses`, `favorites`, `sync covers`, `prune-covers`, `help`, `shutdown`
- Full and partition-scoped sync via SMB with progress display
- Actress resolution through aliases during sync
- Persistence layer: all repositories, title_locations for multi-location dedup support

**Still pending (shell CLI):**
- `list`, `partitions` commands
- `run <action>` organization workflows (organize pipeline runs via MCP tools, not shell commands)

## Known Deviations from Spec

- **SMB**: Uses smbj library (Java), not OS-level `mount_smbfs`. There are no local mount points.
- **Credentials**: Currently in `organizer-config.yaml` under `servers:`. Intended to move to macOS Keychain.
