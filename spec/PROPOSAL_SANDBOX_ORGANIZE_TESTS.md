# Sandbox Organize Tests — Design Proposal

> **Status: APPROVED** — Opus consulted, decisions folded in; ready to execute (see §7)

---

## 1. Goal

Create a suite of end-to-end integration tests for the organize pipeline that run against the **real NAS over SMB**, using the existing `_sandbox` facility as a safe scratch area. These tests exercise the full stack — real file I/O, real timestamp semantics, real SMB rename/move behavior — rather than the mocked or `LocalFileSystem`-backed unit tests that already exist.

The tests live under a `@Tag("sandbox")` and run via a dedicated Gradle task (`sandboxTest`), excluded from the normal `test` task. They require VPN + NAS access and are opt-in.

---

## 2. Context

### 2.1 Existing unit test coverage

The organize pipeline already has unit tests using mocks or a `LocalFileSystem` + `@TempDir`:

| Test class | What it covers |
|---|---|
| `TitleTimestampServiceTest` | Timestamp detection and application (LocalFS) |
| `TitleSorterServiceTest` | Sort routing logic (LocalFS via `RebasingFS`) |
| `OrganizeVolumeServiceTest` | Pipeline orchestration (LocalFS) |
| `OrganizeSortTaskTest` etc. | Task pair (mount/unmount/phases) with mocked service |
| `FreshPrepServiceTest` | Prep filename parsing and folder creation (LocalFS) |

**Gap:** none of these test against real SMB. Rename, move, and `setTimestamps` all go through `SmbFileSystem` in production; certain failure modes (locked handles, case-sensitivity, ACL errors, SMB-specific timestamp precision) are invisible to the local FS tests.

### 2.2 Sandbox facility

`Sandbox` (`com.organizer3.trash.Sandbox`) wraps a `VolumeFileSystem` and provides:
- `root()` → `/_sandbox` (per-server, configured in `organizer-config.yaml` under `servers[].sandbox`)
- `resolve(subPath)` → `/_sandbox/<subPath>`
- `ensureExists()` → idempotent `createDirectories(root())`

Contents are **volatile by convention** — the app may overwrite or remove them at any time. No cleanup required after test runs; the user treats `/_sandbox` as disposable.

### 2.3 Organize services and their dependencies

#### `TitleNormalizerService`
- **Input:** `VolumeFileSystem fs`, `Path titleFolder`, `String titleCode`, `boolean dryRun`
- **What it does:** renames the cover file and/or the single video file to canonical `{CODE}.{ext}` form
- **Dependencies:** `MediaConfig` (video extensions), `NormalizeConfig` (removelist/replacelist)
- **No DB dependency**

#### `TitleRestructurerService`
- **Input:** `VolumeFileSystem fs`, `Path titleFolder`, `boolean dryRun`
- **What it does:** moves video files from the title folder base into the appropriate subfolder (`video/`, `h265/`, `4K/`) based on filename hints
- **Dependencies:** `MediaConfig` (video extensions)
- **No DB dependency**

#### `TitleTimestampService`
- **Input:** `VolumeFileSystem fs`, `Path titleFolder`, `boolean dryRun`
- **What it does:** sets the title folder's `created` and `modified` timestamps to the earliest timestamp across all child files
- **Dependencies:** none (pure FS)
- **No DB dependency**
- **SMB note:** `SmbFileSystem.setTimestamps()` is fully implemented via `FileBasicInformation`

#### `FreshPrepService`
- **Input:** `VolumeFileSystem fs`, `Path partitionRoot`, `boolean dryRun`, `int limit`, `int offset`
- **What it does:** for each loose video file directly at `partitionRoot`, parses a product code and moves it into `(CODE)/video/{normalized-filename}`
- **Dependencies:** `NormalizeConfig`, `MediaConfig`
- **No DB dependency**
- **Structure type:** queue volumes only (operates on the `unsorted/fresh` or `queue/` partition root)

#### `TitleSorterService`
- **Input:** `VolumeFileSystem fs`, `VolumeConfig`, `AttentionRouter`, `Jdbi jdbi`, `String titleCode`, `boolean dryRun`
- **What it does:** moves title folder from `queue/` into `/stars/{tier}/{actressName}/`; routes to `/attention/` on various conditions
- **Dependencies (heavy):** `TitleRepository`, `ActressRepository`, `TitleActressRepository`, `TitleLocationRepository`, `LibraryConfig`, `TitleTimestampService`; reads and writes the SQLite DB within a transaction
- **DB-coupled:** yes — requires `titles`, `actresses`, `title_actress`, `title_locations` rows

