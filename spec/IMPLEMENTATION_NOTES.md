# Organizer3 - Implementation Notes

## Technology Stack

| Concern | Library / Tool |
|---|---|
| Language | Java 21 |
| Build | Gradle |
| Interactive shell / REPL | JLine3 (direct, not via Spring Shell) |
| Command parsing / structure | Picocli |
| Database | SQLite via xerial/sqlite-jdbc |
| SQL mapping | JDBI3 |
| YAML config | Jackson + jackson-dataformat-yaml |
| Logging | SLF4J + Logback |

No Spring. Dependencies are wired manually via a small `Application` or `Context` class.

---

## Modern Java Usage

- **Records** for immutable domain objects: `Title`, `Actress`, `Video`, `Volume`, etc.
- **Sealed classes** for the volume structure type hierarchy:
  ```java
  sealed interface VolumeStructure permits ConventionalStructure, QueueStructure, CollectionsStructure {}
  ```
  Enables exhaustive `switch` expressions when dispatching structure-specific behavior.
- **`java.nio.file.Path` and `Files`** throughout — never `java.io.File`.

---

## SMB / Filesystem Access

The app runs on macOS. All volumes are accessed via SMB (Windows file servers and NAS devices).

Rather than using a Java SMB library, the app mounts shares at the OS level using `mount_smbfs` via `ProcessBuilder`, after which the share is accessible as a standard filesystem path (e.g., `/Volumes/ShareName`) using standard Java NIO. No third-party SMB library is needed.

### Credential Storage

Credentials are stored in the macOS Keychain. The app retrieves them at mount time using the `security find-internet-password` command via `ProcessBuilder`. Credentials are never stored in config files or on disk by the application.

### VolumeFileSystem Abstraction

All filesystem operations go through a `VolumeFileSystem` interface rather than calling `java.nio.file.Files` directly. This keeps SMB/local concerns out of business logic, makes dry-run trivial (a no-op implementation), and preserves flexibility.

```java
interface VolumeFileSystem {
    List<Path> listDirectory(String path);
    void move(String source, String destination);
    void createDirectory(String path);
    boolean exists(String path);
    // etc.
}
```

Implementations:
- `LocalFileSystem` — delegates to `java.nio.file.Files` (used for all mounts, since OS handles SMB)
- `DryRunFileSystem` — logs operations, executes nothing (used in test/dry-run mode)

---

## Configuration Files

YAML files are used for structural configuration that humans write and rarely change. Mutable data discovered by the tool lives in the database.

### Active config files (in `src/main/resources/`)

| File | Purpose |
|---|---|
| `organizer-config.yaml` | Volume definitions: id, SMB path, local mount point, structure type, credentials key, username |
| `aliases.yaml` | Seed data for actress alias mappings — imported into DB on first run, then DB is authoritative |

Credentials are referenced by key in `organizer-config.yaml` but resolved from the macOS Keychain at runtime — never stored in YAML.

### Legacy reference files (in `legacy/`)

The original organizer2 project used a different set of YAML files. These have been moved to `legacy/` and are **not loaded by the application**. They exist only as reference material when porting configuration values.

| File | Original purpose |
|---|---|
| `legacy/nas.yaml` | Seed data for the known actress database |
| `legacy/operation-config.yaml` | Filename normalization rules, media extensions, tier thresholds |

When porting values from these files into the new config model, move them into the appropriate active config file or database seed and delete from `legacy/` once complete.

### Volume Config Shape

```yaml
volumes:
  - id: a
    smbPath: //pandora/jav_A
    mountPoint: /Volumes/jav_A
    structureType: conventional
    credentialsKey: pandora
    username: patrick
```

---

## Database

SQLite database, single file, embedded in process. Single user — no transaction management needed.

### Schema

```sql
volumes         (id, mount_path, structure_type, last_synced_at)
actresses       (id, canonical_name, tier, first_seen_at)
actress_aliases (actress_id, alias_name)
titles          (id, code, base_code, volume_id, partition, actress_id, path, last_seen_at)
videos          (id, title_id, filename, path, last_seen_at)
operations      (id, timestamp, type, source_path, dest_path, was_armed)
```

`actress_id` on titles is nullable — titles in unstructured partitions may not have an associated actress.

### Repository Pattern

A repository layer sits between domain logic and SQLite. Domain code never calls JDBI directly.

- `ActressRepository`
- `TitleRepository`
- `VideoRepository`
- `VolumeRepository`
- `OperationLogRepository`

