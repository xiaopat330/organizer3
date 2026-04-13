# Proposal: User Data Backup and Restore

Design spec for a CLI command pair that exports user-altered database fields to a JSON file and restores them after a database drop and reseed.

---

## 1. Motivation

Most data in the database is recoverable: titles and locations are restored by syncing volumes, actress profiles by `load actresses`, labels by reseeding. The one category that cannot be recovered is user-generated state — favorites, bookmarks, grades, reject flags, visit counts, and watch history. These fields are small in volume but high in value. A database corruption or intentional drop currently destroys them permanently.

The fix is a simple export/import pair: `backup` writes a snapshot of all user-altered fields to a JSON file; `restore` reads that file and overlays it onto the current database after a reseed.

---

## 2. What is backed up

User-altered fields are defined as anything set by user action or user behavior — not by sync, DMM scraping, or YAML loading.

### 2.1 Actress fields

Keyed by `canonical_name` (unique, stable — does not change across reseeds).

| Field | Type | Notes |
|---|---|---|
| `favorite` | boolean | |
| `bookmark` | boolean | |
| `bookmarked_at` | timestamp | nullable |
| `grade` | string | SSS/SS/S/A/B/C |
| `rejected` | boolean | |
| `visit_count` | integer | |
| `last_visited_at` | timestamp | nullable |

### 2.2 Title fields

Keyed by `code` (product code — unique, stable by definition).

| Field | Type | Notes |
|---|---|---|
| `favorite` | boolean | |
| `bookmark` | boolean | |
| `bookmarked_at` | timestamp | nullable |
| `grade` | string | SSS/SS/S/A/B/C |
| `rejected` | boolean | |
| `visit_count` | integer | |
| `last_visited_at` | timestamp | nullable |
| `notes` | string | nullable; free-form user text |

### 2.3 Watch history

All rows from the `watch_history` table: `title_code` + `watched_at`.

### 2.4 Explicitly excluded

These are excluded because they are restorable through existing pipelines:

| Data | How it's restored |
|---|---|
| Actress profile fields (biography, measurements, etc.) | `load actress` / `load actresses` |
| Title metadata (title_original, title_english, release_date) | DMM scrape |
| title_tags | DMM scrape |
| actress_aliases | YAML load |
| title_locations | Volume sync |
| labels, tags, label_tags | Reseed |
| `first_seen_at` on actresses | Set by sync, not a user action |

---

## 3. Backup file format

JSON, written by Jackson (already in the project). Stored at a configurable path, default `data/user-data-backup.json`.

```json
{
  "version": 1,
  "exportedAt": "2026-04-12T10:30:00",
  "actresses": [
    {
      "canonicalName": "Yua Mikami",
      "favorite": true,
      "bookmark": false,
      "bookmarkedAt": null,
      "grade": "SSS",
      "rejected": false,
      "visitCount": 42,
      "lastVisitedAt": "2026-04-10T20:15:00"
    }
  ],
  "titles": [
    {
      "code": "ABP-123",
      "favorite": true,
      "bookmark": false,
      "bookmarkedAt": null,
      "grade": "S",
      "rejected": false,
      "visitCount": 3,
      "lastVisitedAt": "2026-03-15T22:00:00",
      "notes": "Great solo."
    }
  ],
  "watchHistory": [
    { "titleCode": "ABP-123", "watchedAt": "2026-03-15T22:00:00" }
  ]
}
```

**`version`** — integer. Allows future format changes to be detected and handled gracefully. Current version is `1`.

**Nullability** — fields that are null are written as JSON `null`, not omitted. This makes the file unambiguous and easier to diff.

**Omitting default-only entries** — actress and title entries where every field is at its default value (`favorite: false, bookmark: false, grade: null, rejected: false, visitCount: 0, lastVisitedAt: null, notes: null`) are omitted from the export. This keeps the file compact — the vast majority of titles in a large library have never been touched by the user.

---

## 4. Configuration

New `backup:` block in `organizer-config.yaml`:

```yaml
backup:
  path: data/user-data-backup.json
```

If absent, the default path `data/user-data-backup.json` is used. The path is resolved relative to `dataDir`.

---

## 5. Commands

### 5.1 `backup`

Exports all user-altered data to the configured backup file. Overwrites any existing file at that path.

**Output:**
```
Exported 1,204 actress records, 18,432 title records, 847 watch history entries.
Backup written to: data/user-data-backup.json
```

Does not require a volume to be mounted. Reads only from the database.

**In dry-run mode:** Reports what would be written (the counts) but does not create or overwrite the file.

