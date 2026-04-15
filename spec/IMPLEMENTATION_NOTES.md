# Organizer3 — Implementation Notes

## Technology Stack

| Concern | Library / Tool |
|---|---|
| Language | Java 21 |
| Build | Gradle |
| Interactive shell / REPL | JLine3 (direct, not via Spring Shell) |
| SQL mapping | JDBI3 |
| Database | SQLite via xerial/sqlite-jdbc |
| YAML config | Jackson + jackson-dataformat-yaml |
| JSON serialization | Jackson (backup files, API responses) |
| Logging | SLF4J + Logback |
| SMB connectivity | smbj (SMB2/3 Java library) |
| Domain models | Lombok `@Value @Builder` on `Actress` + `AvActress`; Java records elsewhere |
| Test mocking | Mockito |
| Embedded web server | Javalin 6 |
| Web terminal | WebSocket via Javalin |

No Spring. All dependencies are wired manually in `Application.java` (the composition root).

---

## Package Structure

```
com.organizer3
  Application.java          — composition root; wires all dependencies; starts shell + web server
  backup/                   — UserDataBackup, UserDataBackupService, entry records, RestoreResult
  command/                  — JAV shell command implementations (one class per command)
  config/                   — AppConfig singleton, YAML model records
  covers/                   — CoverPath utility (local cover image path resolution)
  db/                       — SchemaInitializer, SchemaUpgrader
  filesystem/               — VolumeFileSystem interface + SmbFileSystem
  model/                    — JAV domain records: Title, TitleLocation, Actress, ActressAlias, Video, Volume
  repository/               — JAV repository interfaces + jdbi/ implementations
  shell/                    — SessionContext, OrganizerShell, PromptBuilder, CommandIO
  smb/                      — SmbConnector, SmbjConnector, VolumeConnection
  sync/                     — JAV sync operations, VolumeIndex, IndexLoader, TitleCodeParser
  web/                      — WebServer, SearchService, AvBrowseService, ActressBrowseService, etc.

  avstars/
    command/                — AV shell command implementations
    iafd/                   — HttpIafdClient, IafdSearchParser, IafdProfileParser, IafdResolvedProfile
    model/                  — AvActress, AvVideo (Lombok @Value @Builder)
    repository/             — AvActressRepository, AvVideoRepository, AvScreenshotRepository,
                              AvTagDefinitionRepository, AvVideoTagRepository
    repository/jdbi/        — JDBI implementations of AV repositories
    service/                — AvBrowseService, AvScreenshotService, AvFilenameParser,
                              AvStarsSyncOperation, AvTagYamlLoader
```

---

## SMB / Filesystem Access

The app connects to all volumes via SMB2/3 using **smbj**. There are no OS-level mounts. The filesystem is accessed entirely through smbj's Java API.

### VolumeFileSystem abstraction

All filesystem reads go through the `VolumeFileSystem` interface. This decouples sync logic from transport:

```java
interface VolumeFileSystem {
    List<Path> listDirectory(Path path);
    boolean exists(Path path);
    boolean isDirectory(Path path);
    InputStream openFile(Path path);  // used by sync covers
    // etc.
}
```

`SmbFileSystem` delegates to smbj's `DiskShare` API. `DryRunFileSystem` (file operation simulation) is not yet wired.

### VolumeConnection

Wraps smbj session lifecycle. `SmbjConnector` opens connections and reports progress phases (connecting → authenticating → opening share) via a `MountProgressListener` callback so the shell can display a spinner.

### Credentials

Stored in `organizer-config.yaml` under `servers:`. Intended to move to macOS Keychain; not yet done.

---

## Database

SQLite, single file at `~/.organizer3/organizer.db`. Single-user, no concurrent access concerns.

### Schema Management