#### `FixTimestampsVolumeService`
- **Input:** `VolumeFileSystem fs`, `String volumeId`, `boolean dryRun`
- **What it does:** queries all non-queue `title_locations` for the volume, applies `TitleTimestampService` to each
- **Dependencies:** `Jdbi` (reads `title_locations` + `titles`), `TitleTimestampService`
- **DB-coupled:** yes — requires `title_locations` rows pointing to real paths

---

## 3. Proposed Test Infrastructure

### 3.1 Location and tagging

```
src/test/java/com/organizer3/sandbox/
  SandboxTestBase.java
  SandboxTitleBuilder.java
  organize/
    NormalizeSandboxTest.java
    RestructureSandboxTest.java
    FixTimestampsSandboxTest.java
    PrepSandboxTest.java
    SortSandboxTest.java     (deferred — see §3.5)
```

All classes annotated `@Tag("sandbox")`. Normal `test` task excludes `sandbox` (same pattern as existing `ui` tag). New `sandboxTest` Gradle task includes it.

### 3.2 `SandboxTestBase`

Abstract base class (`@BeforeAll` / `@AfterAll` static lifecycle):

```java
@Tag("sandbox")
abstract class SandboxTestBase {

    static OrganizerConfig config;
    static VolumeConnection conn;
    static VolumeFileSystem fs;
    static Jdbi jdbi;
    static Connection dbConn;
    static Path runDir;   // /_sandbox/tests/{testClass}-{uuid}

    @BeforeAll
    static void connect() throws Exception {
        config = new OrganizerConfigLoader().load();
        VolumeConfig vol = config.findById(TEST_VOLUME_ID).orElseThrow();
        ServerConfig srv = config.findServerById(vol.server()).orElseThrow();

        assumeTrue(srv.sandbox() != null, "No sandbox configured for server");
        try {
            conn = new SmbjConnector().connect(vol, srv);
        } catch (Exception e) {
            assumeTrue(false, "NAS not reachable: " + e.getMessage());
        }
        fs = conn.fileSystem();

        Sandbox sandbox = new Sandbox(fs, srv.sandbox());
        sandbox.ensureExists();
        runDir = sandbox.resolve("tests/" + simpleName() + "-" + uuid());
        fs.createDirectories(runDir);

        dbConn = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbi = Jdbi.create(dbConn);
        new SchemaInitializer(jdbi).initialize();
        seedVolume(jdbi, vol);
    }

    @AfterAll
    static void disconnect() throws Exception {
        if (conn != null) conn.close();
        if (dbConn != null) dbConn.close();
    }
}
```

**Test volume:** volume `"a"` (pandora, `conventional`) used for all tests. Different structure-type behaviors are simulated via path layout within `runDir` — no need to connect to a different volume.

**Skip behavior:** `assumeTrue` on connection failure → JUnit marks the test as *skipped*, not *failed*. CI without VPN sees green (skipped) rather than red.

**`seedVolume`**: inserts one `volumes` row matching `vol.id()` and `vol.structureType()`.

### 3.3 `SandboxTitleBuilder`

Fluent builder that creates fake title folder structures on the NAS and optionally inserts corresponding DB rows:

```java
class SandboxTitleBuilder {
    // Creates: runDir/queue/MIDE-123/
    //            mide123pl.jpg        (1-byte fake cover)
    //            video/mide123.mp4    (1-byte fake video)
    // Returns the title folder path

    SandboxTitleBuilder inDir(Path parent);
    SandboxTitleBuilder withCode(String code);
    SandboxTitleBuilder withCover(String filename);   // default: {code}pl.jpg
    SandboxTitleBuilder withVideo(String filename);   // default: {code}.mp4
    SandboxTitleBuilder videoInSubfolder(String subfolder);  // default: "video"
    SandboxTitleBuilder videoAtBase();                // puts video at title root (for restructure tests)
    SandboxTitleBuilder withTimestamp(Instant t);     // setTimestamps on cover + video files
    SandboxTitleBuilder insertDbRow(Jdbi jdbi, String partitionId);  // inserts title + title_location

    Path build(VolumeFileSystem fs) throws IOException;
}
```

**Why 1-byte files?** Real files are unnecessary — the organize services only look at filenames and paths, not content. 1-byte `writeFile` calls are fast and don't burn SMB bandwidth.

### 3.4 Gradle configuration

Following the existing `uiTest` pattern:

```groovy
test {
    useJUnitPlatform {
        excludeTags 'ui', 'sandbox'
    }
}

tasks.register('sandboxTest', Test) {
    description = 'Run sandbox E2E organize tests against the real NAS (requires VPN + SMB access).'
    group = 'verification'
    useJUnitPlatform {
        includeTags 'sandbox'
    }
}
```

