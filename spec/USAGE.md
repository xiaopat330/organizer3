# Organizer3 - Usage Guide

## Starting the Shell

```
./gradlew run
```

The shell starts in **dry-run mode** by default (safe — no file operations execute). The prompt shows current state:

```
organizer [*DRYRUN*] >          # no volume mounted
organizer:vol-a [*DRYRUN*] >   # volume "a" mounted
organizer:vol-a >               # armed (live mode)
```

Type `help` at any time to list available commands.

---

## Commands

### `volumes`

Lists all configured volumes with their connection status and last-sync timestamp.

```
organizer > volumes
ID      STRUCTURE       CONNECTED   LAST SYNC
------------------------------------------------------------
a       conventional    -           2026-03-15 14:22
bg      conventional    -           2026-03-10 09:00
unsorted queue          -           never
```

---

### `mount <id>`

Connects to a volume over SMB and activates it as the current session context. Displays a spinner during the connection phases.

```
organizer > mount a
Loaded index: 3241 title(s), 187 actress(es).
Connected. Volume 'a' is now active.
organizer:vol-a [*DRYRUN*] >
```

If the volume has never been synced:

```
organizer > mount unsorted
No index found for volume 'unsorted' — run 'sync all' to build it.
Connected. Volume 'unsorted' is now active.
```

---

### `unmount`

Disconnects from the current volume and clears the session context.

```
organizer:vol-a > unmount
Disconnected from volume 'a'.
organizer [*DRYRUN*] >
```

---

### `sync all`

Full sync for the currently mounted volume. Clears all existing title/video records for the volume and re-scans from the filesystem. Available for `conventional` and `exhibition` volumes.

```
organizer:vol-a > sync all
Syncing a (full) ...
  Scanning queue/ ...
  Scanning stars/library/ ...
  Scanning stars/popular/ ...
  ...
Sync complete.
  Actresses:  187
  Queue:      12
  Attention:  3
  Total:      3241
```

---

### `sync queue`

Partition-scoped sync — rescans only the `queue/` partition on a `conventional` volume. Faster than a full sync when you only have new intake to index.

```
organizer:vol-a > sync queue
```

---

### `sync`

Full sync for `queue` structure volumes (e.g., `unsorted`, `classic`).

```
organizer:vol-unsorted > sync
```

---

### `actresses <tier>`

Lists all actresses in a given tier with their title counts, sorted from most to fewest. Does not require a mounted volume — queries the DB directly.

Valid tier values (case-insensitive): `library`, `minor`, `popular`, `superstar`, `goddess`

```
organizer > actresses goddess
GODDESS  (5 actresses)
  NAME                                      TITLES
  ------------------------------------------------
  Yua Mikami                                127
  Aya Sazanami                               98
  Hibiki Otsuki                              87
  Mia Khalifa                                56
  Julia Boin                                 51
```

---

### `favorites`

Lists all favorited actresses with their title counts, sorted from most to fewest. Does not require a mounted volume.

```
organizer > favorites
FAVORITES  (3 actresses)
  NAME                                      TITLES
  ------------------------------------------------
  Yua Mikami                                127
  Aya Sazanami                               98
  Hibiki Otsuki                              87
```

---

### `help`

Lists all available commands with descriptions.

```
organizer > help
Available commands:
  actresses        List actresses in a tier with title counts: actresses <tier>
  favorites        List all favorited actresses with title counts
  help             List available commands
  mount            Connect to a volume and activate it as the current context. Usage: mount <id>
  shutdown         Shut down the application
  sync             Sync the current volume's database index from the filesystem.
  sync all         Sync the current volume's database index from the filesystem.
  sync queue       Sync the current volume's database index from the filesystem.
  unmount          Disconnect from the current volume and clear the session context.
  volumes          List all configured volumes with connection and sync status
```

---

### `shutdown`

Exits the shell. Ctrl+D also exits gracefully.

---

## Common Workflows

### First-time setup for a new volume

```
organizer > mount unsorted
No index found for volume 'unsorted' — run 'sync all' to build it.
Connected. Volume 'unsorted' is now active.

organizer:vol-unsorted > sync
Syncing unsorted (full) ...
  ...
Sync complete.
  Total:  412
```

### Refreshing the index after filesystem changes

```
organizer:vol-a > sync all
```

Or, if only the queue changed:

```
organizer:vol-a > sync queue
```

### Browsing the actress database

```
# Who are the top performers?
organizer > actresses goddess

# Who has between 5 and 19 titles?
organizer > actresses minor

# Who have I marked as favorites?
organizer > favorites
```

### Switching volumes

```
organizer:vol-a > mount bg
Connected. Volume 'bg' is now active.
organizer:vol-bg [*DRYRUN*] >
```

Switching volumes automatically closes the previous SMB connection.

---

## Volumes Reference

| ID | SMB Path | Structure | Partition range |
|----|----------|-----------|-----------------|
| a | //pandora/jav_A | conventional | A |
| bg | //pandora/jav_BG | conventional | B–G |
| hj | //pandora/jav_HJ | conventional | H–J |
| k | //pandora/jav_K | conventional | K |
| m | //pandora/jav_M | conventional | M |
| ma | //pandora/jav_MA | conventional | MA |
| n | //pandora/jav_N | conventional | N |
| r | //pandora/jav_OR | conventional | O–R |
| s | //pandora/jav_S | conventional | S |
| tz | //pandora/jav_TZ | conventional | T–Z |
| unsorted | //pandora/jav_unsorted | queue | intake |
| classic | //qnap2/JAV/classic | queue | classic |
| qnap | //qnap2/jav | exhibition | overflow |
| collections | //pandora/jav_collections | collections | curated |

---

## Actress Tiers

Tier determines which subfolder under `stars/` an actress's content lives in on conventional volumes.

| Tier | Title count | Folder |
|------|-------------|--------|
| LIBRARY | < 5 | `stars/library/` |
| MINOR | 5–19 | `stars/minor/` |
| POPULAR | 20–49 | `stars/popular/` |
| SUPERSTAR | 50–99 | `stars/superstar/` |
| GODDESS | 100+ | `stars/goddess/` |

Actresses in `stars/favorites/` and `stars/archive/` are stored with tier `LIBRARY` in the DB regardless of title count.
