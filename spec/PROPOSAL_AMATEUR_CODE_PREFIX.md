# PROPOSAL — Amateur numeric-prefix codes (259LUXU-605 → stripped LUXU-605)

**Status:** DRAFT / scoping — no implementation
**Date:** 2026-07-09
**Origin:** deferred follow-up from the classic_fresh multi-volume work (91/161 classic titles excluded)

## 1. Problem

`TitleCodeParser`'s label regex `([A-Za-z][A-Za-z0-9]{0,9})-([A-Za-z]?)(\d{2,})` requires the
label to **start with a letter**, so for amateur/distribution codes it silently drops the leading
numeric distributor prefix:

| Folder on disk | Parsed `code` / `base_code` | Real code |
|---|---|---|
| `(259LUXU-605)` | `LUXU-605` / `LUXU-00605` | `259LUXU-605` |
| `(300MIUM-963)` | `MIUM-963` | `300MIUM-963` |
| `(200GANA-2983)` | `GANA-2983` | `200GANA-2983` |
| `(789ECH-004)` | `ECH-004` | `789ECH-004` |

## 2. Impact — ~349 titles library-wide

| Volume | Titles | Enriched |
|---|---|---|
| pool | 142 | 70 |
| classic_fresh | 91 | 1 |
| classic_pool | 52 | 6 |
| unsorted | 45 | 0 |
| library (hj/ma/m/s/r/n/k/a) + collections | ~19 | 0 |

Three downstream effects, in priority order:

1. **Excluded from the Unprocessed queue.** The eligibility self-check
   `tl.path LIKE '%(' || t.code || ')%'` fails because the folder holds `(259LUXU-605)` but
   `t.code` is `LUXU-605`. (User-visible symptom.)
2. **Folder-rename corruption on curation (latent).** Both the promote path
   (`DraftPromotionService:395` → `renamePreservingDescriptor(…, t.code)`) and the reconciler pass
   the stripped `t.code`. `TitleFolderRenamer` rebuilds the folder as `Name (code)` and
   `extractDescriptor` keys on ` (code)` — so promoting `(259LUXU-605)` would rename to
   `Name (LUXU-605)`, **dropping the prefix AND any descriptor**. Not yet triggered (only
   non-amateur titles have been promoted).
3. **Enrichment — NOT broken.** Verified from live data: the code-search fallback self-heals via
   fuzzy match — `300MIUM-1043` → Prestige Premium / correct cast, `200GANA-1352` → Nampa TV /
   森川アンナ (matches the folder's "Anna Morikawa"), `261ARA-186` → ARA. The stripped code is a
   **working, unique key**: 0 stripped base_codes back >1 distinct title (no prefix collisions).

**Key reframing:** the memory note justified this item partly on "breaks enrichment" — that is
falsified. The stored stripped code *works*. Only effects (1) and (2) are real, and both stem from
trusting `t.code` instead of the folder's actual parenthesised code.

## 3. Option A — Light: make eligibility + rename prefix-aware (RECOMMENDED)

Leave the stored code/base_code/label alone. Fix the two operations that wrongly rebuild from
`t.code`:

- **Eligibility** (`JdbiUnsortedEditorRepository.listEligible`): relax the self-check from
  `path LIKE '%(' || t.code || ')%'` to `path LIKE '%' || t.code || ')%'` — tolerates a numeric
  prefix. It is a self-join (a title's own folder vs its own code), so there is no false-positive
  risk.
- **Rename** (`TitleFolderRenamer`): derive the code token from the **current folder's trailing
  parens** (regex `\(([^()]*)\)$` on the basename it already resolves) and use that for both
  descriptor extraction and target rebuild, instead of the passed `t.code`. This fixes prefix AND
  descriptor preservation, for BOTH the promote and no-draft-save paths, in one place. The `code`
  param becomes a fallback for folders with no parens code.

**Wins:** unblocks all ~349 titles into the queue and curates them without corruption. **No parser
change, no data migration, no cover-label churn, zero enrichment-regression risk.** Deletes the
`259LUXU`-vs-`LUXU` label question entirely (it only exists under Option B).

**Blast radius:** two files (`JdbiUnsortedEditorRepository`, `TitleFolderRenamer`) + tests. The
rename change touches shared code used by every staging volume, so it needs the existing
folder-rename test suite green + a couple amateur-code cases (prefix + descriptor preserved).

## 4. Option B — Heavy: store the full code (parser + migration) — likely NOT worth it

Change the parser to keep an optional leading numeric prefix (`\d{0,4}` before the label) so
`code=259LUXU-605`, `base_code=259LUXU-00605`, then migrate the ~349 existing rows in place (by
`title_id`, to avoid duplicate-title creation since `base_code` is the match key).

**Costs:** parser change affects ALL volumes' sync; in-place data migration of code/base_code/label;
cover-cache relabel (`covers/LUXU/` → `covers/259LUXU/`); a label-semantics decision
(`259LUXU` vs `LUXU`).

**Justification is thin:** enrichment already self-heals with the stripped code, and there are 0
prefix collisions, so the stored code is already a correct unique key. The only gain is on-disk
code fidelity — which Option A's rename fix already delivers on the folder without touching stored
data.

**Decider (only if considering B):** re-resolve one amateur title and read the resolver's
`matched code …` log line. If javdb matches the **stripped** form, B would actively **regress**
future enrichment → do not do B. If javdb matches the **full** form, B has a marginal correctness
benefit at real cost. Either way, Option A is safe and sufficient.

## 5. Recommendation

Do **Option A**. It resolves both real symptoms with a tiny, low-risk change and no data migration.
Treat Option B as unlikely-to-be-needed; revisit only if a concrete need for full-form stored codes
appears (and gate it on the `matched code` log check).

## 6. Plan (Option A)

1. **Wave 1 (Sonnet, Opus-vet — SMB rename path):** `TitleFolderRenamer` derives the code from the
   current folder's trailing parens for target + descriptor; fallback to the passed code. Extend
   `TitleFolderRenamerTest` with amateur-prefix + descriptor-preservation cases.
2. **Wave 2 (Sonnet):** relax the `listEligible` path-code check; add a `JdbiUnsortedEditorRepository`
   test asserting a `(259LUXU-605)`/`LUXU-605` row is eligible.
3. **Gate:** backend suite green; advisor-vet the rename diff (same wrong-share/rename-corruption
   class as the multi-volume work).
4. **Live verify:** curate one amateur `classic_fresh` title (e.g. a `259LUXU` / `300MIUM`) through
   promote; confirm it appears in the queue and the folder keeps its full code + descriptor on the
   `classic/fresh` share.
