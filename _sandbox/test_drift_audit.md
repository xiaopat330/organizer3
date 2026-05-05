# Test Fixture Drift Audit — 2026-05-04

## Summary

Total test classes scanned: 16 (all files touching a major mocked service).
Services audited: `TitleBrowseService` (35 public methods), `ActressBrowseService` (38 public methods),
`SearchService` (3 public methods), `JavdbDiscoveryService` (12 public methods),
`EnrichmentRunner` (12 public methods), `TrashService` (5 public methods), `TranslationService` (5 public methods).

High-severity findings: **1**. Medium: **3**. Low: **2** (dead stubs).

---

## Findings (high → low)

### 1. `UiStockedSmokeTest::{portalSearchShowsOverlayWithResults, searchResultActressClickNavigatesToDetail}` — HIGH

- **Mock:** `SearchService` in `UiTestFixture.buildStockedServer()` — 1 method stubbed out of 3 total.
- **Missing stub that test path exercises:**
  - `search(String, boolean, boolean, boolean)` (4-arg) — called via `GET /api/search` in `SearchRoutes:40`. The fixture stubs the **3-arg** overload `search(String, boolean, boolean)`. In Mockito 5, these are distinct method signatures; the 3-arg stub does NOT match the 4-arg call. Mockito returns an empty `HashMap` for the unstubbed 4-arg call. The JS overlay receives `{}` instead of `{"actresses":[…], …}`, so `result.actresses` is `undefined`, no results render, and `waitForCondition(() -> overlay.contains("Yua Mikami"))` **times out**.
- **Note on mock type:** `searchService` is created via plain `mock(SearchService.class)`, not a spy or `CALLS_REAL_METHODS` mock. In the real implementation, the 3-arg overload delegates to the 4-arg one. On a Mockito mock, no delegation occurs — both overloads are independent stub slots. This is why fixing the 3-arg stub is not sufficient; the 4-arg stub must be added explicitly.
- **Root commit:** `28c8a17` (April 20) added the `includeSparse` param to `SearchRoutes` and switched the call to the 4-arg overload. `UiTestFixture` was not updated.
- **Two tests fail in this way:**
  - `portalSearchShowsOverlayWithResults` — waits for `"Yua Mikami"` in overlay → timeout.
  - `searchResultActressClickNavigatesToDetail` — same precondition; times out before it can click the result.
- **Failure mode:** silent timeout (same pattern as the `UiLibraryFilterTest` regression).
- **Recommendation:** Replace the 3-arg stub with the 4-arg stub in both `buildStockedServer()` and `buildJavdbStockedServer()`:
  ```java
  when(searchService.search(anyString(), anyBoolean(), anyBoolean(), anyBoolean()))
      .thenReturn(searchResult);
  ```
  `buildJavdbStockedServer()` has no search stub at all; add the same there.

---

### 2. `UiLibraryFilterTest::{tagToggleClickMarksChipActiveAndRendersChipBar, chipRemoveButtonClearsActiveTagAndDeactivatesToggle}` — MEDIUM (known, *not yet fixed*)

- **Mock:** `JavdbDiscoveryService` in `UiTestFixture.buildStockedServer()` — 3 methods stubbed
  (`listActresses`, `getQueueStatus`, `getActiveQueueItems`) out of 12 total.
- **Missing stub that test path exercises:**
  - `getTagHealthReport()` — called via `GET /api/javdb/discovery/tag-health` (`JavdbDiscoveryRoutes:208`). The library filter module (`title-browse/library.js:41`) always fetches this endpoint when entering library mode to populate enrichment-tag filter rows. Mockito returns `null` for the unstubbed method (record return type). Javalin calls `ctx.json(null)`, which throws an internal error and returns a 500. The JS fallback `r.ok ? r.json() : { definitions: [] }` absorbs the 500 and renders zero enrichment-tag toggles — **this is graceful**, so it does NOT cause a timeout.
  - **Actual status:** this was the **original** regression that prompted the audit. It was fixed in commit `18ff8cb` by adding the `countAll()` and `getTagCounts()` stubs. `getTagHealthReport()` is still unstubbed but the JS fallback means it degrades silently rather than timing out. The enrichment-tag section of the filter panel simply renders empty.
- **Failure mode:** silent pass-without-asserting (enrichment tags never visible in tests).
- **Recommendation:** Add `when(javdbService.getTagHealthReport()).thenReturn(new JavdbDiscoveryService.TagHealthReport(null, List.of()))` (or a minimal populated instance) to `buildStockedServer()` so the enrichment-tag filter panel is testable.

---

### 3. `UiJavdbDiscoveryTest::*` (all tests using `buildJavdbStockedServer`) — MEDIUM

- **Mock:** `TitleBrowseService` in `buildJavdbStockedServer()` — 13 methods stubbed; **missing** stubs that `buildStockedServer()` now carries:
  - `countAll()` — not stubbed → Mockito returns `0L`. If any JavDB test path navigates to library mode (currently none do), the tag filter panel would collapse exactly as it did in the original regression.
  - `getTagCounts()` — not stubbed → Mockito returns `null` (Map type). Same risk.
  - `actressBrowse.findFavoritesPaged`, `findBookmarksPaged`, `findByTierPaged`, `getSpotlight`, `findById` — not stubbed in `buildJavdbStockedServer`. None of these are currently exercised by the JavDB tests (which stay in the tools view), but any new JavDB test that navigates to actress browse would silently degrade.