Two-tier approach:
- `SchemaInitializer` — creates all tables and indexes using `CREATE TABLE IF NOT EXISTS`. Runs on every startup. Safe to re-run. Used in tests to set up in-memory DBs.
- `SchemaUpgrader` — incremental migrations using `applyVN()` methods (idempotent `ALTER TABLE` + backfill). Runs after `SchemaInitializer`. Each migration is gated by checking `PRAGMA user_version`.

**Do not drop-and-recreate during development** — use `SchemaUpgrader` for schema changes. The `user_version` pragma tracks the current schema version.

### Full Schema

See `FUNCTIONAL_SPEC.md §7` for the schema overview. Full DDL is in `SchemaInitializer.java`.

Key structural points:
- A title is unique by `code`. Location data lives in `title_locations` (one title can have multiple physical locations = duplicate detection).
- `title_effective_tags` is a denormalized union of `title_tags` (direct) and `label_tags` (inherited from the label). Maintained by `TitleTagService`.
- `av_actresses` identity is `(volume_id, folder_name)`. Stage name defaults to folder name when not set.
- `av_videos` identity is `(av_actress_id, relative_path)`.

### Repository Pattern

All DB access goes through repository interfaces. Domain code never calls JDBI directly. Tests use real in-memory SQLite via `SchemaInitializer` — not mocks.

| Interface | Implementation |
|---|---|
| `ActressRepository` | `JdbiActressRepository` |
| `TitleRepository` | `JdbiTitleRepository` |
| `TitleLocationRepository` | `JdbiTitleLocationRepository` |
| `VideoRepository` | `JdbiVideoRepository` |
| `VolumeRepository` | `JdbiVolumeRepository` |
| `LabelRepository` | `JdbiLabelRepository` |
| `WatchHistoryRepository` | `JdbiWatchHistoryRepository` |
| `AvActressRepository` | `JdbiAvActressRepository` |
| `AvVideoRepository` | `JdbiAvVideoRepository` |
| `AvScreenshotRepository` | `JdbiAvScreenshotRepository` |
| `AvTagDefinitionRepository` | `JdbiAvTagDefinitionRepository` |
| `AvVideoTagRepository` | `JdbiAvVideoTagRepository` |

---

## Configuration

`organizer-config.yaml` (in `src/main/resources/`) is the bootstrap config. It is loaded once at startup into `AppConfig` (process-level singleton). `AppConfig.initializeForTest(...)` is available for tests.

### Config shape

```yaml
servers:
  - id: pandora
    username: patri
    password: "..."
    domain: pandora

volumes:
  - id: a
    smbPath: //pandora/jav_A
    structureType: conventional
    server: pandora

structures:
  - id: conventional
    unstructuredPartitions:
      - { id: queue, path: queue }
    structuredPartition:
      path: stars
      partitions:
        - { id: library, path: library }
        - ...
  - id: avstars
    unstructuredPartitions: []
    ignoredSubfolders: [trash, .Trashes, incomplete]

syncConfig:
  - structureType: conventional
    commands:
      - term: sync queue
        operation: partition
        partitions: [queue]
      - term: sync all
        operation: full
  - structureType: avstars
    commands:
      - term: sync all
        operation: full

backup:
  autoBackupIntervalMinutes: 10080   # weekly
  snapshotCount: 10

dataDir: data    # root for covers/, thumbnails/, backups/, av_headshots/, av_screenshots/
```

### Seed files

| File | Purpose |
|---|---|
| `src/main/resources/aliases.yaml` | Actress alias seed (imported once on fresh DB) |
| `src/main/resources/labels.csv` | Label metadata seed (auto-imported on empty table) |

---

## CommandIO

Two output channels:
- **`println`** — scrolling message output (accumulates above the prompt)
- **`status(String)`** — persistent status line at the bottom, overwritten in place
- **`startSpinner(String)`** — animated spinner on the status line (returns `AutoCloseable`)
- **`startProgress(String, int)`** — progress counter on the status line (returns `Progress` with `advance()` and `setLabel()`)

