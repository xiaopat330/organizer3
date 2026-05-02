# Trash Mechanism

> **Status: IMPLEMENTED** — all 4 phases shipped (sidecar contract, TrashService, TrashRoutes + UI, sweep scheduler).

A general-purpose, volume-aware trash mechanism for safely removing any file or folder from the library. Applies to any entity with a physical path on a volume: title folders, actress folders, AV video files, cover images, etc.

---

## 1. Motivation

The app needs a way to remove files from the library without permanently deleting them immediately. A permanent `rm` is irreversible and carries real risk in bulk operations. Instead, trashed items are moved to a well-known folder on the same volume, where the user can review and permanently delete them manually using the NAS OS or a file manager at their own pace.

---

## 2. Design Principles

- **Trash is a move, not a delete.** Since moves are intra-volume and atomic, trashing is instantaneous and safe regardless of file size.
- **Per-volume physically, per-server in config.** Each share gets its own `_trash` folder at its root — required by the intra-volume move constraint. The policy is configured once per server and applies uniformly to all volumes on that box.
- **Scheduled deletion is a floor, not a deadline.** A sidecar's `scheduledDeletionAt` guarantees the item will not be deleted *before* that time. Actual deletion runs as a periodic sweep that catches up on every app start — an offline app does not shorten or skip the holding period.
- **Metadata is best-effort.** A JSON sidecar records context for each trashed item but its loss is tolerable — the folder is still in `_trash` and the user can still clean it up manually.

---

## 3. Configuration

Trash is enabled per server in the server block of `organizer-config.yaml`. Omitting the `trash` key disables trash on that server.

```yaml
servers:
  - id: pandora
    host: pandora
    trash: _trash
  - id: qnap2
    host: qnap2
    trash: _trash
```

Each volume on `pandora` (e.g. `jav_A`, `jav_B`, `jav_unsorted`) gets its own `_trash` folder at its share root. The folder name is taken from the server's `trash` setting.

---

## 4. Trash Operation

To trash an item at `/stars/popular/MIDE-123` on volume `a`:

1. Call `createDirectories("/_trash/stars/popular/")` — creates the full path including `_trash` itself if this is the first trash operation on the volume; no-ops on any segments that already exist
2. Move `/stars/popular/MIDE-123` → `/_trash/stars/popular/MIDE-123`
3. Write sidecar `/_trash/stars/popular/MIDE-123.json`

The move is a single SMB2 `FileRenameInformation` round-trip — atomic, no data transfer.

**Path structure is preserved inside trash.** The item's original directory tree is mirrored under `_trash/`, which guarantees collision-free naming by construction — two items can only collide in trash if they occupied the same path on the volume, which is impossible. It also preserves full context: the location within `_trash/` tells you exactly where the item came from.

---

## 5. Sidecar Metadata

A JSON file co-located with the trashed item. Named `<item-name>.json` alongside the item, mirroring the same path structure under `_trash/`. Works uniformly for both files and folders.

```json
{
  "originalPath": "/stars/popular/MIDE-123",
  "trashedAt": "2026-04-14T10:22:00Z",
  "volumeId": "a",
  "reason": "Duplicate Triage — kept better copy on volume bg"
}
```

**Required fields** — all four are mandatory. Writers must populate every one.

| Field | Type | Description |
|---|---|---|
| `originalPath` | string | Share-relative absolute path before trashing |
| `trashedAt` | string | ISO 8601 UTC timestamp, `Z`-terminated |
| `volumeId` | string | Volume the item came from |
| `reason` | string | App-provided free-form explanation of *why* the item was trashed |

**Optional fields** — absent when not applicable; omitted from newly-trashed items.

| Field | Type | Description |
|---|---|---|
| `scheduledDeletionAt` | string | ISO 8601 UTC timestamp. Absent = not scheduled. Presence means "delete no earlier than this time." |

Writers MAY add additional app-specific fields. The Trash Management screen (Utilities feature) will display the required fields and render unknown fields as extra key-value rows. Extra field names must not collide with the reserved five.

**Feature-specific `reason` conventions** (established per-feature for consistency):

- Duplicate Triage: `"Duplicate Triage — {ranker rationale or free text}"`
- Future features set their own.

Sidecar loss is acceptable. If the sidecar write fails after a successful move, log a warning and continue — the item is still safely in `_trash/`. If the user deletes the item manually from the NAS, the orphaned sidecar can be ignored or cleaned up separately.

---

## 6. Applicability

Trash applies to any entity with a physical path on a volume:

- Title folders (`/stars/popular/MIDE-123/`)
- Actress folders (`/stars/popular/Yua Mikami/`)
- AV video files (`/stars/Gianna Dior/video.mp4`)
- Cover images, loose files, orphaned folders

It is a file-level primitive. Higher-level commands (bulk move, reorganize, prune) can invoke it as their removal mechanism.

---

## 7. Non-Goals

- The app does not perform immediate permanent deletion; deletion is always scheduled with a minimum 10-day holding period and runs asynchronously.
- The app does not update the database on restore — restored items become orphaned until the next volume sync rediscovers them.
- No cross-volume trash or restore (physically impossible with intra-volume move constraint).
- No quota tracking or trash size limits.
