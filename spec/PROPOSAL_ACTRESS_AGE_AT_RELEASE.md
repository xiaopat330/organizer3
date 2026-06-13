# PROPOSAL: Actress Age-at-Release Query Filter

**Status:** Draft 2026-06-12 — planning only, no implementation yet.
**Origin:** Design discussion on querying titles by the age of the credited actress at the title's release date. Age is currently a derivable-but-nowhere-materialized fact: `actresses.date_of_birth` and release dates (`title_javdb_enrichment.release_date` / `titles.release_date`) both exist, but no query surface combines them.

---

## 1. Goal & Scope

Enable backend title queries filtered by **the age of the credited actress at the title's release date**, as a single value or a range, composable with the existing browse filters (code, company, tags, enrichment tags).

**In scope:**
- A denormalized `age_at_release` column on `title_actresses` (the credit, not the title).
- Seeding via schema migration + a reusable global recompute.
- `ageMin`/`ageMax` filter threaded through the browse pipeline (`TitleRoutes` → `TitleBrowseService` → `findLibraryPaged`), with a `castMode` parameter: **`solo`** (default — solo-cast titles only), **`any`** (some credited actress in range), **`all`** (every credited actress in range).
- Tests per house rules (real in-memory SQLite for repository/migration, Mockito elsewhere).

**Out of scope (for now):**
- Any UI surface (v1 or v2).
- Year-only DOB support (§7).
- Sort-by-age (trivially possible later; not requested).

---

## 2. Data situation (measured 2026-06-12, live DB)

| Population | Count |
|---|---|
| Titles total | 57,157 |
| `title_actresses` credit rows | 64,080 |
| Titles with `title_javdb_enrichment.release_date` | 13,006 |
| Titles with `titles.release_date` | 10,054 |
| Actresses with `date_of_birth` | 555 (all full `YYYY-MM-DD`; zero year-only) |
| **Computable credits** (DOB + release date) | **14,278** |
| …of which on multi-cast titles | 4,042 (~28%) |
| Solo-cast titles (exactly 1 credit row) | 53,805 |
| **Eligible solo titles** (solo + release date + DOB) | **10,236** |

Both inputs are actively growing: enrichment promotion adds release dates; actress-profile campaigns add DOBs in batches (POPULAR campaign alone loaded ~185 profiles).

Full-library compute cost is negligible: the complete age filter (joins + solo check + `BETWEEN`) over all 57k titles runs in **~46ms including sqlite3 process startup**. The denorm column is therefore justified by **query simplicity and ad-hoc accessibility**, not performance.

---

## 3. Design

### (a) Schema: `title_actresses.age_at_release` — the credit owns the age

Age-at-release is a property of the **(title, actress) pair**, not the title. A single field on `titles` cannot represent multi-cast titles (whose age?), and populating it "solo titles only" would permanently conflate *multi-cast* with *missing data*. Placing the column on the junction table:

- behaves identically to a titles-level field for solo titles (exactly one row);
- gives every credit on a multi-cast title its own age — the 4,042 multi-cast data points are kept, not discarded;
- directly powers the `any`/`all` cast modes (§3e) — multi-cast queries are query shapes, not schema changes;
- `NULL` cleanly means "not computable yet" (missing DOB or release date) and is re-derivable at any time.

```sql
ALTER TABLE title_actresses ADD COLUMN age_at_release INTEGER;  -- NULL = not computable
```

No index initially — at 64k rows with the access patterns below, a scan is sub-millisecond territory. Add one later only if a sort-by-age surface appears.

### (b) Age computation — exact integer-date arithmetic

SQLite's integer-date trick gives true birthday-aware age in years, with no leap-year or julian-day fuzz:

```sql
(CAST(strftime('%Y%m%d', <release_date>) AS INTEGER)
 - CAST(strftime('%Y%m%d', <date_of_birth>) AS INTEGER)) / 10000
```