Implementations:
- `JLineCommandIO` — live terminal via JLine3's `Status` facility
- `PlainCommandIO` — writes to `PrintWriter`; spinner/progress are no-ops. Used in tests and non-TTY.

---

## Sync Design

### Config-Driven Sync Commands

`SyncCommand` instances are registered dynamically from `syncConfig` in YAML. Adding a new sync term requires only a YAML change, no Java code.

### JAV FullSyncOperation

1. Delete all `videos` and `title_locations` for the volume
2. Walk filesystem partitions; for each title folder: upsert `Title`, insert `TitleLocation`, insert `Video` records
3. Delete orphaned titles (zero remaining locations)
4. Stamp `last_synced_at` on the volume; rebuild `VolumeIndex` from DB

### AV AvStarsSyncOperation

1. For each `avstars` volume: walk each top-level actress folder recursively
2. Upsert `AvActress` record; upsert `AvVideo` records for each video file found
3. Delete orphaned videos (last_seen_at < sync start)
4. Update `video_count` and `total_size_bytes` on each actress
5. Update `last_scanned_at` on each actress

---

## Backup System

`UserDataBackupService` in `com.organizer3.backup`. Exports user-altered fields from both JAV and AV tables to a versioned JSON file. Restores by overlay (not replace).

Current backup version: **2** (added `avActresses` and `avVideos` lists in v2; v1 files have null for these).

Stable backup keys:
- JAV actress → `canonicalName`
- JAV title → `code`
- AV actress → `(volumeId, folderName)`
- AV video → `(volumeId, folderName, relativePath)`

`BackupScheduler` runs auto-backup on a background thread at a configurable interval. Snapshot files use the pattern `user-data-backup-<timestamp>.json`. Oldest snapshots are pruned beyond the configured `snapshotCount`.

---

## Web Server

Javalin 6, port 8080. Started before the shell loop; stopped after.

### Route categories

- **Static assets** — JS modules, CSS, fonts served from `src/main/resources/public/`
- **JAV browse API** — `/api/actresses/*`, `/api/titles/*`, `/api/labels/*`, `/api/search`, `/api/favorites/*`, `/api/bookmarks/*`, `/api/grades/*`, `/api/notes/*`, `/api/watch/*`
- **Cover / thumbnail serving** — `/covers/*`, `/thumbnails/*`, `/api/thumbnail/*`
- **Video streaming** — `/api/video/*` (range-request aware; streams from SMB)
- **AV browse API** — `/api/av/*` (actresses, videos, tags, curation, watch state)
- **AV media serving** — `/api/av/headshots/*`, `/api/av/screenshots/*`
- **AV video streaming** — `/api/av/stream/*`
- **Web terminal** — WebSocket at `/terminal/ws`

`WebServer.registerAvRoutes(...)` is called with all AV-specific dependencies and wires the AV sub-routes.

### SearchService

`SearchService.search(query, startsWith, includeAv)` performs federated search across JAV actresses, titles, labels, companies, and optionally AV actresses. Returns a grouped result map ready for JSON serialization.

Headshot URL construction: `/api/av/headshots/{filename}` — the endpoint serves by **filename** (not ID). `toAvActressMap()` uses `Path.of(r.headshotPath()).getFileName()` to derive the filename.

---

## Interactive Shell (JLine3)

- Fish-style autosuggestions and history search available via JLine3
- Completers not yet wired (tab completion not implemented)
- Ctrl+C continues the read loop; Ctrl+D exits gracefully
- History saved to `.organizer_history` in working directory
- On real TTY: `JLineCommandIO` with spinner/progress
- On dumb terminal / non-TTY: `PlainCommandIO`

---

## Known Deviations from Original Spec

- SMB via smbj (Java) — no OS-level `mount_smbfs`
- Credentials in YAML, not macOS Keychain
- `arm`/`test` toggle commands not yet implemented
- Collections volume sync not yet implemented
- File operations (move/rename) not yet implemented
- Tab completion not yet wired
