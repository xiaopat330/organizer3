# Proposal: Testing Consistency

## Background

On 2026-04-23 the Health tab "Clean up" action for stale locations deleted all `title_locations`
rows across five volumes (bg, a, hj, k, m) — wiping months of sync data. The root cause was a
SQLite type-mismatch bug in the SQL predicate:

```sql
-- Buggy: SQLite string comparison '2026-04-23' < '2026-04-23T10:14:37' evaluates TRUE
-- because the date string is a prefix of the datetime string (lexicographic prefix match)
AND tl.last_seen_at < v.last_synced_at

-- Fixed: DATE() normalizes both sides to date-only before comparison
AND tl.last_seen_at < DATE(v.last_synced_at)
```

The predicate existed in **3 files / 4 SQL locations** (`StaleLocationsService`,
`StaleLocationsCheck`, `FindStaleLocationsTool`) with **zero test coverage**. A single
regression test with a same-day datetime `last_synced_at` would have caught it on day one.

The broader audit revealed that all 6 `LibraryHealthCheck` implementations have no tests,
and no systematic rule exists requiring tests for destructive operations.

---

## Proposed Rules

### Rule 1 — SQL predicates with type-sensitive comparisons get a regression test

Any query that compares columns of different storage types (date vs datetime, string vs int,
etc.) must have at least one test that would fail if the comparison were naive.

**Canonical test pattern for date vs datetime:**

```java
// last_synced_at stored as datetime; last_seen_at stored as date.
// Without DATE() wrapping, '2026-04-23' < '2026-04-23T10:14:37' evaluates TRUE in SQLite.
jdbi.useHandle(h -> h.execute(
    "INSERT INTO volumes (id, structure_type, last_synced_at) VALUES ('vol', 'conventional', '2024-06-01T10:14:37')"));
locationRepo.save(... .lastSeenAt(LocalDate.of(2024, 6, 1)) ...); // same day as sync

// Must be zero — same-day locations are not stale
assertEquals(0, service.count("vol"));
```

### Rule 2 — Health checks are not exempt from unit tests

`LibraryHealthCheck` implementations contain SQL and data logic. They follow the same rule as
any other service: if it touches the DB, it has tests.

**Coverage gap as of 2026-04-24 (all 6 untested):**

| Check | SQL complexity | Priority |
|---|---|---|
| `StaleLocationsCheck` | Date vs datetime comparison — same bug class | **1 — highest** |
| `DuplicateCodesCheck` | Aggregation with `HAVING` | 2 |
| `UnresolvedAliasesCheck` | `LEFT JOIN` orphan detection | 3 |
| `TitlesWithoutCoversCheck` | Repository walk + filesystem probe | 4 |
| `UnloadedYamlsCheck` | Classpath enumeration + DB probe | 5 |
| `OrphanedCoversCheck` | Thin wrapper over `OrphanedCoversService` (already tested) | 6 |

### Rule 3 — Destructive operations require a false-positive guard test

For any method that deletes rows, the **first** test written must seed plausible data that
should **not** be deleted and assert it survives. A test that only verifies correct deletion is
insufficient — it doesn't verify the predicate boundary.

```
For StaleLocationsService.delete():
  ✓ deletesLocationsOlderThanSyncDay         ← happy path (deletion works)
  ✓ doesNotDeleteLocationsSeenOnSameDayAsSync ← false-positive guard (boundary)
  ✓ skipsVolumesNeverSynced                  ← edge case (null last_synced_at)
```

The false-positive guard is the most important of the three. Write it first.

---

## Implementation Plan

### Phase 1 — Health check unit tests (priority order)

1. `StaleLocationsCheckTest` — same-day datetime guard, per-volume breakdown, never-synced
2. `DuplicateCodesCheckTest` — no dupes (zero), intra-volume dupe detected, cross-volume not flagged
3. `UnresolvedAliasesCheckTest` — no orphans, dangling actress_id detected, resolved alias not flagged
4. `TitlesWithoutCoversCheckTest` — requires `CoverPath` mock; cover present vs missing
5. `UnloadedYamlsCheckTest` — requires `ActressYamlLoader` stub
6. `OrphanedCoversCheckTest` — delegates to `OrphanedCoversService`; thin wrapper test

All use real in-memory SQLite via `SchemaInitializer`, matching the existing test convention.

### Phase 2 — Audit remaining SQL for type-sensitive comparisons

Grep all SQL strings in `src/main/java` for comparisons involving `last_seen_at`,
`last_synced_at`, or any other column where the Java type (LocalDate vs LocalDateTime) differs
from the storage type. Add regression tests for each hit.

### Phase 3 — `FixTimestampsVolumeService` unit test

Currently only has a sandbox test (requires VPN + real NAS). Extract the pure-DB logic into a
unit-testable layer so CI can gate it without network access.

---

## Acceptance Criteria

- All 6 `LibraryHealthCheck` implementations have at least 3 tests each (happy path,
  false-positive guard, edge case).
- `StaleLocationsCheck` specifically includes a same-day datetime regression test.
- Any new destructive operation must include a false-positive guard test before merge.
- `FixTimestampsVolumeService` has at least one unit test not requiring the sandbox.
