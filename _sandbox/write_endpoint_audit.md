# Write Endpoint Robustness Audit — 2026-05-04

## Summary
68 write handlers scanned across 19 route files (POST/PUT/DELETE/PATCH).
4 high-priority issues, 5 medium, 5 low.

---

## High priority

### 1. Multiple AvScreenshotQueueRoutes write handlers — unguarded `Long.parseLong`
- **File:** `src/main/java/com/organizer3/web/routes/AvScreenshotQueueRoutes.java:37,61,68,75`
- **Concern:** All four write handlers (`enqueue`, `pause`, `resume`, DELETE queue) call `Long.parseLong(ctx.pathParam("id"))` without a try/catch. A non-numeric `id` throws `NumberFormatException`, which Javalin maps to 500 rather than 400.
  ```java
  app.post("/api/av/actresses/{id}/screenshots/enqueue", ctx -> {
      long actressId = Long.parseLong(ctx.pathParam("id"));  // unguarded
  ```
- **Failure mode:** `GET /api/av/actresses/abc/screenshots/enqueue` → 500 stack trace instead of 400.
- **Fix sketch:** Wrap in try/catch `NumberFormatException` → 400, exactly as done in the adjacent `JavdbDiscoveryRoutes.parseId()` helper.
- **Test status:** `AvScreenshotQueueRoutesTest` exists but there is no test for a non-numeric id path.

### 2. AvStarsRoutes write handler — unguarded `Long.parseLong`
- **File:** `src/main/java/com/organizer3/web/routes/AvStarsRoutes.java:48,62`
- **Concern:** Both the GET detail and `POST /api/utilities/avstars/actresses/{id}/iafd/search` call `Long.parseLong(ctx.pathParam("id"))` without a try/catch. The POST is a write endpoint.
  ```java
  app.post("/api/utilities/avstars/actresses/{id}/iafd/search", ctx -> {
      long id = Long.parseLong(ctx.pathParam("id"));  // unguarded
  ```
- **Failure mode:** Non-numeric `id` → 500 NullPointerException/NumberFormatException instead of 400.
- **Fix sketch:** Try/catch → 400, same pattern as nearby handlers.
- **Test status:** No dedicated `AvStarsRoutesTest`; untested path.

### 3. `JavdbDiscoveryRoutes` — `bodyAsClass` NullPointerException on empty body for pause/move/surface
- **File:** `src/main/java/com/organizer3/web/routes/JavdbDiscoveryRoutes.java:90-91, 103-104, 215-216`
- **Concern:** Three handlers call `ctx.bodyAsClass(Record.class)` and immediately dereference the result without a null check:
  ```java
  var body = ctx.bodyAsClass(PauseRequest.class);
  actionService.setPaused(body.paused());     // NPE if body is null or malformed
  
  var body = ctx.bodyAsClass(MoveRequest.class);
  switch (body.action()) { ... }              // NPE if body is null
  
  var body = ctx.bodyAsClass(SurfaceRequest.class);
  service.setEnrichmentTagSurface(tagId, body.surface());  // NPE if body is null
  ```
  If the client sends an empty body, Javalin's `bodyAsClass` may return null or throw; in either case the handler 500s.
- **Failure mode:** `POST /api/javdb/discovery/queue/pause` with no body → 500 NPE.
- **Fix sketch:** Null-check after `bodyAsClass`, return 400 with a clear message. The pause/resume records have defaults (`boolean`) so a missing body that defaults `paused=false` is also reasonable, but that's a contract decision.
- **Test status:** `JavdbDiscoveryRoutesTest` exists but none of the covered tests send an empty body to these three endpoints.

### 4. `WatchHistoryRoutes` — no 404 when `titleCode` doesn't exist; no input validation
- **File:** `src/main/java/com/organizer3/web/routes/WatchHistoryRoutes.java:22-27`
- **Concern:** `POST /api/watch-history/{titleCode}` records a watch event for whatever path param is supplied, without verifying the title exists. An arbitrary string is persisted to the DB. There is also no check for blank/empty `titleCode`. The Javalin path param framework prevents empty strings in braced segments but does not prevent all garbage (e.g. spaces, unicode).
  ```java
  String titleCode = ctx.pathParam("titleCode");
  WatchHistory entry = repo.record(titleCode, ...);  // no existence check, no blank check
  ```
- **Failure mode:** Phantom watch records for non-existent titles accumulate silently; UI history counts are inflated.
- **Fix sketch:** Add a title-existence lookup before `record()`; return 404 if not found. Or at minimum reject blank codes with 400. This requires a `TitleRepository` dependency injection.
- **Test status:** No route-level test; only `JdbiWatchHistoryRepositoryTest` tests the repository layer.