### 3.5 Scope and sequencing

Build in this order:

1. **Harness + `NormalizeSandboxTest`** — validate end-to-end SMB access, prove the builder works; normalize has no DB dependency
2. **`RestructureSandboxTest`** — same shape, no DB
3. **`FixTimestampsSandboxTest`** — introduces DB rows + `setTimestamps` on SMB
4. **`PrepSandboxTest`** — loose video files, no DB
5. **`SortSandboxTest`** — deferred; requires actress + title_actress + title_locations rows, `AttentionRouter`, `LibraryConfig`; most complex setup by far

---

## 4. Test Cases Per Operation

### 4.1 Normalize (`NormalizeSandboxTest`)

Setup path layout: `runDir/normalize/`

| Case | Cover filename | Video filename | Expected outcome |
|---|---|---|---|
| `coverRenamedToCode` | `mide-123pl.jpg` (non-canonical) | `mide-123.mp4` | renamed to `MIDE-123.jpg` + `MIDE-123.mp4` |
| `alreadyNormalized` | `MIDE-123.jpg` | `MIDE-123.mp4` | no-op (0 changes) |
| `noOpWithMultipleCovers` | `cover1.jpg`, `cover2.jpg` | `MIDE-123.mp4` | cover not renamed (ambiguous); video renamed |
| `dryRunDoesNotMutate` | `mide123pl.jpg` | `mide123.mp4` | reports change needed, files unchanged |

**Assertions:** check the actual filename on the NAS after the call via `fs.listDirectory()`.

### 4.2 Restructure (`RestructureSandboxTest`)

Setup path layout: `runDir/restructure/`

| Case | Video placement | Expected outcome |
|---|---|---|
| `videoAtBaseMovedToSubfolder` | `MIDE-123.mp4` at title root | moved to `video/MIDE-123.mp4` |
| `h265VideoRoutedToH265Folder` | `MIDE-123-h265.mp4` at title root | moved to `h265/MIDE-123-h265.mp4` |
| `videoAlreadyInSubfolder` | `video/MIDE-123.mp4` | no-op |
| `collisionSkipped` | `MIDE-123.mp4` at root + `video/MIDE-123.mp4` already exists | skipped with reason |
| `dryRunDoesNotMutate` | `MIDE-123.mp4` at title root | reports move, file stays at root |

### 4.3 Fix Timestamps (`FixTimestampsSandboxTest`)

Setup path layout: `runDir/timestamps/`; requires `title_locations` DB rows pointing into sandbox.

| Case | Setup | Expected outcome |
|---|---|---|
| `folderTimestampUpdatedToEarliestChild` | Cover file set to 2018, folder set to now | folder `created`+`modified` → 2018 |
| `modifiedOnlyOutOfDate` | Cover `created`=target, `modified`=now | folder timestamps → target |
| `alreadyCorrect` | Folder already matches cover timestamp | `needsChange=false`, not touched |
| `dryRunReportsButDoesNotApply` | Cover set to 2018, folder=now | reports change, folder unchanged |
| `emptyFolderIsSkipped` | Title folder with no files | `needsChange=false` |

**SMB timestamp precision note:** SMB timestamps have 100ns resolution; the test should assert within ±1 second (not exact millis) to tolerate any server-side rounding.

### 4.4 Prep (`PrepSandboxTest`)

Setup path layout: `runDir/prep/fresh/` (partition root = `runDir/prep/fresh/`)

| Case | Input files | Expected outcome |
|---|---|---|
| `looseVideoCreatesFolder` | `MIDE-123.mp4` at partition root | `(MIDE-123)/video/MIDE-123.mp4` created |
| `h265VideoGoesToH265Subfolder` | `PRED-456-h265.mp4` at root | `(PRED-456)/h265/PRED-456-h265.mp4` |
| `junkPrefixStripped` | `foo.com@ONED-999-h265.mp4` | `(ONED-999)/h265/ONED-999-h265.mp4` |
| `unparseable_skipped` | `random_stuff.mp4` | no folder created, skip reason reported |
| `collisionSkipped` | `MIDE-123.mp4` + `(MIDE-123)/` already exists | skipped with reason |
| `dryRunDoesNotMutate` | `MIDE-123.mp4` | reports plan, no folder created |

---

## 5. Resolved Decisions (post-Opus review)

1. **Per-method run dirs.** Each `@Test` gets its own `methodRunDir` = `runDir/{methodName}-{shortUuid}/`, created in `@BeforeEach`. Class `runDir` is only the parent. Cheap insurance against cross-method FS pollution; enables any-order / parallel runs later.

