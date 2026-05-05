# Coverage Baseline + Test Failure Triage — 2026-05-04

## Test run summary
- Build status: FAILED (test failures only; JaCoCo exec data collected)
- Tests run: 3370, passed: 3341, failed: 29, skipped: 1
- Wall time: 16 minutes 35 seconds

---

## Test failure triage

### Real bugs (NOT transient — will reproduce regardless of network)

**SchemaInitializerTest (3 failures)**
- `createsAllTables` — tests expect 40 tables but actual schema creates 44; new tables
  `translation_queue`, `translation_cache`, `translation_strategy`, `stage_name_lookup`,
  `stage_name_suggestion` were added but the hardcoded expected list was not updated.
- `createsExpectedIndexes` — same root cause: new indexes
  `idx_tq_status`, `idx_snl_kanji`, `idx_sns_kanji`, `idx_sns_unreviewed`, `idx_tc_strategy`
  exist in the live schema but are absent from the test's expected list.
- `freshSchemaIsStampedAtCurrentVersion` — test asserts version=46, but
  `SchemaUpgrader.CURRENT_VERSION` is now 50. Test hard-codes a stale constant.

**SchemaUpgraderTest (16 failures)**
All 16 failures share the same message: `expected: <46> but was: <50>`.
Root cause: every test that checks the final schema version asserts `46`, but
`SchemaUpgrader.CURRENT_VERSION` has advanced to `50` (schema versions 47–50 were
shipped after these tests were written). Tests need their expected-version constant updated.

**DraftSchemaMigrationTest (4 failures)**
All 4 failures: `expected: <45> but was: <50>`.
Root cause: same pattern — these tests assert the schema lands at v45, but the upgrader
continues to v50. The `DraftSchemaMigrationTest` has its own stale version constant (45).

**ActressYamlLoaderRealDataTest — NanaOgura, SoraAoi, YumaAsami (3 failures)**
All 3: `alternate_names should not be added as aliases ==> expected: <false> but was: <true>`.
Root cause: `ActressYamlLoader` is inserting `profile.alternate_names` values into the
`actress_aliases` table instead of (or in addition to) keeping them purely in the profile
record. The loader logic for `alternate_names` vs `aliases` appears to have regressed.

**TranslationQueueRepositoryTest (1 failure)**
- `claimNext_atomicClaim_onlyOneThreadWins` — `TransactionException: Failed to commit
  transaction` caused by `java.sql.SQLException: database in auto-commit mode`. The
  concurrent test uses two threads sharing an in-memory SQLite connection; SQLite does not
  support concurrent write transactions from multiple threads on the same in-process DB
  instance. This is a test-design bug: the test uses a shared SQLite handle in
  multi-threaded mode without proper per-thread connection setup.

**WebServerTest (1 failure)**
- `watchHistoryPostEndpointRecordsAndReturnsEntry` — test passes `titleRepo = null` to
  `WebServer` constructor. The route handler now calls `titleRepo.findByCode(titleCode)`
  before delegating to `watchRepo.record()` (added as part of a 404-guard). With a null
  `titleRepo`, the handler throws NPE → 500. The test was not updated when the null-guard
  was added.

### NAS/SMB-dependent (likely transient)
*None.* All 29 failures are reproducible assertion failures unrelated to network state.

### Test infrastructure
*None* (the TranslationQueueRepositoryTest concurrent-transaction failure is categorized as
a real bug because it will always fail — SQLite in-memory + multi-thread is inherently
broken on that test, not a flap).

---

## Summary by class

| Class | Failures | Root cause |
|---|---|---|
| `SchemaUpgraderTest` | 16 | Stale version constant: tests assert v46, upgrader is at v50 |
| `SchemaInitializerTest` | 3 | Tests assert stale table/index set and version 46; schema now at v50 |
| `DraftSchemaMigrationTest` | 4 | Tests assert v45; upgrader continues to v50 |
| `ActressYamlLoaderRealDataTest` (3 nested) | 3 | `alternate_names` written to aliases table instead of profile only |
| `TranslationQueueRepositoryTest` | 1 | Multi-thread SQLite auto-commit conflict — test design bug |
| `WebServerTest` | 1 | `watchHistoryPost` test passes null titleRepo; route now calls titleRepo.findByCode() |

---

## Coverage overall

