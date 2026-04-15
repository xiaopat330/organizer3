# AV Stars — Western Performer Library

> **Status: FULLY IMPLEMENTED**
> All phases of the AV Stars backend are complete: `avstars` volume type, `av_actresses` / `av_videos` / `av_tag_definitions` / `av_video_tags` / `av_video_screenshots` schema, sync (`av sync`), IAFD enrichment (`av resolve`), filename parsing (`av parse`), screenshot generation (`av screenshots`), curation commands, web UI, federated search integration, and backup/restore support. A second AV volume (`athena_av` → `//athena/AV/stars`) has been added alongside the original `qnap_av`.

Design proposal for adding a new volume type, `avstars`, holding Western/European adult performer content alongside the existing JAV library. AV stars content lives on a single volume (`qnap_av` → `//qnap2/AV/stars`) and is modeled independently from the existing `actresses` / `titles` schema — there is **no shared identity or crossover** between JAV and AV data.

This document is the source of truth for the initial AV stars implementation. It was derived from direct inspection of `/Volumes/AV/stars/` and analysis of eight sample IAFD profiles (Charity Crawford, Gianna Dior, August Ames, Ashley Adams, Vina Sky, Angel Dark, and prior reference: Anissa Kate, Asa Akira via folder sampling only).

---

## 1. Motivation

The existing library framework assumes:
- Titles are discrete releases identified by a JAV-style code (`ABP-123`)
- Actresses live in tiered sub-partitions (`popular`, `goddess`, etc.)
- A title has a single canonical code and appears in well-defined partitions

None of these hold for the AV stars content. Inspection of `/Volumes/AV/stars/` shows:
- **68 top-level actress folders** (Western stage names: `Anissa Kate`, `Asa Akira`, `Adriana Checkik`, …)
- **~15,296 video files** scattered across actress folders, some loose, some in arbitrary subfolders
- **No release codes.** Filenames encode studio/site, date, and tags but follow no single convention
- **Ad-hoc organizational buckets** inside actress folders: `old`, `new`, `keep`, `trash`, `incomplete`
- **Occasional genuine subfolder titles** (compilations/DVDs like `Asa Akira Is Insatiable 2`, `Private The Best Of Anissa Kate`)
- **One cross-contamination:** `Melody Marks/jav/` contains JAV codes mixed into an AV folder

Rather than contort the existing schema to accept this chaos, `avstars` introduces a parallel, minimal data model where the only durable identity is **(actress, video file)**.

---

## 2. Non-goals

- **No sharing with JAV actresses/titles.** `av_actresses` and `av_videos` are their own tables. An AV performer who has also done JAV work (e.g. Asa Akira, Melody Marks) still has **one JAV record in `actresses`** and **one AV record in `av_actresses`** — they are not linked. The existing `actresses` / `titles` commands never see AV data and vice versa.
- **No code parsing.** `TitleCodeParser` is irrelevant here.
- **No tier system.** AV stars have no `library / popular / superstar / goddess` hierarchy.
- **No file operations in phase 1.** Sync is read-only like the existing sync pipeline.
- **No web UI in phase 1.** Dashboards come after the CLI and data layer work.

---

## 3. Volume and structure config

### 3.1 Volume entry (`organizer-config.yaml`)

```yaml
volumes:
  - id: qnap_av
    smbPath: //qnap2/AV/stars
    structureType: avstars
    server: qnap2
```

### 3.2 Structure type

```yaml
structures:
  - id: avstars
    unstructuredPartitions: []
    # No structuredPartition block — avstars uses recursive treewalk
    # from the volume root, where each top-level folder is an actress.
    ignoredSubfolders:
      - trash
      - .Trashes
      - incomplete
      # add more as needed — matched case-insensitively against any
      # subfolder name encountered during treewalk
```

`avstars` does not use `PartitionDef` in the normal sense. The volume root is conceptually a flat collection of actress folders, and sync walks each one recursively. There is no `partition_id` stored for AV videos because there is no partition layout.

**`ignoredSubfolders`**: list of folder names that sync will skip during recursive treewalk. Matched case-insensitively against the folder's own name (not its full path), so a `trash` entry skips any `trash/` or `Trash/` anywhere in the tree. JAV content is NOT special-cased — a `jav/` subfolder under an AV actress is treated like any other folder and its contents are ingested as `av_videos`. AV and JAV are separate namespaces; a JAV-coded file sitting in an AV actress folder is still, to this schema, just a video belonging to that AV actress.

