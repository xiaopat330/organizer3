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

| File | Purpose |
|---|---|
| `organizer-config.yaml` | Volume definitions: id, SMB path, local mount point, structure type, credentials key |
| `operation-config.yaml` | Filename normalization rules, media extensions, tier thresholds |
| `aliases.yaml` | Initial seed data for actress alias mappings (imported into DB on first run) |
| `nas.yaml` | Initial seed data for known actress database (imported into DB on first run) |

Credentials are referenced by key in `organizer-config.yaml` but resolved from the macOS Keychain at runtime — never stored in YAML.

### Volume Config Shape

```yaml
volumes:
  - id: a
    smbPath: //nas-server/ShareA
    mountPoint: /Volumes/ShareA
    structureType: conventional
    credentialsKey: nas-main
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

## Data Layer Architecture

The DB is a **persistent cache of the filesystem**. The in-memory index (built at mount time) is a **session-level cache of the DB**.

```
Filesystem  <--sync-->  Database  <--mount-->  In-memory index  <--commands-->  Results
```

- `mount` loads from DB into memory. Assumes DB is current.
- `sync` walks the filesystem, reconciles against DB, updates records.
- All operational commands work against the in-memory index.

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
