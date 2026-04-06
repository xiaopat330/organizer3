# Organizer3 - Implementation Notes

## Technology Stack

| Concern | Library / Tool |
|---|---|
| Language | Java 21 |
| Build | Gradle |
| Interactive shell / REPL | JLine3 (direct, not via Spring Shell) |
| SQL mapping | JDBI3 |
| Database | SQLite via xerial/sqlite-jdbc |
| YAML config | Jackson + jackson-dataformat-yaml |
| Logging | SLF4J + Logback |
| SMB connectivity | smbj (SMB2/3 Java library) |
| Domain models | Lombok (`@Value @Builder`) on `Actress`; Java records elsewhere |
| Test mocking | Mockito |

No Spring. Dependencies are wired manually in `Application.java` (the composition root).

---

## Modern Java Usage

- **Records** for immutable domain objects: `Title`, `Video`, `Volume`, `ActressAlias`
- **Lombok `@Value @Builder`** on `Actress` (predates the records adoption; may be migrated later)
- **Sealed classes** for use cases requiring exhaustive dispatch (not yet in place; intended for `VolumeStructure`)
- **`java.nio.file.Path` and `Files`** throughout — never `java.io.File`
- **`switch` expressions** used in tier/partition mapping (`toActressTier`, `SyncCommand` operation dispatch)

---

## SMB / Filesystem Access

The app connects to all volumes via SMB2/3 using the **smbj** library. Shares are accessed entirely through smbj — there are no OS-level mounts (`mount_smbfs`). The filesystem is never exposed as a local path.

### VolumeFileSystem Abstraction

All filesystem reads go through a `VolumeFileSystem` interface. This decouples sync logic from the transport layer and will make dry-run trivial to implement for file operations:

```java
interface VolumeFileSystem {
    List<Path> listDirectory(Path path);
    boolean exists(Path path);
    boolean isDirectory(Path path);
    // etc.
}
```

Implementations:
- `SmbFileSystem` — delegates to smbj's `DiskShare` API
- `DryRunFileSystem` — intended for file operation simulation (not yet wired)

### VolumeConnection

`VolumeConnection` wraps the lifecycle of an smbj session:
- Holds the `SMBClient`, `Connection`, `Session`, and `DiskShare`
- Exposes a `VolumeFileSystem` backed by the open share
- `close()` tears down the full session stack

`SmbjConnector` opens connections and reports progress phases (connecting → authenticating → opening share) via a `MountProgressListener` callback so the shell can display a spinner.

### Credentials

Credentials are currently stored in `organizer-config.yaml` under a `servers:` block. This is a known deviation from the intended design (Keychain storage). Plan: move to macOS Keychain via `security find-internet-password` at mount time.

---

## Configuration Files

YAML files are used for structural configuration that humans write and rarely change. Mutable data discovered by the tool lives in the database.

### Active config files (in `src/main/resources/`)

| File | Purpose |
|---|---|
| `organizer-config.yaml` | Servers (host, username, password), volume definitions, structure definitions, sync command bindings |
| `aliases.yaml` | Seed data for actress alias mappings — imported into DB on first run |

### Config Shape

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
    server: pandora        # references a server entry by id

structures:
  - id: conventional
    unstructuredPartitions:
      - { id: queue, path: queue }
      ...
    structuredPartition:
      path: stars
      partitions:
        - { id: library, path: library }
        ...

syncConfig:
  - structureType: conventional
    commands:
      - term: sync-queue
        operation: partition
        partitions: [queue]
      - term: sync-all
        operation: full
```

`AppConfig` is a process-level singleton initialized at startup. `AppConfig.initializeForTest(...)` is available for unit tests.

### Legacy reference files (in `legacy/`)

The original organizer2 project config files are in `legacy/` and are **not loaded by the application**. They exist only as reference when porting configuration values.

---

## Database

SQLite database, single file at `~/.organizer3/organizer.db`. Single user — no concurrent access concerns.

### Schema (current: version 4)

```sql
volumes         (id TEXT PK, structure_type TEXT, last_synced_at TEXT)
actresses       (id INTEGER PK AUTOINCREMENT, canonical_name TEXT UNIQUE,
                 tier TEXT, first_seen_at TEXT, favorite INTEGER DEFAULT 0)
actress_aliases (actress_id INTEGER → actresses.id, alias_name TEXT,
                 PRIMARY KEY (actress_id, alias_name))