### 3.3 Sync config

```yaml
syncConfig:
  - structureType: avstars
    commands:
      - term: sync all
        operation: full
```

A new `FullSyncOperation` variant (or an `AvStarsSyncOperation`) handles the treewalk. See §5.

---

## 4. Database schema

Two new tables in a new migration `SchemaUpgrader.applyV11()`. Both live in `com.organizer3.avstars` territory and are wholly independent of existing tables.

### 4.1 `av_actresses`

```sql
CREATE TABLE av_actresses (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    volume_id       TEXT NOT NULL REFERENCES volumes(id),
    folder_name     TEXT NOT NULL,          -- as seen on disk
    stage_name      TEXT NOT NULL,          -- display name (defaults to folder_name)

    -- IAFD identity
    iafd_id         TEXT,                   -- UUID from iafd.com
    headshot_path   TEXT,                   -- local cached headshot
    aka_names_json  TEXT,                   -- [{name, source}]

    -- Personal
    gender          TEXT,
    date_of_birth   TEXT,
    date_of_death   TEXT,
    birthplace      TEXT,
    nationality     TEXT,                   -- may be compound ("American, Vietnamese")
    ethnicity       TEXT,

    -- Physical
    hair_color      TEXT,                   -- may be slash-delimited ("Black/Brown/Auburn")
    eye_color       TEXT,
    height_cm       INTEGER,
    weight_kg       INTEGER,
    measurements    TEXT,                   -- "32B-24-34"
    cup             TEXT,
    shoe_size       TEXT,                   -- nullable; field may be absent on page
    tattoos         TEXT,                   -- literal "None" vs NULL is meaningful
    piercings       TEXT,

    -- Career
    active_from     INTEGER,                -- performer era
    active_to       INTEGER,                -- NOT a retirement flag; see §7.3
    director_from   INTEGER,                -- nullable; some transition to directing
    director_to     INTEGER,
    iafd_title_count INTEGER,               -- "Performer Credits (N)" top-line stat

    -- External links
    website_url         TEXT,
    social_json         TEXT,               -- {twitter, facebook, instagram, tiktok, ...}
    platforms_json      TEXT,               -- paid content: {onlyfans, manyvids, ...}
    external_refs_json  TEXT,               -- other DBs: {egafd, stashdb, wikidata, ...}

    -- Editorial / awards
    iafd_comments_json TEXT,                -- JSON array of remark strings from Comments tab
    awards_json     TEXT,                   -- {org: [{status, year, category, title, title_year, title_iafd_id}]}
                                            --   status ∈ {nominee, winner, inducted}

    -- Curation (user)
    favorite        INTEGER NOT NULL DEFAULT 0,
    bookmark        INTEGER NOT NULL DEFAULT 0,
    rejected        INTEGER NOT NULL DEFAULT 0,
    grade           TEXT,                   -- SSS/SS/S/A/B/C (parity with JAV)
    notes           TEXT,

    -- Housekeeping
    first_seen_at       TEXT NOT NULL,
    last_scanned_at     TEXT,
    last_iafd_synced_at TEXT,
    video_count         INTEGER NOT NULL DEFAULT 0,
    total_size_bytes    INTEGER NOT NULL DEFAULT 0,

    UNIQUE(volume_id, folder_name)
);

CREATE INDEX idx_av_actresses_volume ON av_actresses(volume_id);
CREATE INDEX idx_av_actresses_iafd_id ON av_actresses(iafd_id);
```

### 4.2 `av_videos`

```sql
CREATE TABLE av_videos (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    av_actress_id   INTEGER NOT NULL REFERENCES av_actresses(id),
    volume_id       TEXT NOT NULL REFERENCES volumes(id),
    relative_path   TEXT NOT NULL,          -- relative to actress folder
    filename        TEXT NOT NULL,
    extension       TEXT,
    size_bytes      INTEGER,
    mtime           TEXT,                   -- filesystem modification time
    last_seen_at    TEXT NOT NULL,
    added_date      TEXT,                   -- estimated from filesystem

    -- Location metadata (populated by sync)
    bucket          TEXT,                   -- first-level subfolder name under actress (e.g. "old", "new", "keep"); NULL if loose at root

    -- Parsed from filename (second pass, all nullable)
    studio          TEXT,                   -- "Brazzers", "DorcelClub"
    release_date    TEXT,
    parsed_title    TEXT,
    resolution      TEXT,                   -- "1080p", "2160p"
    codec           TEXT,                   -- "h265"
    tags_json       TEXT,                   -- parsed scene tags

    -- Curation (user)
    favorite        INTEGER NOT NULL DEFAULT 0,
    rejected        INTEGER NOT NULL DEFAULT 0,

    UNIQUE(av_actress_id, relative_path)
);

CREATE INDEX idx_av_videos_actress ON av_videos(av_actress_id);
CREATE INDEX idx_av_videos_volume ON av_videos(volume_id);
CREATE INDEX idx_av_videos_studio ON av_videos(studio);
CREATE INDEX idx_av_videos_bucket ON av_videos(bucket);
```