---

## Medium priority

### 5. `BgThumbnailsRoutes` — `POST /api/bg-thumbnails/toggle` missing concurrency guard
- **File:** `src/main/java/com/organizer3/web/routes/BgThumbnailsRoutes.java:36-41`
- **Concern:** The toggle reads `worker.isEnabled()`, negates, then calls `worker.setEnabled(now)`. If two requests race, both read the same current value and write the same new value — the toggle is lost. Per memory notes, bg-thumbnails are explicitly declared to be outside the utility-task atomic lock, but this handler has no synchronization of its own.
- **Failure mode:** Double-tap from UI sends two concurrent POSTs; toggle appears to fire but state is net unchanged.
- **Fix sketch:** Make `toggleEnabled()` atomic in `BackgroundThumbnailWorker` (compare-and-set or synchronized method). No route change needed.
- **Test status:** No test for `BgThumbnailsRoutes`.

### 6. `ActressMergeRoutes` — `POST /api/actresses/{id}/merge` missing 404 when `intoId` doesn't exist
- **File:** `src/main/java/com/organizer3/web/routes/ActressMergeRoutes.java:52-57`
- **Concern:** The handler delegates to `MergeActressesTool.merge(...)`, which throws `IllegalArgumentException` (caught → 400). But if `intoId` or `fromId` references a non-existent actress, it surfaces as a 400 rather than the semantically correct 404. This conflates "bad input shape" with "resource not found".
- **Failure mode:** Client cannot distinguish between "fromId is required" (structural) and "actress 9999 not found" (missing resource) — both return 400.
- **Fix sketch:** In the handler, check `actressRepo.findById(intoId/fromId)` before delegating, return 404 on missing. Or change `MergeActressesTool` to throw a distinct `NotFoundException`.
- **Test status:** No route-level test for `ActressMergeRoutes`.

