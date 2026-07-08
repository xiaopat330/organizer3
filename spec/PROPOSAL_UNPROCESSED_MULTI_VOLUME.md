# PROPOSAL — Extend the Unprocessed tool to service `classic_fresh` (multi-volume intake)

**Status:** DRAFT / thinking — no implementation yet
**Branch:** `feat/unprocessed-classic-pool`
**Date:** 2026-07-08

## 1. Goal

Today the **Unprocessed** curation tool (v1 Tools → Curation → Unprocessed, and the v2
`/v2-unprocessed.html` surface) services exactly one volume: `unsorted`. We want it to also
service a **new archival-intake volume, `classic_fresh`**, so fresh classic/archival rips can be
curated through the same workflow (assign cast + cover + descriptor, or JavDB-enrich → draft →
promote).

Both UIs must keep working — v1 is production, v2 is beta.

## 2. The new volume

```yaml
  - id: classic_fresh
    smbPath: //qnap2.local/JAV/classic/fresh
    structureType: queue
    server: qnap2
    group: archive
```

**Key fact that shapes everything:** `classic_fresh` is a *structural twin* of `unsorted`
(`structureType: queue`). Same `fresh/(CODE)/{video,h265,4K}/…` layout, same bare actor-less
`(CODE)` folders, same disorganized intake semantics. Therefore:

- Sync already handles it — `queue` → `QueueScanner` is registered (`Application.java:374`); no sync work.
- The eligibility query already matches its layout (video under `video/|h265/|4K/`).
- Folder-rename, cover-write, and promotion mechanics are identical — only the *volume they target* is hardcoded.

This is **not** `classic_pool`. `classic_pool` (`//qnap2.local/JAV/classic/new`, `sort_pool`) holds
already-named, cast-carrying, demosaiced archival titles (3,317 of them, all folder-name-derived
cast). It is out of scope here and stays with Discovery/Sort.

## 3. What "unprocessed" means (unchanged)

An eligible staging title whose location has `curated_at IS NULL`. Curating it — manual save or
draft promotion — stamps `curated_at` and flips it to "processed." This definition is
volume-agnostic already; we just widen the set of volumes we look at.

## 4. Backend design — de-hardcode the single volume

### 4.1 The core change: "serviceable staging volumes"

Replace the single `UNSORTED_VOLUME_ID = "unsorted"` string (`Application.java:1129`) with the
**set of volumes whose `structureType = "queue"`** — currently `{unsorted, classic_fresh}`, and
self-extending for any future queue volume. Keying off `structureType` (not a hardcoded id list)
mirrors how `TitleDiscoveryService` already selects all `sort_pool` volumes.

A title lives on exactly one staging volume, so per-title resolution is unambiguous: find the
title's live location whose `volume_id ∈ serviceable`.

### 4.2 Touch points (all currently bound to `unsortedVolumeId`)

| Component | Today | Change |
|---|---|---|
| `JdbiUnsortedEditorRepository.listEligible(volumeId)` | filters one volume | accept the serviceable set (`volume_id IN (…)`); **return `volume_id` per row** |
| `.findEligibleById` / `.findOtherLocations` / `.hasLocationInVolume` | scoped to one volume | resolve the title's serviceable volume |
| `UnsortedEditorService` | holds one `unsortedVolumeId` + its smbPath | holds the serviceable set; resolves per-title volume + smbPath from the existing `volumeSmbPaths` map (already injected, `Application.java:1137`) |
| `DraftPromotionService` (curated_at stamp `:606`, rename base) | binds `unsortedVolumeId` | stamp `volume_id IN (serviceable)`; rename/cover on the title's actual volume |
| `TitleFolderRenamer` (ctor takes one volume) | renames on `unsorted` | per-title volume-aware (pass volume in, or resolve from location) |
| `CoverWriteService` (ctor takes one volume + smbPath) | writes to `unsorted` | resolve title's volume + smbPath |
| `PromotionFolderRenameReconciler` (background) | scoped to `unsorted` | iterate serviceable volumes |

`volumeSmbPaths` (id → smbPath) is already built and threaded, so per-volume SMB base paths need
no new plumbing.

