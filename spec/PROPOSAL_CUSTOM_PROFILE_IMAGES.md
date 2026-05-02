# Proposal: Custom Profile Images

Status: **Draft** — design alignment before implementation.
Originating context: 2026-05-01. Enriched profile images (javdb avatars, stored as
`actress-avatars/{slug}.{ext}` and pointed to by
`javdb_actress_staging.local_avatar_path`) have proven useful, but two gaps remain:

1. Older-era enriched avatars are often low quality.
2. The vast majority of actresses will never be enriched, so they have no avatar at all.

The fix: allow any actress — enriched or not — to have a **user-curated avatar**,
cropped from one of her own title covers via an in-app editor.

## Model

Each actress may have up to two avatar images, which coexist:

- **Enriched avatar** — unchanged from today. Lifecycle owned by enrichment.
  Path stored in `javdb_actress_staging.local_avatar_path`.
- **Custom avatar** — new. Lifecycle owned by the user via the editor described
  below. Path stored on the new column `actresses.custom_avatar_path`.

Resolution rule (server-side, single source of truth):

```
displayed avatar = custom_avatar_path  if present
                 else local_avatar_path (enriched)
                 else null  → render placeholder frame
```

When both exist, the enriched copy is **kept on disk** (never deleted by the
custom-avatar workflow). Re-running enrichment must never touch
`actresses.custom_avatar_path` or the file under `actress-custom-avatars/`.

## Storage

- New column: `actresses.custom_avatar_path TEXT NULL`. Migration `applyV43()` —
  idempotent `ALTER TABLE` only, no backfill.
- Files: `<dataDir>/actress-custom-avatars/{actressId}.jpg`. Keyed by actress ID
  (stable, exists for non-enriched actresses too — slug-keying does not work
  here). The directory is parallel to the existing `actress-avatars/` so the
  same static-file route convention applies.
- Format: **JPEG, quality ~0.88, no upscaling.** The minimum crop is 150×150
  natural pixels (covers are typically ~1024×1024, and a useful face crop is
  often a small region of that — forcing a larger minimum would either include
  distracting background or upscale a low-res region into blurriness).
  Server stores at the crop's natural dimensions, **capped at 600×600** (any
  larger crop is downscaled). Atomic writes; client may submit JPEG or PNG and
  the server re-encodes to JPEG.
- Atomic writes via `tmp` + `Files.move` (mirror the pattern in
  `ActressAvatarStore`).

## API

Three endpoints under `/api/actresses/{id}`:

1. `GET /api/actresses/{id}/title-covers`
   Returns `{ titleId, label, code, coverUrl }[]` — every title where this
   actress is in the cast and a local cover file exists. Used to populate the
   editor's cover picker. Sorted however the existing actress-detail title list
   is sorted (date desc).

2. `POST /api/actresses/{id}/custom-avatar`
   Body: `image/jpeg` or `image/png` of the cropped square (raw bytes, no
   wrapping JSON — keeps the upload simple). Server validates: square,
   ≥150×150, ≤ a few MB. Server normalizes to 300×300 JPEG, writes to
   `actress-custom-avatars/{id}.jpg`, sets
   `actresses.custom_avatar_path = "actress-custom-avatars/{id}.jpg"`,
   returns the new URL.

3. `DELETE /api/actresses/{id}/custom-avatar`
   Clears the column and deletes the file. Idempotent.

The `ActressSummary.localAvatarUrl` field is repurposed as **the resolved
avatar** (custom-or-enriched). One additional optional flag is added so the
editor can decide whether to offer the "blank/clear" tile:
`hasCustomAvatar: boolean`. The frontend never has to do its own resolution.

## Frontend

### Placeholder frame

Wherever an actress avatar renders today, replace the conditional `<img>` with a
`<div class="actress-avatar-frame">` that always renders:

- If a resolved avatar exists → `<img>` of it.
- Else → grey tile with a centered **face-silhouette icon** (inline SVG, e.g.
  the Heroicons `user-circle` outline or equivalent). On hover the tile
  darkens slightly and a small "add profile image" caption fades in.
  No `+` glyph — the face icon is the affordance.

The frame is clickable in **two places** in v1:
- **Actress detail page** — primary editor entry. Click anywhere on the frame.
- **Actress grid cards** — clickable only when the resolved avatar is *missing*
  (i.e., the placeholder face is showing). When an avatar is already present,
  the card stays read-only as today; editing requires a trip to the detail
  page. Rationale: the friction concern is bulk-adding avatars to the
  long tail of un-enriched actresses, and grid-pop only triggers on tiles
  that are *asking* to be filled — no surprise modals on already-set avatars.

Shared CSS class (`.actress-avatar-frame` + `.actress-avatar-frame--empty`) so
the visual treatment and click handler live in one component.

Touchpoints (from `grep localAvatarUrl`):
- `src/main/resources/public/modules/actress-detail.js` — primary editor entry.
- `src/main/resources/public/modules/utilities-javdb-discovery.js` — enrichment
  surface; **leaves the `avatarUrl || localAvatarUrl` path alone** (this is the
  enrichment review screen, not the user-facing actress view).
- Any actress grid/card consumer of `ActressSummary.localAvatarUrl` — picks up
  the resolved value transparently.

### Editor modal

State machine:

1. **Picker** — grid of small cover thumbnails (from `GET /title-covers`)
   + a "blank" tile (only shown when `hasCustomAvatar === true`). Clicking
   blank fires `DELETE /custom-avatar` and closes the modal. All thumbnails
   use `loading="lazy"` — no pagination; even prolific actresses (~165
   titles) render fine in a scrollable grid.
2. **Cropper** — selected cover shown full-size (fit-to-modal). User drags a
   square selection (min 150 *natural* pixels — enforced on the source image's
   `naturalWidth`/`naturalHeight`, not on displayed pixels). Shift-drag is
   unnecessary; the selection is square by construction. Live overlay shows
   the selection rect.
3. **Confirm** — OK draws the selection to a hidden `<canvas>`, exports as
   JPEG blob, POSTs to `/custom-avatar`, refreshes the actress view, closes
   the modal. Cancel returns to step 1 (or closes if the user clicked Cancel
   on the picker step).

All cropping is client-side; the server only persists the final image.

## Resolution touchpoints (server)

- `ActressBrowseService` — extend the staging-join query that builds
  `localAvatarUrlByActress` to also `LEFT JOIN actresses` for
  `custom_avatar_path` and prefer it. One query change, ~5 lines.
- `SearchService` — same `r.localAvatarPath()` block at line 109 needs to read
  custom first. Consider lifting "resolve avatar" into a small helper
  (`AvatarResolver.resolve(actressId)` or just a SQL `COALESCE` if the column is
  on `actresses` and joinable everywhere).
- `JdbiActressRepository` — the two queries currently selecting
  `s.local_avatar_path` should be extended to project the resolved value
  (`COALESCE(a.custom_avatar_path, s.local_avatar_path)`).

## Out of scope

- Editing/cropping the **enriched** avatar. The custom avatar is the editing
  surface; enriched stays read-only.
- Multiple custom avatars per actress, history, or undo. One slot, replaceable.
- Source images other than the actress's own title covers (no upload-from-disk
  in v1 — keeps the surface scoped and avoids file-handling questions).
- Bulk tooling. One actress at a time via the UI.
- Image moderation / NSFW gating. The covers are already in the library.

## Migration / rollout

- Schema bump to v43, single `ALTER TABLE actresses ADD COLUMN custom_avatar_path TEXT`.
- No data backfill needed.
- Feature is purely additive: existing enriched avatars continue to render
  through the same `localAvatarUrl` field, just now via `COALESCE`.
- Rollback: drop the column (or leave it nullable and unused) and the
  `actress-custom-avatars/` directory can be deleted by the user.
