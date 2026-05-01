# Proposal: Mirror YAML alternate names into `actress_aliases`

Status: **Draft** — capture the idea, revisit later.
Originating context: AIKA (actress 107) cast-anomaly bug, 2026-05-01. The matcher fix
(`canonical_name` + NFKC + case-fold) handled the immediate corner case; this proposal
covers the structural follow-up.

## Problem

YAML profiles store alternate names in `profile.name.alternate_names[]`, which the
loader writes to `actresses.alternate_names_json`. That column is **read-only metadata**
— a few tools (`FindEnrichmentCastMismatchesTool`, `EnrichmentClearMismatchedTask`)
consult it, but the operational matchers don't:

- `CastMatcher` (write-time gate, recovery) reads `canonical_name` + `stage_name` +
  `actress_aliases`.
- `EnrichmentRunner.buildKnownNames` (slug recording) — same set.
- Sync resolver — `actress_aliases` only.

Result: the YAML can declare an alternate name and the matcher never learns about it.
For AIKA the workaround was a one-off `set_actress_aliases` call. Each future romaji-only
or rename-style actress will hit the same workflow.

## Proposed change

Treat YAML as the source of truth for aliases. On `load actress`, the loader should
**mirror** entries from `profile.name.alternate_names` into `actress_aliases` (additive,
idempotent). Stage name and canonical name stay where they are.

Open questions for the revisit:

1. **Scope of mirroring.** Just `alternate_names[].name`, or also the implied "given_name
   stage_name" / "stage_name given_name" romaji pair if not already aliased? My instinct:
   start with `alternate_names[]` only — narrow and explicit.
2. **Removal semantics.** If an alias is deleted from YAML on a re-load, do we delete the
   row from `actress_aliases`? Two options:
   - **Strict mirror** — YAML is authoritative; deletes propagate. Risk: hand-curated
     aliases added via `set_actress_aliases` get blown away on next load.
   - **Additive only** — YAML loader inserts but never deletes; manual aliases survive.
     Safer, but YAML can drift from DB.
   Probably additive-only with a separate `prune_yaml_aliases` utility if needed.
3. **Conflict handling.** What if the alias collides with another actress's
   canonical/stage/alias name? `actress_aliases.alias_name` has an index but no unique
   constraint. The existing `find_alias_conflicts` tool already surfaces these — likely
   safe to insert and let the conflict checker flag.
4. **Backfill.** A one-shot pass on existing YAML-loaded actresses to populate aliases
   from their stored `alternate_names_json`. Cheap, idempotent.

## Backwards-compat / migration

No schema change. Pure write-side behavior. The existing matcher fix already handles
case/canonical-name issues; this is additive coverage for genuine alias data that lives
in YAML.

## Out of scope

- Migrating `alternate_names_json` away (still useful as the structured form with `note`).
- Sync-time alias normalization (already works through `actress_aliases`).
- Promoting AIKA's stage_name in the YAML (separate concern — orthogonal to the loader
  change).

## Test plan sketch

- Unit test on `ActressYamlLoader`: load a YAML with two `alternate_names`, assert both
  appear in `actress_aliases` after load.
- Re-load same YAML: no duplicate rows.
- Re-load YAML with one alternate removed: under additive-only semantics, the removed
  alias remains in DB.
- Conflict case: alternate matches another actress's canonical_name → row inserted, but
  `find_alias_conflicts` reports it.