### Data Ownership

- **YAML owns**: volume definitions, normalization rules, thresholds — structural config humans edit.
- **DB owns**: actress records, alias mappings, title records, video records, operation history — data the tool discovers and manages.
- `aliases.yaml` and `nas.yaml` are **seed files** only — imported into the DB on first run, then the DB is authoritative.

---

## Volume Structure Types

Each volume has a `structureType` (from `organizer-config.yaml`) that determines its folder layout. Structure definitions live in the `structures` section of config, keyed by `id`.

### Conventional Structure

The most complex structure. Has two kinds of partitions:

**Unstructured partitions** — top-level folders whose immediate children are title folders:
```
<volume root>/
  queue/          archive/    attention/    converted/
  duplicates/     favorites/  recent/       minor/
```

**Structured partition** — the `stars/` tree, organized by actress tier, then actress name:
```
<volume root>/
  stars/
    library/           # tier sub-partition
      Actress Name/    # actress folder — folder name is the actress name
        ABP-123/       # title folder
          video.mkv    # video files directly here ...
          video/       # ... or inside an optional video/ subdirectory
            video.mkv
    minor/             # same pattern for all tier sub-partitions
    popular/
    superstar/
    goddess/
    favorites/         # user-curated — not a title-count tier, defaults to LIBRARY in DB
    archive/           # archived — same note as favorites
```

Tier sub-partitions under `stars/` that map to `Actress.Tier` enum values: `library → LIBRARY`, `minor → MINOR`, `popular → POPULAR`, `superstar → SUPERSTAR`, `goddess → GODDESS`. The special sub-partitions `favorites` and `archive` do not map to a tier — actresses found there are stored with tier `LIBRARY` in the DB.

### Queue Structure

Minimal: a single unstructured partition called `fresh/` on disk (logical id `queue`):
```
<volume root>/
  fresh/
    ABP-123/    # title folder
```

### Collections Structure

All unstructured partitions, no `stars/` tree. Sync not yet implemented.

---

## Partition ID vs Path