### 7. `DuplicateDecisionsRoutes` — `DELETE` returns 204 even when the row doesn't exist (silent no-op)
- **File:** `src/main/java/com/organizer3/web/routes/DuplicateDecisionsRoutes.java:83-101`
- **Concern:** `repo.delete(...)` is called without checking whether a row was actually deleted. The handler returns 204 regardless. A client that sends the wrong params (e.g. typo'd `nasPath`) will get "success" back silently.
- **Failure mode:** Client believes the decision was removed; it was never found. No indication of the error.
- **Fix sketch:** Have `repo.delete()` return an `int` rows-deleted count; return 404 if 0.
- **Test status:** `DuplicateDecisionsRoutesTest` exists but no test for the not-found delete path.

### 8. `UnsortedEditorRoutes` — `POST /api/unsorted/titles/{id}/cover` no Content-Type guard for URL branch; URL param used in HTTP fetch without sanitization
- **File:** `src/main/java/com/organizer3/web/routes/UnsortedEditorRoutes.java:139-153`
- **Concern:** The URL-fetch branch reads `body.url` and passes it directly to `imageFetcher.fetch(body.url)`. There is no scheme check (e.g. `file://`, `jar:`, `ftp://`). If `imageFetcher` uses `URL.openStream()` without scheme filtering, a crafted request could read local files.
  ```java
  var fetched = imageFetcher.fetch(body.url);  // no scheme/host validation
  ```
  `ImageFetcher.ImageFetchException` is caught and mapped to 400, but only network errors are caught there — filesystem reads would succeed silently.
- **Failure mode:** SSRF / local file read if `ImageFetcher` doesn't restrict schemes.
- **Fix sketch:** Validate that `body.url` starts with `https://` or `http://` before calling the fetcher.
- **Test status:** `UnsortedEditorServiceTest` exists but tests service layer; no route test for the cover URL injection path.

### 9. `TranslationRoutes` — `POST /api/translation/manual` blocks indefinitely with no timeout
- **File:** `src/main/java/com/organizer3/web/routes/TranslationRoutes.java:136`
- **Concern:** The route comment says "May block 30-120s." `service.requestTranslationSync(req)` holds a Javalin worker thread for the full duration of the Ollama request. Javalin's default thread pool is bounded; a flurry of manual requests can exhaust it, making the entire app unresponsive.
- **Failure mode:** Heavy manual-translate use or a slow Ollama node starves all other HTTP handlers.
- **Fix sketch:** Run the sync call in a virtual thread (Javalin supports `ctx.future()`), or add a hard deadline via `CompletableFuture.orTimeout`. Not urgent if manual translate is rare.
- **Test status:** `TranslationRoutesTest` exists but mocks the service; does not test thread exhaustion.

---

## Low priority / nice-to-have

### 10. `TitleTagEditorRoutes` — `PUT /api/titles/{code}/tags` no check that tag values are non-empty after trim
- **File:** `src/main/java/com/organizer3/web/routes/TitleTagEditorRoutes.java:92-97`
- **Concern:** Blank strings are stripped with `if (s.isEmpty()) continue` but the code is post-trim, so this is actually handled. Low risk, well-structured. Noted for completeness.
- **Test status:** No route-level test (only service-layer tests).

### 11. `AvScreenshotQueueRoutes` — `enqueue` silently no-ops on unknown `actressId`
- **File:** `src/main/java/com/organizer3/web/routes/AvScreenshotQueueRoutes.java:38-56`
- **Concern:** If `actressId` doesn't exist, `videoRepo.findByActress(actressId)` returns an empty list; all three counts stay 0. The response is `{enqueued:0, alreadyDone:0, alreadyQueued:0}` — indistinguishable from "actress has no videos."
- **Fix sketch:** Look up the actress first; return 404 if not found. Or include an `actressFound` field in the response.
- **Test status:** `AvScreenshotQueueRoutesTest` does not test the unknown-actress case.

### 12. `MergeCandidatesRoutes` — `PUT` returns 204 on non-existent candidate id without surfacing "not found"
- **File:** `src/main/java/com/organizer3/web/routes/MergeCandidatesRoutes.java:72-78`
- **Concern:** `repo.decide(id, ...)` does not check rows affected; 204 is returned even if `id` didn't exist.
- **Fix sketch:** Check rows-affected; return 404 if 0.
- **Test status:** `DuplicateDecisionsRoutesTest` covers decisions; MergeCandidatesRoutes has no dedicated test.

### 13. `NoMatchTriageRoutes` — `POST .../reassign` casts `actressIdRaw` to `Number` but JSON integers from `Map` are `Integer`, not `Long`
- **File:** `src/main/java/com/organizer3/web/routes/NoMatchTriageRoutes.java:71-77`
- **Concern:** `((Number) actressIdRaw).longValue()` works for both `Integer` and `Long`, so this is actually safe. But the explicit cast-to-Number with a `ClassCastException` guard is fragile if Jackson ever maps to a `BigInteger` (very large IDs). Low risk in practice.
- **Test status:** `NoMatchTriageRoutesTest` exists.

### 14. `TrashRoutes.handleListVolumes` — `Map.of(...)` with `(Object) null` cast for `itemCount`
- **File:** `src/main/java/com/organizer3/web/routes/TrashRoutes.java:72`
- **Concern:** `m.put("itemCount", (Object) null)` is safe because `itemToJson` uses `LinkedHashMap`, not `Map.of`. However, the cast is a code smell that hints at a past `Map.of`-null bug and may confuse maintainers into thinking `Map.of` accepts null here.
- **Fix sketch:** Use `LinkedHashMap` throughout or annotate with a comment.

---

## Out of scope but noticed

- `DraftRoutes.registerBulkEnrichPreview` issues N+1 SQL queries (two per titleId). Not a robustness bug but a latency/correctness concern for large batches.
- `UtilitiesRoutes` creates a fresh `ObjectMapper` inline in several handlers (`new ObjectMapper().createObjectNode()`). These bypass the application-scoped `ObjectMapper` with its registered modules; if date serialization is ever added to those nodes, it will misbehave silently.
- Several routes use raw `Map.class` with `@SuppressWarnings("unchecked")` for body parsing. This works but has no schema validation — a float where an int is expected will succeed silently. Not exploitable, but consider a typed DTO for high-churn endpoints.

---

## Recommended cleanup pass scope

A single PR could reasonably bundle:
1. **AvScreenshotQueueRoutes** (item 1) — add `try/catch NumberFormatException` to all four write handlers + a test.
2. **AvStarsRoutes** (item 2) — same fix for the one write handler + test.
3. **JavdbDiscoveryRoutes** (item 3) — null-check after `bodyAsClass` for the three thin handlers.
4. **DuplicateDecisionsRoutes DELETE not-found** (item 7) — return 404 when `repo.delete()` affects 0 rows + test.
5. **MergeCandidatesRoutes decide not-found** (item 12) — same pattern.

Items 4 (WatchHistory) and 8 (URL cover SSRF) warrant separate PRs since they require either a new dependency injection or a deliberate security policy decision.