### 4.3 Schema design notes

- **No shared FK with JAV actresses/titles.** Deliberate, to keep the two libraries fully independent.
- **`relative_path` as uniqueness key** — same filename can appear in both `old/` and `new/` buckets within one actress folder. Path disambiguates.
- **Denormalized counts on `av_actresses`** (`video_count`, `total_size_bytes`) for fast dashboard rendering — recomputed at end of sync.
- **Parsed video fields are nullable and computed in a separate pass** (§6) — keeps sync reliable regardless of filename chaos.
- **Filmography from IAFD is NOT stored in `av_actresses.awards_json` or as a blob** — if we ever ingest it, it gets its own `av_iafd_credits` table (see §9, deferred).

---

## 5. Sync pipeline

New operation: `AvStarsSyncOperation` (parallels `FullSyncOperation`).

```
sync_start = now()
for each top-level dir under volume root:
    actress = findOrCreate av_actress by (volume_id, folder_name)
    recursive treewalk via VolumeFileSystem:
        for each file with recognized video extension:
            upsert av_video by (av_actress_id, relative_path)
            set size_bytes, mtime, last_seen_at = now
    recompute actress.video_count + total_size_bytes
delete av_videos where last_seen_at < sync_start (orphans)
stamp volumes.last_synced_at
```

**Video extension list** — same as existing `SyncCommand`:
`mkv mp4 avi mov wmv mpg mpeg m4v m2ts ts rmvb divx asf wma wm`

**Directories to skip during treewalk:**
- Names starting with `.` (hidden: `.DS_Store`, `.Trashes`, resource forks)
- Any name in the structure's `ignoredSubfolders` config list (default: `trash`, `.Trashes`, `incomplete`), matched case-insensitively against the folder's own name at any depth
- **Not** `jav/` — AV and JAV are separate namespaces; a JAV-looking folder under an AV actress is ingested as AV videos belonging to that AV actress

**Orphan handling:** `av_actresses` records whose folder is gone are NOT auto-deleted — they're left in place so that user curation (favorite / grade / notes) survives disk reorganization. A future `prune-av-actresses` command can clean up orphans explicitly. (Mirror of how cover orphans work today.)

---

## 6. Filename parsing (separate pass)

Lives in its own `AvFilenameParser` class, runs via `av parse filenames`. **Does not block sync.** Populates the nullable parsed columns on `av_videos`. Idempotent — can be re-run any time as heuristics improve.

**Sample filename patterns observed:**
```
[Brazzers] (Anissa Kate) Fucked In Front Of Class XXX (2019) (1080p HEVC) [GhostFreakXX].mp4
!DorcelClub - 2015.12.30 Gets A Hard DP Action With Her Business Associates 2160p-h265.mkv
2008.07 [Vouyer Media] Asa Akira (Control Freaks Scene.1) (1080p).mp4
1111Customs.22.08.23.Alex.Coal.Anissa.Kate.Alex.Coal.Shares.Her.Boyfriend.With.Anissa.Kate.XXX.2160p.mp4
[AdrianaChechik.com] - 2014.09.19 - Hands Mouth Full [Gangbang, Anal, DP, Facials]-h265.mkv
```

**Fields the parser attempts to extract:**
- `studio` — from leading `[Studio]`, `!Studio` marker, or `Studio.NN.NN.NN` dot-form
- `release_date` — `YYYY.MM.DD`, `YYYY-MM-DD`, `(YYYY)`, or `NN.NN.NN` two-digit year
- `resolution` — `1080p`, `2160p`, `720p`, `4K`
- `codec` — `h265`, `HEVC`, `x265`, `WEB-DL`
- `parsed_title` — residual after stripping known markers
- `tags_json` — contents of trailing `[tag, tag, tag]` block

Note: the leading `!` character sometimes seen on filenames has no semantic meaning — it's an ad-hoc sort trick from the user's organization workflow, not a priority flag. The parser strips it and discards it.