**Release-date precedence:** `COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,''))` — the javdb enrichment date is canonical; `titles.release_date` is the fallback. This matches the rule "title is enriched, or minimally has a non-null release date (not create date)".

### (c) Global recompute — one statement is both seed and repair

A single idempotent **full re-derivation** (not a fill-the-blanks pass):

```sql
UPDATE title_actresses
SET age_at_release = (
    SELECT CASE
        WHEN a.date_of_birth IS NULL OR a.date_of_birth = '' THEN NULL
        WHEN COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,'')) IS NULL THEN NULL
        ELSE (CAST(strftime('%Y%m%d', COALESCE(NULLIF(e.release_date,''), NULLIF(t.release_date,''))) AS INTEGER)
            - CAST(strftime('%Y%m%d', a.date_of_birth) AS INTEGER)) / 10000
    END
    FROM titles t
    JOIN actresses a ON a.id = title_actresses.actress_id
    LEFT JOIN title_javdb_enrichment e ON e.title_id = t.id
    WHERE t.id = title_actresses.title_id
)
```

(Shape illustrative; the implementation must guarantee the **NULL-out** behavior: rows that lose a prerequisite — DOB removed, credit reassigned, bad enrichment evicted — get cleared, so corrections self-heal rather than leaving stale ages.)

Properties that make this the maintenance strategy, not just the seed:
- **Idempotent** — safe to run any number of times.
- **Fast** — equivalent full-library SELECT measured at ~46ms; the UPDATE is expected well under 1s.
- **Self-healing** — recomputes *and clears*; a stale value never survives a run.

Because the global pass is essentially free, **no per-mutation surgical hooks are needed**. This matters: the invalidation surface is wide and includes paths Java hooks can't see (direct sqlite surgery during triage sessions, `reassign_title_credit`, `merge_actresses`, DOB corrections — cf. the Arisu Miyuki split, which was *proven by* a DOB fix). Trying to hook every mutation site would be fragile; recompute-the-world is robust.

### (d) Recompute trigger points

Encapsulate the statement in a small service (e.g., `AgeAtReleaseRecomputer`, wired manually in `Application.java` per the no-Spring rule) and invoke it from the few places where inputs arrive in bulk:

1. **`applyV69` migration** — the seed (§4).
2. **App startup** — run after schema upgrade on every boot. Sub-second cost; self-heals after direct-sqlite surgery sessions without anyone remembering to call the repair tool.
3. **`ActressYamlLoader` / `load actresses`** — after a profile load; YAML batch loads are when DOBs arrive in volume. (After the batch, not per-actress.)
4. **Draft promotion** (`DraftPromotionService`) — enrichment release dates and new credits land here. Per-promotion is acceptable given <1s cost; batching is an optimization, not a requirement.
5. **`merge_actresses` / `reassign_title_credit` / `remove_title_credit`** — credit identity changes.
6. **Manual repair**: an MCP tool / Utilities operation `recompute_age_at_release` reporting (a) changed-row count and (b) an **implausible-age triage list** (e.g. `age_at_release < 18 OR > 70`) — outliers almost always mean a wrong DOB or a code-reuse mis-enrichment (cf. the `force_enrich` code-reuse trap), so the recompute doubles as a misattribution detector. Compute honestly; report, never suppress. Utilities atomicity rules apply (single task slot).

**Accepted trade-off:** between an out-of-band edit and the next trigger, the column can briefly disagree with the underlying dates. Acceptable given the repair tool and the read-mostly usage; alternative (compute-at-query, no column) was considered and rejected in §6.

### (e) Browse filter — `castMode` = `solo` | `any` | `all`, AND-composable

`findLibraryPaged` (`JdbiTitleRepository`) gains the age params plus a cast-mode discriminator. When an age filter is present, append one of three predicate shapes (all EXISTS-based against the junction table — no join multiplication, no GROUP BY interference with the existing tag HAVING logic):