| Counter | Covered | Total | % | vs April 2026 |
|---|---|---|---|---|
| **INSTRUCTION** | 94,969 | 139,233 | **68.2%** | +4.2 pp (was ~64%) |
| BRANCH | 5,994 | 10,496 | 57.1% | — |
| LINE | 18,696 | 27,474 | 68.0% | — |
| COMPLEXITY | 5,803 | 10,548 | 55.0% | — |
| METHOD | 3,623 | 5,255 | 68.9% | — |
| CLASS | 792 | 964 | 82.2% | — |

Coverage improved by ~4 pp vs the April baseline. No package dropped significantly.

---

## Top 20 packages by missed instructions

| Package | Missed | Covered | Total | % |
|---|---|---|---|---|
| `com.organizer3.mcp.tools` | 7,418 | 12,284 | 19,702 | 62.3% |
| `com.organizer3.web.routes` | 4,843 | 7,872 | 12,715 | 61.9% |
| `com.organizer3.command` | 3,800 | 5,674 | 9,474 | 59.9% |
| `com.organizer3.web` | 3,503 | 9,878 | 13,381 | 73.8% |
| `com.organizer3` (Application) | 3,319 | 0 | 3,319 | 0.0% |
| `com.organizer3.repository.jdbi` | 2,250 | 7,771 | 10,021 | 77.5% |
| `com.organizer3.javdb.enrichment` | 1,600 | 9,166 | 10,766 | 85.1% |
| `com.organizer3.smb` | 1,598 | 350 | 1,948 | 18.0% |
| `com.organizer3.media` | 1,390 | 1,373 | 2,763 | 49.7% |
| `com.organizer3.shell.io` | 1,091 | 42 | 1,133 | 3.7% |
| `com.organizer3.db` | 963 | 2,673 | 3,636 | 73.5% |
| `com.organizer3.organize` | 955 | 4,073 | 5,028 | 81.0% |
| `com.organizer3.trash` | 923 | 557 | 1,480 | 37.6% |
| `com.organizer3.avstars.repository.jdbi` | 855 | 1,708 | 2,563 | 66.6% |
| `com.organizer3.translation` | 770 | 2,637 | 3,407 | 77.4% |
| `com.organizer3.translation.ollama` | 616 | 49 | 665 | 7.4% |
| `com.organizer3.utilities.task.avstars` | 582 | 0 | 582 | 0.0% |
| `com.organizer3.avstars.iafd` | 565 | 1,370 | 1,935 | 70.8% |
| `com.organizer3.avstars.sync` | 512 | 605 | 1,117 | 54.2% |
| `com.organizer3.avstars.command` | 507 | 2,863 | 3,370 | 85.0% |

---

## Top 15 untested / low-coverage classes

| Class | Missed Instructions | Coverage % |
|---|---|---|
| `Application` | 3,319 | 0% |
| `JdbiTitleRepository` | 1,683 | 59.2% |
| `WebServer` | 1,095 | 32.5% |
| `TrashService` | 780 | 0.5% |
| `SchemaUpgrader` | 774 | 63.6% |
| `SmbFileSystem` | 638 | 0% |
| `UtilitiesRoutes` | 613 | 71.2% |
| `HttpOllamaAdapter` | 609 | 0% |
| `UnsortedEditorRoutes` | 608 | 0% |
| `DraftRoutes` | 572 | 68.5% |
| `ThumbnailService` | 531 | 25.1% |
| `EnrichmentRunner` | 526 | 73.7% |
| `TrashRoutes` | 521 | 0% |
| `UnsortedEditorService` | 464 | 62.4% |
| `TitleTagEditorRoutes` | 452 | 0% |

---

## Notable changes vs April 2026

- **+4.2 pp overall** (68.2% vs ~64%): reflects translation pipeline, stage-name lookup,
  draft mode, and enrichment hardening code added since April — and tests written alongside
  them.
- `com.organizer3.translation` package (new): 77.4% covered.
- `com.organizer3.javdb.draft` (new): 91.3% covered — very well tested.
- No package regressed more than 5 pp from baseline.

---

## Recommended next test-coverage targets

1. **`com.organizer3.mcp.tools` (7,418 missed, 62.3%)** — largest absolute gap. Many MCP
   tool classes have zero or near-zero test coverage. High value: these are the primary API
   surface consumed by agents.

2. **`com.organizer3.web.routes` (4,843 missed, 61.9%)** — `TrashRoutes`,
   `UnsortedEditorRoutes`, `TitleTagEditorRoutes` are entirely untested (0%). These handle
   destructive or mutating operations and deserve regression guards.

3. **`com.organizer3.command` (3,800 missed, 59.9%)** — shell command handlers with
   substantial logic that currently lack direct tests. Moderate effort, high payoff for
   preventing silent regressions.