The parser is iterated against a corpus of real filenames collected from the mounted volume. Low-confidence parses leave fields NULL rather than polluting with guesses.

---

## 7. IAFD integration

### 7.1 Client

- HTTP via whatever works through the same access path we use for FANZA today. WebFetch returns 403; `mcp__fetch__fetch` with `raw:true` works.
- Profile URL: `https://www.iafd.com/person.rme/id=<uuid>`
- Search URL: `https://www.iafd.com/ramesearch.asp` (form POST)

### 7.2 Profile parser (`IafdProfileParser`)

Uses jsoup to extract `p.bioheading` / `p.biodata` pairs plus tab content.

**Fields mapped directly** — the straightforward bioheading → biodata cases:
- Birthday → `date_of_birth`
- Date of Death → `date_of_death`
- Birthplace → `birthplace`
- Gender → `gender`
- Ethnicity → `ethnicity`
- Nationality → `nationality`
- Eye Color → `eye_color`
- Height → `height_cm` (parse the cm suffix)
- Weight → `weight_kg` (parse the kg suffix)
- Measurements → `measurements`
- Shoe size → `shoe_size`
- Tattoos → `tattoos`
- Piercings → `piercings`
- Website → `website_url`

**Fields needing parsing logic:**

- **AKA block** — single `<div class="biodata">` with `<br>`-separated names. Each line optionally ends with `(source)`. Literal `"No known aliases"` → empty array. Observed max: 10 aliases (Angel Dark).
- **Hair label** — accept both `"Hair Color"` and `"Hair Colors"`. Value may be slash-delimited (`Black/Brown/Red/Auburn`). Store as-is.
- **Years Active label** — accept `"Years Active"`, `"Years Active as Performer"`. Value shape: `YYYY-YYYY (Started around N years old)` — parse the two years.
- **Years Active as Director** — separate bioheading, may be a range (`2022-2025`) or single year (`2024`). Populates `director_from`/`director_to`.
- **Social Network** — a row of `<a>` tags, each containing an `<img>` whose filename encodes the platform (`x.png`, `2023_Facebook_icon.svg`, `Instagram_Glyph_Black.png`, `tiktok-icon2.png`). Use the image filename as the key into `social_json`.
- **Digital distribution platform** — same pattern with different icons (`OnlyFans_Social_Icon_Rounded_Blue.png`, `mv.ico`). Populates `platforms_json`.
- **External DB links** — e.g. `<p class="bioheading">EGAFD</p>` with a link out to `egafd.com`. Store under `external_refs_json`. Extensible for StashDB, Wikidata, etc. when encountered.
- **Awards** — per-org `<p class="bioheading">`, then alternating `<div class="showyear">` / `<div class="biodata">`. The biodata text begins with `"Nominee:"`, `"Winner:"` (often wrapped in `<b>`), or `"Inducted:"`. May contain a `<a href="/title.rme/id=...">` for a linked title with `(YYYY)` year annotation. Populates `awards_json`.
- **Comments tab** — `<div id="comments">` may contain `<ul><li class="cmt">` entries. Each entry goes into `iafd_comments_json` as a string.
- **Performer Credits count** — extract the N from `"Performer Credits (N)"` in the perftabs nav. Stores to `iafd_title_count` without ingesting the full filmography.

### 7.3 Important parser semantics

- **`active_to` is NOT a retirement flag.** Observed 5/8 samples with `active_to = 2025` and clearly still active. It's just "most recent year IAFD has activity for." If we ever show a "Retired" badge, derive it from `date_of_death` or other signals — not from `active_to`.
- **`"None"` on tattoos/piercings is literal page text.** Store the literal — it's distinct from NULL (unresolved).
- **Missing fields are missing.** Ashley Adams has no Shoe size row at all. Absent ≠ NULL for all fields — only set columns when the bioheading was present.
- **Compound values are free text.** `"American, Vietnamese"`, `"Black/Brown/Auburn"` — don't normalize to single values.

### 7.4 Resolution commands

- `av resolve <name>` — interactive: search IAFD by name, present candidate list, user picks → fetch profile → populate `av_actresses`, cache headshot
- `av resolve all` — batch over unresolved (`iafd_id IS NULL`), logging ambiguous cases for manual resolution
- `av resolve refresh <name>` — re-fetch a resolved profile to pick up changes

Headshots are downloaded to `data/av_headshots/<iafd_id>.<ext>`. Parallel to the existing `data/covers/` layout but gitignored separately.