**`solo` (default)** — the title has exactly one credit, and it is in range:
```sql
WHERE EXISTS (SELECT 1 FROM title_actresses ta
              WHERE ta.title_id = t.id AND ta.age_at_release BETWEEN :ageMin AND :ageMax)
  AND NOT EXISTS (SELECT 1 FROM title_actresses ta1, title_actresses ta2
                  WHERE ta1.title_id = t.id AND ta2.title_id = t.id
                    AND ta1.actress_id <> ta2.actress_id)
```

**`any`** — at least one credited actress is in range (multi-cast included):
```sql
WHERE EXISTS (SELECT 1 FROM title_actresses ta
              WHERE ta.title_id = t.id AND ta.age_at_release BETWEEN :ageMin AND :ageMax)
```

**`all`** — every credited actress is in range, **strict NULL semantics**: a credit with unknown age fails the title. (The loose alternative — "everyone we *know about* is in range" — silently passes a 4-cast title with 3 unknown ages; rejected as the default. Could become a fourth mode later if ever needed.) A title must also have at least one credit:
```sql
WHERE EXISTS (SELECT 1 FROM title_actresses ta WHERE ta.title_id = t.id)
  AND NOT EXISTS (SELECT 1 FROM title_actresses ta
                  WHERE ta.title_id = t.id
                    AND (ta.age_at_release NOT BETWEEN :ageMin AND :ageMax
                         OR ta.age_at_release IS NULL))
```

- **Solo definition:** exactly one row in `title_actresses`. Deliberately NOT `titles.actress_id` — that is the *filing* actress (folder owner), set even on multi-cast titles; it encodes an organizational choice, not "main cast".
- **"Actress X was 22, including multicast"** needs no dedicated mode: it is `castMode=any` combined with an actress filter — her own credit row carries her age, so the EXISTS can be scoped to `ta.actress_id = :actressId` when an actress filter is active. (Browse currently has no actress query param; if one is added later, the predicates compose directly.)
- Single-value query = `ageMin == ageMax`.
- Eligibility is implicit: credits missing DOB/release date have `age_at_release IS NULL` and fall out of `BETWEEN` (solo/any) or fail the title (all). No flags needed.
- Composes via AND with the existing dynamic-SQL filters (code prefix, company/label, tags with HAVING-count semantics, enrichment tags), following the established builder pattern at `JdbiTitleRepository.findLibraryPaged` (~line 1537).

### (f) API plumbing

- `TitleRoutes` browse endpoint (`/api/titles/browse`): parse optional `ageMin` / `ageMax` integer query params and optional `castMode` (`solo`|`any`|`all`, default `solo`; reject unknown values, negative ages, or min>max with 400). `castMode` without an age filter is silently ignored (no-op), consistent with other browse params.
- `TitleBrowseService.findLibraryPaged(...)`: thread two nullable `Integer`s plus a `CastMode` enum through.
- **Response shape:** `TitleSummary` gains a nullable `ageAtRelease` field, populated **for solo titles only** (the single unambiguous value; multi-cast titles return null — per-cast ages in the response are a future-UI concern). Populated whenever computable, not just when the filter is active, so results always show why they matched and future UI gets it for free.
- `TitleRepository` interface: extend the `findLibraryPaged` signature.
- No UI changes in this proposal; MCP `sql_query` users additionally get direct access to the materialized column for free (a stated motivation for denormalizing).

---

## 4. Migration: `applyV69`

Standard `SchemaUpgrader` pattern (`CURRENT_VERSION` 68 → 69):

1. `addColumnIfMissing("title_actresses", "age_at_release", "INTEGER")`.
2. Run the §3c re-derivation UPDATE as the backfill — seeds all 14,278 currently-computable credits on first startup.
3. Idempotent by construction (column guard + re-derivation semantics).

---

## 5. Testing

Per house rules: repository and migration tests on **real in-memory SQLite**; service/route tests with Mockito.