### 5.2 `restore`

Reads the backup file and overlays user-altered fields onto the current database.

```
restore           -- reads from the configured backup path
restore <path>    -- reads from a specific file path
```

**Restore logic (per entry):**

- **Actress entry:** Look up the actress by `canonicalName`. If found, apply all non-null user fields from the backup entry. If not found (not yet synced back in), skip and count as skipped.
- **Title entry:** Look up the title by `code`. If found, apply all non-null user fields. If not found, skip and count as skipped.
- **Watch history:** Insert all entries via `INSERT OR IGNORE` (idempotent — duplicate `title_code + watched_at` pairs are silently skipped).

Restore is an overlay, not a replace. It does not zero out fields on rows that have no corresponding backup entry.

**Output:**
```
Restored 1,198 actress records (6 skipped — not found).
Restored 18,401 title records (31 skipped — not found).
Inserted 847 watch history entries.
```

Skipped entries are expected on a fresh reseed before all volumes have been synced — they can be resolved by syncing volumes and running `restore` again.

**In dry-run mode:** Reads and parses the file, counts what would be applied vs. skipped, prints the summary, and exits without writing anything to the database.

**Version mismatch:** If the backup file's `version` field is higher than the current parser supports, abort with a clear error message rather than silently misreading the format.

---

## 6. Implementation

### 6.1 Model

New record in `com.organizer3.backup`:

```java
public record UserDataBackup(
    int version,
    LocalDateTime exportedAt,
    List<ActressBackupEntry> actresses,
    List<TitleBackupEntry> titles,
    List<WatchHistoryEntry> watchHistory
) {}

public record ActressBackupEntry(
    String canonicalName,
    boolean favorite,
    boolean bookmark,
    LocalDateTime bookmarkedAt,
    String grade,
    boolean rejected,
    int visitCount,
    LocalDateTime lastVisitedAt
) {}

public record TitleBackupEntry(
    String code,
    boolean favorite,
    boolean bookmark,
    LocalDateTime bookmarkedAt,
    String grade,
    boolean rejected,
    int visitCount,
    LocalDateTime lastVisitedAt,
    String notes
) {}

public record WatchHistoryEntry(
    String titleCode,
    LocalDateTime watchedAt
) {}
```

### 6.2 `UserDataBackupService`

Single service class in `com.organizer3.backup`:

```java
UserDataBackup export()
void write(UserDataBackup backup, Path path)
UserDataBackup read(Path path)
RestoreResult restore(UserDataBackup backup)
```

`RestoreResult` carries counts: `actressesRestored`, `actressesSkipped`, `titlesRestored`, `titlesSkipped`, `watchHistoryInserted`.

The export query pulls all actresses and titles with at least one non-default user field:

```sql
-- Actress export
SELECT canonical_name, favorite, bookmark, bookmarked_at, grade, rejected,
       visit_count, last_visited_at
FROM actresses
WHERE favorite = 1 OR bookmark = 1 OR grade IS NOT NULL
   OR rejected = 1 OR visit_count > 0

-- Title export
SELECT code, favorite, bookmark, bookmarked_at, grade, rejected,
       visit_count, last_visited_at, notes
FROM titles
WHERE favorite = 1 OR bookmark = 1 OR grade IS NOT NULL
   OR rejected = 1 OR visit_count > 0 OR notes IS NOT NULL
```

The restore applies fields individually with targeted UPDATE statements to avoid clobbering unrelated fields.

### 6.3 Commands

`BackupCommand` and `RestoreCommand` in `com.organizer3.command`. Neither requires a mounted volume. Both are wired in `Application.java`.

---

## 7. Usage workflow after a database drop

1. Drop the database (or it is lost to corruption)
2. Restart the app — `SchemaInitializer` creates a fresh schema
3. `load actresses` — reseeds actress profiles from YAML files
4. Mount volumes and run `sync all` — restores all titles, locations
5. `restore` — overlays user-altered fields from the backup file

Steps 3 and 4 can run in either order. `restore` should be the last step so all entities it references are already present.

---

## 8. Out of scope

- **Automatic scheduled backups** — backup is on-demand for v1. A cron-triggered `backup` command could be added later via the scheduler infrastructure.
- **Versioned backup history** — only one backup file is kept. The user manages copies manually if they want a history.
- **AV Stars data** — `av_actresses` curation fields (favorite, grade, notes) are not included. Deferred until AV Stars is implemented; a v2 of the backup format can add them.
- **Web UI export/import** — a browser "Download backup" / "Upload backup" button is a natural addition once the web UI matures, but is out of scope here.