- **Failure mode:** currently not failing (tests don't enter those paths), but the factory has diverged from `buildStockedServer()` and will silently break any new test that does.
- **Recommendation:** Extract the common stub block into a shared helper (e.g. `stubBrowseDefaults(titleBrowse, actressBrowse, searchService)`), called by both factories, so they stay in sync automatically.

---

### 4. `JavdbDiscoveryServiceTest` (service-level) — MEDIUM

- **Mock:** `EnrichmentRunner` in `setUp()` — 5 methods stubbed (`isPaused`, `getPauseUntil`,
  `getPauseReason`, `getConsecutiveRateLimitHits`, `getPauseType`). The real `EnrichmentRunner` has 12
  public methods.
- **Missing stub that test path exercises:**
  - `recoverCastAnomaliesAfterMatcherFix()` — called only in `CastAnomalyTriageServiceTest` (where it IS stubbed), not in `JavdbDiscoveryServiceTest`. Not an issue here.
  - No current test in `JavdbDiscoveryServiceTest` exercises a code path that needs an unstubbed method. **However**, the `getQueueStatus()` implementation calls `runner.isPaused()`, `getPauseUntil()`, `getPauseReason()`, `getConsecutiveRateLimitHits()`, and `getPauseType()` — all 5 are stubbed. Currently complete.
- **Failure mode:** not currently failing.
- **Recommendation:** Add a `verify(mockRunner, never()).start()` or similar guard if any test later calls `service.start()`, since `start()` is unstubbed and would throw on the mock.

---

### 5. Dead stub — `actressBrowse.findPrefixIndex()` — LOW

- **Location:** `UiTestFixture.buildStockedServer()` line 70.
- **Evidence:** The `/api/actresses/index` route exists in `ActressRoutes:23` and `findPrefixIndex()` is stubbed in the fixture to return `["Y", "A"]`. However, grepping the entire `src/main/resources/public/` tree shows **zero calls** to `/api/actresses/index` from any JS module. The actress landing's letter chips were removed or replaced by the tier-chip system in `actress-browse/chips.js:119`, which builds chips from the static `ACTRESS_TIERS` constant rather than fetching the index. The stub currently exercises nothing.
- **Recommendation:** Remove the `findPrefixIndex` and `findTierCountsByPrefix` stubs from `buildStockedServer()`. Add a deletion note to `CHANGES.md` if the `/api/actresses/index` route itself should be removed.

---

### 6. Dead stub — `actressBrowse.findTierCountsByPrefix()` — LOW

- **Location:** `UiTestFixture.buildStockedServer()` line 71.
- **Evidence:** Same as above — the `/api/actresses/tier-counts` route exists (`ActressRoutes:26`) but no JS module calls it. The tier panel shows hardcoded chips from the static constant, not server counts.
- **Recommendation:** Remove the stub. Consider deprecating/deleting the route if it has no callers.

---

## Cross-check table: stubs vs route calls for the two main services

| Method | Route | Stubbed in `buildStockedServer`? | Risk if exercised |
|---|---|---|---|
| `TitleBrowseService.countAll()` | `/api/titles/tag-counts` | ✓ (returns 10L) | — |
| `TitleBrowseService.getTagCounts()` | `/api/titles/tag-counts` | ✓ (returns map with 3 tags) | — |
| `TitleBrowseService.findByVolumeQueue()` | `/api/queues/{id}/titles` | ✗ → empty list | graceful |
| `TitleBrowseService.findTagsForPool()` | `/api/pool/{id}/tags` | ✗ → empty list | graceful |
| `TitleBrowseService.findTagsForCollections()` | `/api/collections/tags` | ✗ → empty list | graceful |
| `TitleBrowseService.findDuplicatesPaged()` | `/api/tools/duplicates` | ✗ → `null` (record return type) | potential NPE if JS unwraps `.titles` |
| `SearchService.search(4-arg)` | `/api/search` | ✗ → **empty map** | **TIMEOUT** (Finding 1) |
| `ActressBrowseService.findByVolumesPaged()` | `/api/actresses?volumes=` | ✗ → empty list | graceful |
| `JavdbDiscoveryService.getTagHealthReport()` | `/api/javdb/discovery/tag-health` | ✗ → null → 500 | graceful (JS fallback) |

**`findDuplicatesPaged`** deserves a separate note: Mockito will return `null` for the `DuplicatePage` record (an object type, not a collection). If the JS `action.js` / duplicate-ranker calls `/api/tools/duplicates` and does `data.titles.forEach(…)`, this would throw a JS TypeError. No current UI test exercises this path, but it is a latent risk if added.

---

## Negative result: `EnrichmentQueue` mocks

A grep across all test sources for `mock(EnrichmentQueue` and `@Mock.*EnrichmentQueue` returned zero results. No test class directly mocks `EnrichmentQueue`. The queue is exercised only through higher-level service mocks (`EnrichmentRunner`, `JavdbDiscoveryService`). No drift risk here.