---

## 8. Commands (package `com.organizer3.avstars.command`)

All new commands live under an `av` prefix namespace to keep them visually distinct from JAV commands. The existing `actresses`, `favorites`, etc. are untouched and never see AV data.

| Command | Requires Mount | Description |
|---|---|---|
| `sync all` | Yes | Full avstars sync — treewalk each actress folder, populate `av_*` tables |
| `av actresses` | No | List AV actresses sorted by video count |
| `av actress <name>` | No | Show profile + video list for one AV actress |
| `av parse filenames` | No | Run the filename parser pass against `av_videos` |
| `av resolve <name>` | No | Fetch IAFD profile for one actress (interactive) |
| `av resolve all` | No | Batch IAFD resolution |
| `av favorites` | No | List favorited AV actresses |
| `av migrate-actress <old> <new>` | No | Move curation (favorite/grade/notes/IAFD linkage) from an orphaned `av_actresses` row to a renamed folder's row. Manual — sync does not attempt fuzzy matching. |

Existing command infrastructure (`Command` interface, `CommandIO`, `SessionContext`) is reused unchanged.

---

## 9. Deferred / out of scope

These are explicitly deferred to keep phase 1 tractable:

- **IAFD filmography ingestion.** When we want it, it goes in a new `av_iafd_credits` table: `(av_actress_id, iafd_title_id, title, year, distributor, notes_tags)`. Don't jam into JSON.
- **Cross-referencing `av_videos` with IAFD credits.** Would let us resolve filename ambiguity via fuzzy match against known titles. Post-phase-1.
- **Web UI / AV dashboard.** A `/av` route tree parallel to Titles/Actresses dashboards. Design phase of its own.
- **Galleries tab** from IAFD (Angel Dark has one). Rare, low value.
- **Director AKA** block on IAFD. Rare.
- **Arm/test mode integration.** Sync is read-only; no file ops to gate.

---

## 10. Phasing

### Phase 1 — Foundation
1. Volume + structure type + syncConfig entries in `organizer-config.yaml`
2. Schema migration v11 (`applyV11`): create `av_actresses` and `av_videos`
3. Domain records: `AvActress`, `AvVideo`
4. Repositories: `AvActressRepository`, `AvVideoRepository` + JDBI impls
5. Repository tests against real in-memory SQLite
6. `AvStarsSyncOperation` + tests (mocked `VolumeFileSystem`)
7. Wire up via `syncConfig` — `sync all` on `qnap_av` works end-to-end

### Phase 2 — Query surface
1. `av actresses` command
2. `av actress <name>` command
3. `av favorites` command
4. Curation: favorite / grade / bookmark / reject toggles

### Phase 3 — Filename parsing
1. `AvFilenameParser` with a test corpus captured from real filenames
2. `av parse filenames` command
3. Iterate on parser against observed patterns

### Phase 4 — IAFD enrichment
1. `IafdClient` — hit profile + search URLs through the same transport as FANZA
2. `IafdProfileParser` — jsoup-based, handling all variability documented in §7.2
3. `av resolve <name>` / `av resolve all` commands
4. Headshot caching under `data/av_headshots/`

### Phase 5 — Web UI
Separate design document, following the pattern established by `PROPOSAL_TITLES_DASHBOARD.md`.

---

## 11. Open questions

1. **IAFD resolution UX** — fully interactive pick at the CLI vs storing `iafd_search_candidates_json` for deferred picking in a future web UI. Phase 4 decision.
2. **IAFD search endpoint shape** — `ramesearch.asp` form params and response structure haven't been mapped yet. Needs a probe in Phase 4 before `av resolve` can be implemented.
3. **Headshot cache eviction** — if a profile re-resolves and the headshot URL changes, do we keep the old file around? Punt until it matters.

---

## 12. Package structure

```
com.organizer3.avstars/
  model/
    AvActress.java              (record)
    AvVideo.java                (record)
  repository/
    AvActressRepository.java
    AvVideoRepository.java
    jdbi/
      JdbiAvActressRepository.java
      JdbiAvVideoRepository.java
  sync/
    AvStarsSyncOperation.java
    AvStarsVolumeScanner.java
    AvFilenameParser.java
  iafd/
    IafdClient.java
    IafdProfileParser.java
  command/
    AvActressesCommand.java
    AvActressCommand.java
    AvFavoritesCommand.java
    AvParseFilenamesCommand.java
    AvResolveCommand.java
```
