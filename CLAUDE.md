# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

Organizer3 is an interactive CLI tool for managing a JAV media library across multiple NAS volumes over SMB. The core shell infrastructure, SMB connectivity, sync pipeline, persistence layer, and initial actress query commands are implemented.

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
  command/      Command implementations (one class per command)
  config/       Config model + loaders (AppConfig singleton, YAML model)
  db/           SchemaInitializer (PRAGMA user_version migrations)
  filesystem/   VolumeFileSystem interface + implementations
  model/        Domain records: Title, Actress, ActressAlias, Video, Volume
  repository/   Repository interfaces + jdbi/ implementations
  shell/        SessionContext, OrganizerShell, PromptBuilder, CommandIO
  smb/          SmbConnector, SmbjConnector, VolumeConnection
  sync/         Sync operations, VolumeIndex, IndexLoader, TitleCodeParser
```

## What's Implemented vs Pending

**Implemented:**
- Interactive shell (JLine3), prompt with dry-run/volume indicators
- `mount`, `unmount`, `volumes`, `sync`/`sync-all`/`sync-queue`, `actresses`, `favorites`, `help`, `shutdown`
- Full and partition-scoped sync via SMB with progress display
- Actress resolution through aliases during sync
- Persistence layer: all repositories, 4-migration schema

**Not yet implemented:**
- `arm` / `test` mode toggle commands (dry-run defaults to true, no toggle command yet)
- `actress <name>` detail command
- `list`, `partitions` commands
- `run <action>` organization workflows
- File operations (move, rename, mkdir) and `DryRunFileSystem`
- Tab completion

## Known Deviations from Spec

- **SMB**: Uses smbj library (Java), not OS-level `mount_smbfs`. There are no local mount points.
- **Credentials**: Currently in `organizer-config.yaml` under `servers:`. Intended to move to macOS Keychain.