**Recomputer:**
- Birthday boundary: released the day before the birthday vs. on it (off-by-one is the classic age bug; the integer trick must be verified at the boundary, including Feb-29 DOBs).
- Date precedence: enrichment date wins over `titles.release_date`; fallback works when enrichment row absent/empty.
- NULL-out: a previously computed row loses its DOB (or release date) → recompute clears it.
- Multi-cast: every credit on a multi-cast title gets its own (different) age.
- Empty-string dates treated as NULL (both fields are TEXT and `''` occurs in the wild).

**Migration:** fresh DB + pre-seeded fixture → column exists, computable rows seeded, non-computable rows NULL; re-run is a no-op.

**Browse filter:**
- Range and single-value matching; boundary inclusivity.
- `solo`: multi-cast title with an in-range credit is excluded; zero-credit titles excluded.
- `any`: multi-cast title matches when exactly one of N credits is in range; still matches when other credits have NULL ages.
- `all`: every credit in range → match; one credit out of range → no match; one credit NULL → no match (strict semantics); zero-credit titles excluded.
- Missing-data exclusion (NULL ages never satisfy a range).
- Composition: age + tag filter, age + company filter return the intersection; castMode doesn't disturb tag HAVING-count semantics.
- Param validation at the route (min>max, non-numeric, unknown castMode); castMode without age params is a no-op.
- `TitleSummary.ageAtRelease`: populated for solo titles with computable age; null for multi-cast and non-computable.

**Repair tool:** changed-row count reported; implausible-age rows (<18 / >70) returned as a triage list.

---

## 6. Alternatives considered

1. **Compute-at-query, no column (+ optional SQL view).** Always correct by construction; measured fast enough (~46ms full-library). Rejected in favor of the denorm column because: the materialized value keeps the already-gnarly dynamic SQL in `findLibraryPaged` simple; it is directly usable in ad-hoc sqlite/`sql_query` sessions; and seeding+repair being a single sub-second statement neutralizes most of the staleness objection. A `title_actress_age` **view** remains a reasonable companion if ad-hoc users want guaranteed-fresh values, but is not required.
2. **`titles.age_at_release` (title-level field).** Serves the solo-only ask, but cannot represent multi-cast titles, conflates "multi-cast" with "unknown", and forces a second migration the day multi-cast queries matter. Rejected; the junction column costs the same and ends where this feature ends up anyway.
3. **Min/max age pair on `titles`.** Handles multi-cast ranges but loses per-actress attribution and doubles the denorm surface. Rejected as strictly worse than the junction column.
4. **Per-mutation recompute hooks (surgical).** Rejected: the mutation surface includes direct-sqlite edits invisible to Java; global recompute is cheap enough to make precision pointless.

---

## 7. Open questions / deferred

- **Year-only DOB.** `actresses.date_of_birth` is a full-date TEXT parsed to `LocalDate`; year-only is currently unrepresentable and zero rows need it. If profile campaigns later want it (e.g., a `birth_year` column or relaxed parsing), age degrades to ±1-year precision for those rows — a separate small feature. Deferred.
- **Loose ALL mode** ("everyone we know about is in range" — NULL ages don't fail the title). Strict is the shipped default (§3e); add a fourth mode only if a real query needs it.
- **Actress-scoped age query in browse.** `castMode=any` + an actress filter covers "X was 22 incl. multicast", but browse has no actress param today; adding one is a separate (small) feature. Until then this query is served by `sql_query` against the materialized column.
- **Standing health check.** The repair tool's outlier triage list (§3d) covers on-demand detection; promoting it into the V68 `attribution_findings` standing-check pattern (automatic, persisted findings) is a possible follow-up once outlier volume is known.
- **Per-cast ages in the browse response.** `TitleSummary.ageAtRelease` is solo-only (§3f); exposing the full per-credit age list for multi-cast titles is deferred until a UI surface wants it.
