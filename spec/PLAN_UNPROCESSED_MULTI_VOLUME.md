# PLAN — Unprocessed multi-volume (`classic_fresh`) implementation

**Status:** PLAN LOCKED — not started
**Branch:** `feat/unprocessed-classic-pool`
**Proposal:** `spec/PROPOSAL_UNPROCESSED_MULTI_VOLUME.md`
**Date:** 2026-07-08

## Locked decisions
1. **UX:** v1 = minimal per-row volume pill; v2 = major (segmented `All · Unsorted · Classic`
   scope control + per-scope counts + row pill).
2. **Scope:** Unprocessed services `unsorted + classic_fresh` only. `classic_pool` stays with
   Discovery/Sort.
3. **Filing:** deferred (actress-driven Sort filing is fine for now).

## Dispatch rules (per user)
- **Sonnet** does mechanical/independent work, parallelized where files are disjoint.
- **Opus** owns the architectural spine and all **SMB-write-target** code (wrong volume = wrong NAS
  share) and the **v2 major UX** design.
- **Complex subagent work is vetted by Opus or the advisor before its gate closes.** Vet points are
  marked ⚑.
- Opus owns `Application.java` (shared wiring / merge point) directly — never handed to a subagent.

## Legend
Owner: **O**=Opus, **S**=Sonnet(subagent), **O⇢S**=Opus designs → Sonnet implements → Opus vets.
∥ = safe to run in parallel with its wave-siblings (disjoint files).

---

## Phase 0 — Foundation (runs in parallel with Phase 1 coding)
Gate **G0** only blocks Phase 4 live-verify, not the coding phases (they build against the
abstraction, not live data).

| Wave | Owner | Work | Notes |
|---|---|---|---|
| 0.1 | O | Add `classic_fresh` block to `organizer-config.yaml` (`//qnap2.local/JAV/classic/fresh`, `queue`, qnap2, `group: archive`) | 1-line-ish edit |
| 0.2 | **user** | Provision the SMB share on qnap2; mount + `sync classic_fresh` | physical NAS — not a subagent task |
| 0.3 | O | Confirm titles indexed (`SELECT … WHERE volume_id='classic_fresh'`) | **Gate G0** |

---

## Phase 1 — Backend generalization (the core)

### Wave 1.0 — Contracts & wiring (O, serial — pins everything)
- Derive `Set<String> serviceableStagingVolumeIds` = volumes with `structureType == "queue"` in
  `Application.java`; thread it in place of `UNSORTED_VOLUME_ID`.
- Change interface signatures / records as a **compiling scaffold** (delegates/stubs ok):
  - `UnsortedEditorRepository`: `listEligible(Collection<String>)`; per-title resolvers
    (`findEligibleById`, `findOtherLocations`, `hasLocationInVolume`) resolve across the set.
  - `EligibleTitle` + `TitleDetail` records gain **`volumeId`**.
  - `UnsortedEditorService`, `DraftPromotionService`, `TitleFolderRenamer`, `CoverWriteService`,
    `PromotionFolderRenameReconciler` ctors take the set (+ keep `volumeSmbPaths`).
- **Output:** every signature fixed so Wave 1.1+ subagents code against stable contracts.

### Wave 1.1 — Foundational impl (after 1.0)
| Sub | Owner | Files | ∥ |
|---|---|---|---|
| 1.1a | S ⚑ | `JdbiUnsortedEditorRepository` — `IN`-clause query, `volume_id` in projection, per-title volume resolution helper | — (others depend on it) |
| 1.1f | S | `PromotionFolderRenameReconciler` — loop serviceable volumes | ∥ with 1.1a (disjoint file) |

Vet ⚑: Opus reviews 1.1a (the resolution helper is the shared primitive).

### Wave 1.2 — Services (after 1.1a)
| Sub | Owner | Files | ∥ |
|---|---|---|---|
| 1.2a | S | `UnsortedEditorService` — hold set, resolve per-title volume + smbPath from `volumeSmbPaths` | ∥ |
| 1.2b | **O** ⚑ | `DraftPromotionService` — `curated_at` stamp `volume_id IN (set)`; rename/cover target = **title's actual volume** | ∥ |
| 1.2c | **O⇢S** ⚑ | `TitleFolderRenamer` + `CoverWriteService` — per-title volume + smbPath resolution (SMB **write** path) | ∥ |

Vet ⚑ (**advisor**): review the combined 1.2b+1.2c diff before G1 — this is the one place a
wrong-volume bug writes to the wrong NAS share.

### Wave 1.3 — Tests (S ∥, alongside 1.1–1.2)
Extend `UnsortedEditor*Test`, `DraftPromotionServiceTest`, folder-rename/cover tests with a
`classic_fresh` case (dual-volume queue; promote a classic_fresh title; assert stamp + rename land
on `classic_fresh`, not `unsorted`).

**Gate G1:** backend suite green (constrained-heap run) + advisor sign-off on the SMB-write diff.

---

## Phase 2 — API (S, after G1)
- `UnsortedEditorRoutes` / `UnsortedEditorService.listEligible`: add **`volumeId`** (+ display label)
  to each row; accept optional `?volume=<id>` filter (default = all serviceable).
- **Gate G2:** `curl /api/unsorted/titles` returns `volumeId` for both volumes; `?volume=classic_fresh` filters.

---

## Phase 3 — UI (after G2 — two parallel tracks, disjoint files)

### Track A — v1 minimal (S, Opus-vet ⚑ — legacy-protected)
- `modules/title-editor.js`: per-row volume pill next to DRAFT/✓ pills. Optional volume filter.
- ⚑ Opus vets: no v1 regression; shared `utils.js`/`task-center.js`/`cards.js` untouched.

### Track B — v2 major (O⇢S, Opus-vet ⚑)
- **O designs** DOM + state for the segmented scope control (`All · Unsorted · Classic`), per-scope
  counts, and row pill; **S implements** across `modules/v2/unprocessed/queue.js`, `index.js`,
  `css/workbench.css`; **O vets**.
- Wire scope → `?volume=` (or client-side filter of the volume-tagged rows) + counts from row set.

**Gate G3:** both UIs render correctly — sandbox static-server + Puppeteer screenshot verify
(running server serves stale classpath until restart).

---

## Phase 4 — Integration & live verify (O + user; needs G0)
1. Rebuild (`installDist`) + **user** restart.
2. Against real `classic_fresh` titles: list + scope/filter in both UIs; run one **enrich → draft →
   promote** end-to-end; confirm `curated_at` stamped on the `classic_fresh` location and the folder
   renamed **on the classic tree** (correct NAS share); cover written under `classic/fresh`.
3. Full test suite; **advisor** final review; merge to `main`.

---

## Parallelism summary
- Phase 0 ∥ Phase 1 (coding independent of live data).
- Within 1.1: 1.1a ∥ 1.1f. Within 1.2: 1.2a ∥ 1.2b ∥ 1.2c. Tests (1.3) ∥ throughout.
- Phase 3 Track A ∥ Track B.
- Serial gates: 1.0 → 1.1 → 1.2 → **G1** → Phase 2 → **G2** → Phase 3 → **G3** → Phase 4.

## Opus-reserved (not delegated)
`Application.java` wiring (1.0) · `DraftPromotionService` SMB targets (1.2b) · v2 major UX design
(3B design) · all ⚑ vets · Phase 4 integration.