### 4.3 API — keep the paths, add a volume dimension

The endpoint names (`/api/unsorted/titles`, `/api/unsorted/titles/:id/actresses|cover`) are a
load-bearing misnomer both UIs call. **Do not rename them** — pure churn that breaks production.
Instead:

- `GET /api/unsorted/titles` → each row gains **`volumeId`** (and a display label, e.g. `"Unsorted"` / `"Classic"`). Optionally accept `?volume=<id>` to filter.
- All other endpoints are keyed by `titleId` and resolve the volume internally → **no path or contract change**, so both UIs keep working untouched at the wire level.
- Draft endpoints (`/api/drafts/*`) are already `titleId`-keyed → **unaffected**.

### 4.4 Downstream filing (note, not in scope)

Curation is in-place: promote/save stamps `curated_at` + renames the folder on `classic_fresh`.
Filing to a library volume happens later via the **Sort/organize** pipeline, which routes by the
*credited actress's folder*, not by source volume. So a curated `classic_fresh` title follows its
actress wherever she's filed. **Open question for later:** whether classic titles should be forced
into the `archive` group (`classic`/`qnap_archive`) rather than mixed onto letter volumes. That's a
Sort-pipeline concern, separate from this tool, but worth confirming so curated classics don't
land somewhere unexpected.

## 5. UI / UX — differentiating the two volumes

The user explicitly wants to decide this. Both UIs need *some* way to tell an Unsorted row from a
Classic row. Options, from least to most invasive:

### v1 (production, legacy-protected — favor minimal)

- **Minimal (recommended):** a small per-row **volume pill** next to the existing DRAFT/✓-processed
  pills (e.g. a muted `CLASSIC` chip; Unsorted rows show nothing or a neutral chip). Optionally a
  volume filter dropdown above the queue. Low churn to `title-editor.js` (protected — needs your
  approval, already implied by "must work in both UIs").

### v2 (beta — room for richer treatment)

- **Minor:** same per-row volume pill as v1. One consistent look across both surfaces.
- **Major:** a **segmented scope control** at the top of the queue (`All · Unsorted · Classic`) with
  live counts per scope, and/or **grouped sections** (Unsorted header, then Classic header). Richer,
  but more new code in the v2 queue module.

**Default scope decision (rollout-critical):** because both UIs share `/api/unsorted/titles`, the
backend change alone changes v1 production behavior. To avoid classic rows appearing *unlabeled and
mixed* in production, ship the **per-row volume pill in the same release as the backend** (both
UIs), defaulting the queue to **all serviceable volumes**. The richer v2 scope control can follow
later as a pure-v2 enhancement.

## 6. Rollout ordering

1. Add `classic_fresh` to `organizer-config.yaml` (SMB share must exist on the NAS). Sync it.
2. Backend: serviceable-set generalization + `volumeId` in the row payload.
3. Both UIs: per-row volume pill (minimal), queue defaults to all serviceable volumes. **Ship 1–3 together.**
4. (Optional, follow-up) Richer v2 UX — segmented scope control / grouped sections.

## 7. Risks / watch-items

- **Per-title volume resolution** must be deterministic (a title should live on exactly one staging
  volume; assert/pick deterministically if ever both).
- **Legacy edits** to `title-editor.js` need explicit approval and must not regress v1; it also
  imports shared `utils.js`/`task-center.js`/`cards.js`.
- **Cover/rename SMB path** must resolve from the *title's* volume, not a global — the one place a
  wrong-volume bug could write to the wrong NAS share.
- **Test coverage:** the `UnsortedEditor*`, `DraftPromotionService`, and folder-rename tests are
  currently single-volume; extend them with a `classic_fresh` case.

## 8. Open decisions for the user

1. **UX depth** — v1 minimal pill (recommended) + v2 minimal or v2 major (segmented scope control)?
2. **Confirm scope** — `unsorted + classic_fresh` only; `classic_pool` stays out? (assumed yes)
3. **Downstream filing** — do curated classic titles need forced routing into the `archive` group,
   or is actress-driven filing fine? (can defer)