`PartitionDef` has two fields:
- `id` — logical name used in config references and stored as `partition_id` in the DB (e.g., `"queue"`)
- `path` — actual folder name on disk relative to the partition root (e.g., `"fresh"` for the queue volume's queue partition)

These diverge in the queue volume type (`id=queue`, `path=fresh`). They happen to be the same in conventional volumes. Always use `id` when writing to the DB; resolve to `path` when accessing the filesystem.

For titles inside the structured partition, `partition_id` in the DB is `"stars/<tier-id>"` (e.g., `"stars/popular"`), not just the tier name.

---

## Data Layer Architecture

The DB is a **persistent cache of the filesystem**. The in-memory index (built at mount time) is a **session-level cache of the DB**.

```
Filesystem  <--sync-->  Database  <--mount-->  In-memory index  <--commands-->  Results
```

- `mount` loads from DB into memory. Assumes DB is current.
- `sync` walks the filesystem, reconciles against DB, updates records.
- All operational commands work against the in-memory index.

---

## Sync Design

### Sync Commands are Config-Driven

Available sync terms and their scope are defined entirely in the `syncConfig` section of `organizer-config.yaml`, not hardcoded. A `SyncCommandDef` binds a user-facing term (e.g., `sync-queue`) to a `SyncOperationType` (`FULL` or `PARTITION`) and an optional list of partition ids to scan. One `SyncCommand` instance is registered per term at startup.

Current binding:
| Structure type  | Term        | Operation | Scope                         |
|-----------------|-------------|-----------|-------------------------------|
| conventional    | `sync-queue`| PARTITION | `queue` partition only        |
| conventional    | `sync-all`  | FULL      | entire volume                 |
| queue           | `sync`      | FULL      | entire volume                 |
| queue           | `sync-all`  | FULL      | same as `sync` (alias)        |
| collections     | *(none)*    | —         | deferred, not yet implemented |

To add a new sync term for a structure type, add an entry under `syncConfig` in the YAML. No Java changes needed.

### Sync Scope and DB Clearing

- **`FullSyncOperation`**: deletes all `videos` then all `titles` for the volume (FK order), then re-scans everything.
- **`PartitionSyncOperation`**: for each named partition, deletes only that partition's videos (via join) and titles, then re-scans that partition. The rest of the volume's DB records are untouched.

Sync always reads the real filesystem regardless of dry-run mode — it is read-only from the filesystem's perspective. All writes go to the DB.

### What Sync Produces

For each title folder found:
- A `Title` record: `code` and `baseCode` parsed from the folder name, `volume_id`, `partition_id`, `actress_id` (null for unstructured), `path`, `last_seen_at = today`
- `Video` records for each media file inside the title folder (checked directly and inside an optional `video/` subdirectory)

For each actress folder found in the `stars/` tree:
- Resolves against DB via `ActressRepository.resolveByName()` (checks canonical name and aliases)
- Creates a new `Actress` record if not found, using the folder name as canonical name and the tier from the sub-partition mapping

After all scanning, `last_synced_at` is stamped on the volume record and the `VolumeIndex` is rebuilt from DB and set on `SessionContext`.

### Title Code Parsing

`TitleCodeParser` extracts a JAV code from a folder name using pattern `[A-Za-z][A-Za-z0-9]{0,9}-\d{2,6}`:
- `code` = normalized label (uppercased) + `-` + original digits + any `_SUFFIX` immediately following (e.g., `_U`, `_4K`)
- `baseCode` = `LABEL-NNNNN` (5-digit zero-padded number, no suffix) — used for cross-volume matching
- Falls back to the raw folder name for both fields if no pattern matches

### Video File Discovery

Within a title folder, video files are collected from two locations:
1. Directly inside the title folder
2. Inside an optional `video/` subdirectory

Recognized video extensions (from `MediaExtensions`): `mkv mp4 avi mov wmv mpg mpeg m4v m2ts ts rmvb divx asf wma wm`

### In-Memory Index

`VolumeIndex` holds titles and actresses for the active volume. `IndexLoader` builds it from DB:
- `titles` = all `Title` records for the volume
- `actresses` = all `Actress` records referenced by those titles (via non-null `actress_id`)

The index is set on `SessionContext` after every `mount` and every `sync`. Commands that need volume data read from the index rather than querying the DB per call. A cold volume (no DB records yet) gets an empty index; `mount` warns the user to run sync.

---

## Mount Lifecycle

`mount <id>`:
1. Look up volume config by id
2. Check if `mountPoint` is already an active OS mount (non-empty directory check is sufficient)
3. If not mounted: retrieve credentials from Keychain, call `mount_smbfs` via `ProcessBuilder`
4. Capture stderr — surface clear error messages for wrong credentials, server unreachable, etc.
5. **Cold DB detection**: if no records exist for this volume, inform the user: *"No index found for volume 'a' — run sync to build it."*
6. If DB has data: load into in-memory index, set as active volume context, update prompt

OS mounts are never unmounted by the app — that is the OS's responsibility. `mount` is idempotent: calling it on an already-mounted volume simply reactivates it as the session context.

Only one volume is active at a time, but multiple volumes may remain OS-mounted from previous `mount` calls.

---

## Command Categories

Commands fall into two categories based on whether they need an active mounted volume:

**Require a mounted volume (filesystem access):**
- `run <action>` — execute organization workflows
- `sync` — refresh DB index from filesystem (current volume only)
- `list` — display inventory of current volume

**Work from DB alone (no mount needed):**
- `volumes` — list all known volumes with last-sync timestamps
- `actress <name>` — actress detail, queries across all volumes in DB
- `actresses` — full actress listing from DB
- Future cross-volume search commands

---

## Dry-Run / Armed Mode

The batch operation builder is a first-class part of the execution model — not a flag passed to file operations.

Every action produces a list of `Operation` objects (move, rename, mkdir). Execution is a separate step:
- In **test mode**: operations are logged and displayed, not executed. `VolumeFileSystem` is backed by `DryRunFileSystem`.
- In **armed mode**: operations are executed via `LocalFileSystem` and written to the operations log in the DB.

---

## Interactive Shell (JLine3)

JLine3 is used directly — not via Spring Shell — to enable a modern interactive experience:

- Fish-style autosuggestions (ghost text from history)
- Syntax/keyword highlighting in the prompt
- Tab completion with dynamic completers
- Ctrl+R reverse history search
- Custom key bindings

### Tab Completion

Completers are built from live data at startup:
- Command names (static)
- Volume IDs (from config)
- Actress names (from DB)
- Action names (per structure type of mounted volume)

---

## Logging

- SLF4J + Logback
- Session-based log files
- Log rotation (configurable max files, default 3)
- All file operations logged regardless of test/armed mode
- Console echo of important events
