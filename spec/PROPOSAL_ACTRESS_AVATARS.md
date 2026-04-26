# Proposal: Local Actress Avatar Storage

## Problem

`javdb_actress_staging` stores the remote `avatar_url` (from `jdbstatic.com` CDN), but
browsers cannot display these images when loaded from the organizer UI — the CDN blocks
hotlink requests whose `Referer` is not `javdb.com`. The Profile tab in the Discovery
screen currently renders a broken image.

## Goal

Download and store actress avatars locally at fetch time, serve them from the organizer's
own web server, and expose them as a stable URL so any screen in the app can display them.

## Proposed Implementation

### 1. Schema v27 — `local_avatar_path`

```sql
ALTER TABLE javdb_actress_staging ADD COLUMN local_avatar_path TEXT;
```

Stores a path relative to `dataDir`, e.g. `actress-avatars/AbXy.jpg`.

### 2. `ActressAvatarStore` (`javdb/enrichment/`)

Small service responsible for:
- Downloading an image URL via HTTP (no session cookie needed — CDN is public)
- Deriving the file extension from the `Content-Type` response header (fallback: parse from URL)
- Writing to `<dataDir>/actress-avatars/{slug}.{ext}`
- Returning the relative path for DB storage

Skip download if the file already exists (idempotent). Log a warning but do not fail the
enrichment job if the download fails.

### 3. Hook in `EnrichmentRunner`

After `stagingRepo.upsertActress(row)`, if `extract.avatarUrl()` is non-null:

```java
String relPath = avatarStore.download(slug, extract.avatarUrl());
if (relPath != null) {
    stagingRepo.updateLocalAvatarPath(actressId, relPath);
}
```

### 4. `AvatarRoutes`

Serve `/actress-avatars/{file}` from `<dataDir>/actress-avatars/`, following the same
path-traversal guard pattern as `CoverRoutes`.

Wire in `WebServer` and `Application` alongside `CoverRoutes`.

### 5. API surface

- `JavdbDiscoveryService.getActressProfile()` — add `localAvatarUrl` field, set to
  `/actress-avatars/{filename}` when `local_avatar_path` is present, null otherwise.
- Actress detail API (main browse) — expose `localAvatarUrl` so the actress detail page
  can display the avatar too.

### 6. Frontend

- Discovery Profile tab: prefer `localAvatarUrl` over `avatarUrl` when present.
- Actress detail page: add avatar display using `localAvatarUrl`.
- Actress list / cards: optional stretch goal.

## Re-enrichment

Existing fetched profiles will not have a local avatar. A re-fetch of the profile
(`↺ Re-fetch Profile`) will trigger the download. No bulk backfill needed unless desired.

## Notes

- Storage is under `dataDir` (consistent with covers, javdb_raw, thumbnails).
- The download uses a plain `HttpClient` — no session cookie or rate limiter needed for
  the CDN.
- File naming by slug is deterministic; no DB lookup required to resolve the serve path.