titles          (id INTEGER PK AUTOINCREMENT, code TEXT, base_code TEXT,
                 label TEXT, seq_num INTEGER,
                 volume_id TEXT → volumes.id, partition_id TEXT,
                 actress_id INTEGER → actresses.id (nullable),
                 path TEXT, last_seen_at TEXT)
videos          (id INTEGER PK AUTOINCREMENT, title_id INTEGER → titles.id,
                 filename TEXT, path TEXT, last_seen_at TEXT)
operations      (id INTEGER PK AUTOINCREMENT, timestamp TEXT, type TEXT,
                 source_path TEXT, dest_path TEXT, was_armed INTEGER)
```

Indexes: `actress_aliases(alias_name)`, `titles(volume_id)`, `titles(actress_id)`, `titles(code)`, `titles(label)`, `videos(title_id)`

`actress_id` on titles is nullable — titles in unstructured partitions have no actress until organized into a starred partition.

### Schema Migrations

`SchemaInitializer` uses `PRAGMA user_version` as a version counter. Migrations are applied in order on startup, each incrementing the version. Current migrations:

| Version | Change |
|---------|--------|
| 0 → 1 | Initial schema |
| 1 → 2 | Drop `mount_path` from volumes (smbj replaced OS mounts) |
| 2 → 3 | Add `label` and `seq_num` columns to titles |
| 3 → 4 | Add `favorite` column to actresses |

### Repository Pattern

All DB access goes through repository interfaces. Domain code never calls JDBI directly.

| Interface | Implementation |
|---|---|
| `ActressRepository` | `JdbiActressRepository` |
| `TitleRepository` | `JdbiTitleRepository` |
| `VideoRepository` | `JdbiVideoRepository` |
| `VolumeRepository` | `JdbiVolumeRepository` |

Repositories are tested with real in-memory SQLite DBs (not mocks). Each test gets a fresh schema via `SchemaInitializer`.

### Data Ownership

- **YAML owns**: volume definitions, server config, structure definitions, sync command bindings
- **DB owns**: actress records, alias mappings, title records, video records, operation history
- `aliases.yaml` is a **seed file** only — imported into the DB on first run, then the DB is authoritative

---

## Volume Structure Types

Four structure types are defined in config:

| Type | Stars layout | Sync |
|---|---|---|
| `conventional` | Tiered sub-folders under `stars/` | `sync-all` (full), `sync-queue` (partition) |
| `queue` | No stars | `sync` / `sync-all` (full) |
| `stars-flat` | Actress folders directly under `stars/`, no tier sub-folders | `sync-all` (full) |
| `collections` | No stars, all unstructured partitions | Not yet implemented |

For `stars-flat`, all actresses are stored with tier `LIBRARY` in the DB regardless of title count, because there is no tier information encoded in the folder structure.

### Partition ID vs Path

`PartitionDef` has two fields:
- `id` — logical name used in DB (e.g., `"queue"`)
- `path` — actual folder name on disk (e.g., `"fresh"` for queue volumes)

These diverge in the queue volume type (`id=queue`, `path=fresh`). They are the same in conventional volumes. Always use `id` when writing to the DB; resolve to `path` when accessing the filesystem.

For titles inside the structured partition, `partition_id` in the DB is `"stars/<tier-id>"` (e.g., `"stars/popular"`).

---

## Data Layer Architecture

```
SMB Filesystem  <--sync-->  SQLite DB  <--mount-->  VolumeIndex  <--commands-->  Results
```

- `sync` walks the SMB filesystem, reconciles against DB, updates records
- `mount` loads from DB into the in-memory `VolumeIndex`. Assumes DB is current.
- Commands that need per-volume data read from `VolumeIndex`; cross-volume queries (actresses, favorites) go directly to the DB repositories

---

## Sync Design

### Sync Commands are Config-Driven

`SyncCommand` instances are registered dynamically at startup from `syncConfig` in the YAML. One instance is registered per unique term. Terms shared across structure types produce a single command that validates against all applicable types.

### Sync Scope and DB Clearing

- **`FullSyncOperation`**: deletes all `videos` then all `titles` for the volume (FK order), then re-scans everything
- **`PartitionSyncOperation`**: deletes only the named partition's videos (via join) and titles, then re-scans that partition

Sync always reads the real filesystem regardless of dry-run mode — it is read-only from the filesystem's perspective. All writes go to the DB.

### What Sync Produces

For each title folder found:
- A `Title` record: `code`, `baseCode`, `label`, `seqNum` parsed from the folder name; `volume_id`, `partition_id`, `actress_id` (null for unstructured), `path`, `last_seen_at = today`
- `Video` records for each media file inside the title folder (checked directly and inside an optional `video/` subdirectory)

For each actress folder found in the `stars/` tree:
- Resolved via `ActressRepository.resolveByName()` (checks canonical name and aliases)
- Creates a new `Actress` record if not found, using the folder name as canonical name and the tier from the sub-partition mapping

After all scanning, `last_synced_at` is stamped on the volume record and the `VolumeIndex` is rebuilt from DB.

### Title Code Parsing

`TitleCodeParser` extracts a JAV code from a folder name using pattern `[A-Za-z][A-Za-z0-9]{0,9}-\d{2,6}`:
- `code` = normalized label (uppercased) + `-` + original digits + any `_SUFFIX` immediately following (e.g., `_U`, `_4K`)
- `baseCode` = `LABEL-NNNNN` (5-digit zero-padded number, no suffix) — used for cross-volume matching
- `label` = the label portion only (e.g., `"ABP"`)
- `seqNum` = parsed as integer if digits are present
- Falls back to the raw folder name for `code`/`baseCode` if no pattern matches

### Video File Discovery

Within a title folder, video files are collected from:
1. Directly inside the title folder
2. Inside an optional `video/` subdirectory

Recognized extensions: `mkv mp4 avi mov wmv mpg mpeg m4v m2ts ts rmvb divx asf wma wm`

---

## Mount Lifecycle

`mount <id>`:
1. Look up volume config and server config by id
2. If already connected to this volume, acknowledge and return
3. If a different volume is connected, close its connection first
4. Open smbj connection with spinner feedback (three phases: connect, authenticate, share open)
5. On failure: print error, return without updating session state
6. Set connection and volume on `SessionContext`
7. Load `VolumeIndex` from DB — if empty (cold volume), print a prompt to run sync

`unmount`:
- Closes the SMB connection
- Clears `activeConnection`, `mountedVolume`, and `index` from `SessionContext`

---

## Command Infrastructure

### Command Interface

```java
public interface Command {
    String name();
    String description();
    void execute(String[] args, SessionContext ctx, CommandIO io);
    default List<String> aliases() { return List.of(); }
}
```

`args[0]` is always the command name. The shell splits on whitespace before dispatching.

### CommandIO

Two output paths:
- **`println`** — scrolling message output (accumulates above the prompt)
- **`status` / `startSpinner` / `startProgress`** — persistent status line at the bottom, overwritten in place

Implementations:
- `JLineCommandIO` — live terminal with animated spinner and progress via JLine3's `Status` facility
- `PlainCommandIO` — writes to a `PrintWriter`; spinner/progress are no-ops. Used in tests and non-TTY contexts.

### Session State

`SessionContext` holds all mutable per-session state:
- `mountedVolume` — currently active `VolumeConfig`
- `activeConnection` — open `VolumeConnection`
- `index` — in-memory `VolumeIndex` for the active volume
- `dryRun` — defaults to `true`; controls whether file operations execute
- `running` — `false` triggers shell exit

`SessionContext` is never a singleton — always injected so tests can construct isolated instances.

---

## Interactive Shell (JLine3)

JLine3 is used directly — not via Spring Shell:

- Fish-style autosuggestions and history search available via JLine3 but completers not yet wired
- Ctrl+C (`UserInterruptException`) continues the read loop without exiting
- Ctrl+D (`EndOfFileException`) exits gracefully
- History saved to `.organizer_history` in the working directory
- On a real TTY: `JLineCommandIO` with spinner/progress
- On a dumb terminal or non-TTY: `PlainCommandIO`

---

## Dry-Run / Armed Mode

`SessionContext.isDryRun()` defaults to `true`. The prompt displays `[*DRYRUN*]` when active.

`arm` and `test` toggle commands are not yet implemented — the mode is set only at startup. The `VolumeFileSystem` abstraction is designed to support a `DryRunFileSystem` implementation for no-op file operations, but it is not yet wired.

---

## Logging

- SLF4J + Logback
- Session-based log files
- Log rotation (configurable max files, default 3)
- All file operations will be logged regardless of armed mode (when file ops are implemented)
- Console echo of important events via `CommandIO`