2. **On-success cleanup.** Delete `methodRunDir` in `@AfterEach` **only when the test passes**. On failure, leave it intact and log the path for post-mortem. Implement via a JUnit 5 `TestWatcher` extension, not manual state. Class `runDir` itself is left in place (volatile-by-convention).

3. **DB lifecycle.** In-memory SQLite via `Jdbi.create(connection)` (matches existing `JdbiWatchHistoryRepositoryTest` etc. — single Connection reused, which Jdbi handles correctly). Created once per class in `@BeforeAll`, seeded with one `volumes` row. `title_locations` cleared in `@BeforeEach` to prevent cross-method pollution. Per-method UUID subpaths guarantee stale rows never match live FS paths.

4. **`SortSandboxTest` scope.** Single happy-path smoke test — 3 fake titles seeded to push actress into `library` tier, assert destination path. Tier logic stays in unit tests; sandbox only validates the end-to-end move + DB update on real SMB.

5. **Timestamp seeding.** Use **10-second** separation in test fixtures — well clear of the 2000ms `shouldChange` tolerance and any SMB-side rounding. Bake the helper into `SandboxTitleBuilder.withTimestamp()` so callers can't accidentally pick values that straddle the threshold. Assertions tolerate ±1s for SMB round-trip.

6. **Test volume selection.** System property `sandbox.test.volume` with default `"a"` (pandora, conventional). Single-user project — no null-guard needed; if the property resolves to a volume that isn't reachable, `assumeTrue` on the SMB connect already handles the skip.

### SMB-differentiating cases (load-bearing)

These cases are why the sandbox suite exists — they catch things LocalFS unit tests cannot. Call them out explicitly in javadocs on the relevant test classes:

- Rename where source and target differ only in case (`mide-123.mp4` → `MIDE-123.mp4`) — SMB/NTFS is case-insensitive; LocalFS on APFS behaves differently
- Timestamp round-trip: write `Instant` with sub-second precision, read back, assert within ±1s
- Move across subfolders on a real SMB share (atomicity of rename vs copy+delete fallback)

Other cases (e.g. `alreadyNormalized`, `dryRunDoesNotMutate`) are parity coverage — cheap to include but not the justification for the infra.

---

## 6. Non-Goals

- Testing the web layer / SSE event format (covered by existing integration tests)
- Testing the task runner lifecycle (covered by `OrganizeSortTaskTest` etc.)
- Full Sort + Classify E2E in the first pass (deferred to `SortSandboxTest`)
- Auto-cleanup of the sandbox root itself (only per-method run dirs are cleaned)

---

## 7. Execution Plan

### Phase 0 — Prerequisites (verify, don't build)
- [ ] Confirm `servers[].sandbox: _sandbox` is set for `pandora` in local `organizer-config.yaml`
- [ ] Confirm VPN/SMB reachability to pandora from dev machine
- [ ] Confirm `SchemaInitializer` produces a usable DB with `title_locations` + `titles` + `volumes` tables

### Phase 1 — Gradle wiring + empty harness + connectivity canary (single PR)
Goal: `./gradlew sandboxTest` runs one trivial test that creates `runDir`, writes a 1-byte file, lists it, passes.
- `build.gradle`: add `excludeTags 'sandbox'` to `test`; register `sandboxTest` task mirroring `uiTest`
- Create `src/test/java/com/organizer3/sandbox/` package
- `SandboxTestBase` skeleton: `@BeforeAll` connect (with `assumeTrue` skip), `@AfterAll` disconnect, `@BeforeEach` method run dir, `@AfterEach` on-success cleanup via `TestWatcher` extension
- `ConnectivitySandboxTest` with one method — **kept long-term as a canary** for "is the whole infra still wired"

Ship on its own. Validate with VPN on (green) and VPN off (skipped, not failed).

### Phase 2 — `SandboxTitleBuilder`
- Fluent builder per §3.3, but FS creation (`build(fs)`) and DB registration (`registerInDb(jdbi, partitionId)`) are separate methods
- Unit-test the builder against `LocalFileSystem` + `@TempDir`
- `withTimestamp()` helpers enforce 10s minimum separation

### Phase 3 — `NormalizeSandboxTest`
Template for subsequent classes. Validates builder + harness on real SMB.

### Phase 4 — `RestructureSandboxTest`, then `FixTimestampsSandboxTest`, then `PrepSandboxTest`
In that order (§3.5). Prep can land before FixTimestamps if the latter hits snags.

### Phase 5 — `SortSandboxTest` (deferred)
Single happy-path smoke test per decision #4 above.

---

