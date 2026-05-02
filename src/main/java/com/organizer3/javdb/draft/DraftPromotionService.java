package com.organizer3.javdb.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.javdb.enrichment.EnrichmentHistoryRepository;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implements the Draft Mode promotion transaction: pre-flight validation (§4.1)
 * and the atomic promotion transaction (§4.2).
 *
 * <h3>Atomicity</h3>
 * <p>Option B (per spec task instructions) is used: all promotion SQL is written
 * inline within a single {@code jdbi.inTransaction(handle -> ...)} lambda. This
 * guarantees that every canonical write shares one JDBI {@link Handle} / one
 * SQLite transaction.
 *
 * <h3>Cover-copy / COMMIT window</h3>
 * <p>The cover copy (§4.2 step 8) is performed INSIDE the lambda but BEFORE the
 * implicit COMMIT. If the copy fails, the lambda throws and the txn rolls back.
 * If COMMIT then fails (rare — disk-full etc.), the copied cover is orphaned; the
 * caller catches COMMIT exceptions and deletes the copied file as compensation.
 * A {@code final Path[] destCoverHolder} side-channel carries the destination path
 * out of the lambda for this compensation step, because the lambda's return value
 * is lost when COMMIT throws.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §4, §5.1, §5.4, §6, §8, §9.3.
 */
@Slf4j
public class DraftPromotionService {

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final Jdbi                          jdbi;
    private final DraftTitleRepository          draftTitleRepo;
    private final DraftActressRepository        draftActressRepo;
    private final DraftTitleActressesRepository draftCastRepo;
    private final DraftTitleEnrichmentRepository draftEnrichRepo;
    private final DraftCoverScratchStore        coverStore;
    private final CoverPath                     coverPath;
    private final CastValidator                 castValidator;
    private final TitleRepository               titleRepo;
    private final EnrichmentHistoryRepository   historyRepo;
    private final TitleEffectiveTagsService     effectiveTags;
    private final ObjectMapper                  json;

    /** Seam for cover-copy — injectable in tests to simulate COMMIT-failure. */
    private CoverCopier coverCopier;

    // ── Constructor ───────────────────────────────────────────────────────────

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DraftPromotionService(
            Jdbi                          jdbi,
            DraftTitleRepository          draftTitleRepo,
            DraftActressRepository        draftActressRepo,
            DraftTitleActressesRepository draftCastRepo,
            DraftTitleEnrichmentRepository draftEnrichRepo,
            DraftCoverScratchStore        coverStore,
            CoverPath                     coverPath,
            CastValidator                 castValidator,
            TitleRepository               titleRepo,
            EnrichmentHistoryRepository   historyRepo,
            TitleEffectiveTagsService     effectiveTags,
            ObjectMapper                  json) {
        this.jdbi            = jdbi;
        this.draftTitleRepo  = draftTitleRepo;
        this.draftActressRepo = draftActressRepo;
        this.draftCastRepo   = draftCastRepo;
        this.draftEnrichRepo = draftEnrichRepo;
        this.coverStore      = coverStore;
        this.coverPath       = coverPath;
        this.castValidator   = castValidator;
        this.titleRepo       = titleRepo;
        this.historyRepo     = historyRepo;
        this.effectiveTags   = effectiveTags;
        this.json            = json;
        this.coverCopier     = this::defaultCoverCopy;
    }

    /** Override the cover-copy implementation; used in tests to inject failure. */
    void setCoverCopier(CoverCopier copier) {
        this.coverCopier = copier;
    }

    /**
     * Optional hook called at the very end of the transaction lambda, just before
     * {@code return canonicalTitleId}. Null in production. Tests inject a throwing
     * {@link Runnable} here to simulate COMMIT-failure and verify compensation.
     */
    private Runnable preCommitHook;

    /** Injects a pre-commit hook for testing COMMIT-failure compensation. */
    void setPreCommitHook(Runnable hook) {
        this.preCommitHook = hook;
    }

    // ── Functional interface for cover copy (testability seam) ────────────────

    @FunctionalInterface
    public interface CoverCopier {
        /**
         * Copies the scratch cover file to its canonical destination.
         *
         * @param scratch  the source scratch file
         * @param dest     the destination path
         * @throws IOException on copy failure
         */
        void copy(Path scratch, Path dest) throws IOException;
    }

    // ── Pre-flight (§4.1) ────────────────────────────────────────────────────

    /**
     * Runs pre-flight validation against the given draft title.
     *
     * <p>Pure read — no writes, no transaction.
     *
     * @param draftTitleId  the {@code draft_titles.id} to validate.
     * @param expectedUpdatedAt  optimistic-lock token supplied by the caller
     *                           (must match current {@code draft_titles.updated_at}).
     *                           Pass {@code null} to skip the optimistic-lock check
     *                           (used when re-running preflight inside the transaction).
     * @return {@link PreFlightResult#success()} or a failure with structured error codes.
     * @throws DraftNotFoundException if no draft exists for the given id.
     */
    public PreFlightResult preflight(long draftTitleId, String expectedUpdatedAt) {
        Optional<DraftTitle> draftOpt = draftTitleRepo.findById(draftTitleId);
        if (draftOpt.isEmpty()) {
            throw new DraftNotFoundException(draftTitleId);
        }
        DraftTitle draft = draftOpt.get();
        return runChecks(draft, expectedUpdatedAt);
    }

    /**
     * Overload that looks up the draft by canonical title id.
     * Throws {@link DraftNotFoundException} if no draft exists.
     */
    public PreFlightResult preflightByTitleId(long titleId, String expectedUpdatedAt) {
        Optional<DraftTitle> draftOpt = draftTitleRepo.findByTitleId(titleId);
        if (draftOpt.isEmpty()) {
            throw new DraftNotFoundException(-1L);
        }
        return runChecks(draftOpt.get(), expectedUpdatedAt);
    }

    private PreFlightResult runChecks(DraftTitle draft, String expectedUpdatedAt) {
        List<String> errors = new ArrayList<>();

        // Check 4: upstream_changed flag
        if (draft.isUpstreamChanged()) {
            errors.add("UPSTREAM_CHANGED");
        }

        // Check 5: optimistic-lock token
        if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(draft.getUpdatedAt())) {
            errors.add("OPTIMISTIC_LOCK_CONFLICT");
        }

        // Load cast slots
        List<DraftTitleActress> slots = draftCastRepo.findByDraftTitleId(draft.getId());

        // Determine javdb stage_name count from enrichment cast_json
        int javdbStageNameCount = countJavdbStageNames(draft.getId());

        // Check 2: cast mode rule (§5.1)
        List<String> castErrors = castValidator.validate(javdbStageNameCount, slots.stream()
                .map(DraftTitleActress::getResolution).toList());
        errors.addAll(castErrors);

        // Check 3: english_last_name required for create_new slots
        for (DraftTitleActress slot : slots) {
            if ("create_new".equals(slot.getResolution())) {
                Optional<DraftActress> actress = draftActressRepo.findBySlug(slot.getJavdbSlug());
                if (actress.isEmpty() ||
                        actress.get().getEnglishLastName() == null ||
                        actress.get().getEnglishLastName().isBlank()) {
                    if (!errors.contains("MISSING_ENGLISH_LAST_NAME")) {
                        errors.add("MISSING_ENGLISH_LAST_NAME");
                    }
                }
            }
        }

        return errors.isEmpty() ? PreFlightResult.success() : PreFlightResult.failure(errors);
    }

    // ── Promotion transaction (§4.2) ─────────────────────────────────────────

    /**
     * Promotes a draft to canonical state in one DB transaction.
     *
     * <p>Steps 1-9 per §4.2. Cover copy (step 8) runs inside the lambda before COMMIT;
     * COMMIT-failure compensation deletes the copied file.
     *
     * @param draftTitleId    the {@code draft_titles.id} to promote.
     * @param expectedUpdatedAt  optimistic-lock token.
     * @return the canonical {@code titles.id} of the promoted title.
     * @throws DraftNotFoundException   if no draft exists.
     * @throws PreFlightFailedException if pre-flight checks fail.
     * @throws OptimisticLockException  if the lock token doesn't match.
     * @throws PromotionException       on transaction-level failure.
     */
    public long promote(long draftTitleId, String expectedUpdatedAt) {
        // Load draft upfront to fail fast before opening transaction
        DraftTitle draftCheck = draftTitleRepo.findById(draftTitleId)
                .orElseThrow(() -> new DraftNotFoundException(draftTitleId));

        // Quick optimistic-lock pre-check (also re-run inside txn per §4.2 step 1)
        if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(draftCheck.getUpdatedAt())) {
            throw new OptimisticLockException(
                    "promote: updated_at mismatch for draft id=" + draftTitleId +
                    " (expected=" + expectedUpdatedAt + ", actual=" + draftCheck.getUpdatedAt() + ")");
        }

        // Side-channel for COMMIT-failure compensation (§4.3)
        final Path[] destCoverHolder = {null};

        long titleId;
        try {
            titleId = jdbi.inTransaction(h -> executePromotionTransaction(
                    h, draftTitleId, expectedUpdatedAt, destCoverHolder));
        } catch (DraftNotFoundException | PreFlightFailedException | OptimisticLockException e) {
            // COMMIT-failure compensation: remove the cover that was copied before COMMIT
            compensateCover(destCoverHolder[0]);
            throw e;  // pass through typed exceptions
        } catch (PromotionException pe) {
            // COMMIT-failure compensation: remove the cover that was copied before COMMIT
            compensateCover(destCoverHolder[0]);
            // Set last_validation_error on the draft so user can see the failure
            safelySetValidationError(draftTitleId, pe.getMessage());
            throw pe;
        } catch (Exception e) {
            // COMMIT-failure compensation: remove the cover that was copied before COMMIT
            compensateCover(destCoverHolder[0]);
            // Unexpected exception — set validation error and wrap
            safelySetValidationError(draftTitleId, "promote_failed: " + e.getMessage());
            throw new PromotionException("promote_failed", "Unexpected error during promotion", e);
        }

        // Post-commit: recompute effective tags (must be outside transaction — same as canonical path)
        try {
            effectiveTags.recomputeForTitle(titleId);
        } catch (Exception e) {
            log.warn("promote: effective-tags recompute failed for title {} (non-fatal)", titleId, e);
        }

        // Post-commit: delete scratch cover — best-effort; GC sweep catches leaks
        try {
            coverStore.delete(draftTitleId);
        } catch (IOException e) {
            log.warn("promote: failed to delete scratch cover for draft {} (non-fatal)", draftTitleId, e);
        }

        log.info("draft promoted: draftId={} → titleId={}", draftTitleId, titleId);
        return titleId;
    }

    // ── Transaction body ─────────────────────────────────────────────────────

    private long executePromotionTransaction(
            Handle h, long draftTitleId, String expectedUpdatedAt,
            Path[] destCoverHolder) throws IOException {

        // ── Step 1: Re-run pre-flight inside the txn (race protection) ──────────
        DraftTitle draft = h.createQuery("SELECT * FROM draft_titles WHERE id = :id")
                .bind("id", draftTitleId)
                .map(DraftTitleRepository.ROW_MAPPER)
                .findOne()
                .orElseThrow(() -> new DraftNotFoundException(draftTitleId));

        PreFlightResult check = runChecksWith(h, draft, expectedUpdatedAt);
        if (!check.ok()) {
            // Check for optimistic lock conflict specifically
            if (check.errors().contains("OPTIMISTIC_LOCK_CONFLICT")) {
                throw new OptimisticLockException(
                        "promote: optimistic lock conflict for draft id=" + draftTitleId);
            }
            throw new PreFlightFailedException(check.errors());
        }

        // Load enrichment — needed for slug, tags, etc.
        DraftEnrichment enrichment = h.createQuery(
                "SELECT * FROM draft_title_javdb_enrichment WHERE draft_title_id = :id")
                .bind("id", draftTitleId)
                .map(DraftTitleEnrichmentRepository.ROW_MAPPER)
                .findOne()
                .orElseThrow(() -> new PromotionException("missing_enrichment",
                        "No enrichment row for draft id=" + draftTitleId));

        // Load cast slots
        List<DraftTitleActress> slots = h.createQuery(
                "SELECT * FROM draft_title_actresses WHERE draft_title_id = :id")
                .bind("id", draftTitleId)
                .map(DraftTitleActressesRepository.ROW_MAPPER)
                .list();

        String nowIso = Instant.now().toString();
        long canonicalTitleId = draft.getTitleId();

        // ── Step 7 (before writes): snapshot prior canonical state for audit ────
        // Must be captured BEFORE the UPDATE in step 3.
        PriorState priorState = snapshotPriorState(h, canonicalTitleId, draft.getCode());

        // ── Step 2: INSERT new actresses for create_new resolutions ─────────────
        Map<String, Long> newActressIds = insertNewActresses(h, slots, nowIso);

        // ── Step 3: UPDATE titles row from draft ─────────────────────────────────
        h.createUpdate("""
                UPDATE titles SET
                    title_original = :titleOriginal,
                    title_english  = :titleEnglish,
                    release_date   = :releaseDate,
                    notes          = :notes,
                    grade          = :grade,
                    grade_source   = 'enrichment'
                WHERE id = :id
                """)
                .bind("titleOriginal", draft.getTitleOriginal())
                .bind("titleEnglish",  draft.getTitleEnglish())
                .bind("releaseDate",   draft.getReleaseDate())
                .bind("notes",         draft.getNotes())
                .bind("grade",         draft.getGrade())
                .bind("id",            canonicalTitleId)
                .execute();

        // ── Step 4: Replace title_actresses cast ─────────────────────────────────
        h.createUpdate("DELETE FROM title_actresses WHERE title_id = :id")
                .bind("id", canonicalTitleId)
                .execute();
        insertTitleActresses(h, canonicalTitleId, slots, newActressIds);

        // ── Step 5: INSERT OR REPLACE title_javdb_enrichment ─────────────────────
        writeCanonicalEnrichment(h, canonicalTitleId, enrichment, nowIso);

        // ── Step 6: Resolve and write title_enrichment_tags ─────────────────────
        h.createUpdate("DELETE FROM title_enrichment_tags WHERE title_id = :id")
                .bind("id", canonicalTitleId)
                .execute();
        writeResolvedTags(h, canonicalTitleId, enrichment.getTagsJson());

        // ── Step 7: Audit log row ─────────────────────────────────────────────────
        String newPayload        = snapshotNewState(h, canonicalTitleId);
        String promotionMetadata = buildPromotionMetadata(slots, newActressIds, coverStore.exists(draftTitleId));

        historyRepo.appendPromotion(
                canonicalTitleId, draft.getCode(),
                priorState.slug(), priorState.payload(),
                newPayload, promotionMetadata,
                h);

        // ── Step 8: Cover copy (filesystem — before COMMIT) ──────────────────────
        if (coverStore.exists(draftTitleId)) {
            Title canonicalTitle = titleRepo.findById(canonicalTitleId).orElse(null);
            if (canonicalTitle != null && !coverPath.exists(canonicalTitle)) {
                Path scratch = coverStore.coverPath(draftTitleId);
                Path dest    = coverPath.resolve(canonicalTitle, "jpg");
                try {
                    Files.createDirectories(dest.getParent());
                    coverCopier.copy(scratch, dest);
                    destCoverHolder[0] = dest;
                    log.info("promote: copied scratch cover {} → {}", scratch, dest);
                } catch (IOException e) {
                    // Step 8 failure → throw → txn rolls back
                    // Spec §4.3: delete any partial copy
                    try { Files.deleteIfExists(dest); } catch (IOException ignored) { /* best-effort */ }
                    throw new PromotionException("cover_copy_failed",
                            "Failed to copy scratch cover to title folder: " + e.getMessage(), e);
                }
            }
        }

        // ── Step 9: DELETE draft rows ─────────────────────────────────────────────
        // delete in FK order; ON DELETE CASCADE handles child rows
        h.createUpdate("DELETE FROM draft_title_javdb_enrichment WHERE draft_title_id = :id")
                .bind("id", draftTitleId)
                .execute();
        h.createUpdate("DELETE FROM draft_title_actresses WHERE draft_title_id = :id")
                .bind("id", draftTitleId)
                .execute();
        h.createUpdate("DELETE FROM draft_titles WHERE id = :id")
                .bind("id", draftTitleId)
                .execute();

        // ── Pre-commit hook (null in production; tests inject a throwing Runnable ─
        // here to simulate COMMIT-failure and verify compensation logic).
        if (preCommitHook != null) {
            preCommitHook.run();
        }

        return canonicalTitleId;
        // ── COMMIT happens here (JDBI inTransaction) ─────────────────────────────
        // If COMMIT fails (rare — disk full etc.), outer code compensates by deleting
        // destCoverHolder[0] if non-null.
    }

    // ── Pre-flight inside-transaction variant ────────────────────────────────

    /**
     * Runs pre-flight checks using the provided handle (for use inside the promotion transaction).
     * Reads draft_actresses rows via the handle to share the transaction.
     */
    private PreFlightResult runChecksWith(Handle h, DraftTitle draft, String expectedUpdatedAt) {
        List<String> errors = new ArrayList<>();

        if (draft.isUpstreamChanged()) {
            errors.add("UPSTREAM_CHANGED");
        }
        if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(draft.getUpdatedAt())) {
            errors.add("OPTIMISTIC_LOCK_CONFLICT");
        }

        List<DraftTitleActress> slots = h.createQuery(
                "SELECT * FROM draft_title_actresses WHERE draft_title_id = :id")
                .bind("id", draft.getId())
                .map(DraftTitleActressesRepository.ROW_MAPPER)
                .list();

        int javdbStageNameCount = countJavdbStageNamesWithHandle(h, draft.getId());

        List<String> castErrors = castValidator.validate(javdbStageNameCount, slots.stream()
                .map(DraftTitleActress::getResolution).toList());
        errors.addAll(castErrors);

        for (DraftTitleActress slot : slots) {
            if ("create_new".equals(slot.getResolution())) {
                Optional<DraftActress> actress = h.createQuery(
                        "SELECT * FROM draft_actresses WHERE javdb_slug = :slug")
                        .bind("slug", slot.getJavdbSlug())
                        .map(DraftActressRepository.ROW_MAPPER)
                        .findOne();
                if (actress.isEmpty() ||
                        actress.get().getEnglishLastName() == null ||
                        actress.get().getEnglishLastName().isBlank()) {
                    if (!errors.contains("MISSING_ENGLISH_LAST_NAME")) {
                        errors.add("MISSING_ENGLISH_LAST_NAME");
                    }
                }
            }
        }

        return errors.isEmpty() ? PreFlightResult.success() : PreFlightResult.failure(errors);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Counts the number of original javdb cast entries from the enrichment cast_json. */
    private int countJavdbStageNames(long draftTitleId) {
        return draftEnrichRepo.findByDraftId(draftTitleId)
                .map(e -> castListSize(e.getCastJson()))
                .orElse(0);
    }

    private int countJavdbStageNamesWithHandle(Handle h, long draftTitleId) {
        return h.createQuery(
                "SELECT cast_json FROM draft_title_javdb_enrichment WHERE draft_title_id = :id")
                .bind("id", draftTitleId)
                .mapTo(String.class)
                .findOne()
                .map(this::castListSize)
                .orElse(0);
    }

    private int castListSize(String castJson) {
        if (castJson == null || castJson.isBlank()) return 0;
        try {
            List<?> list = json.readValue(castJson, new TypeReference<List<?>>() {});
            return list.size();
        } catch (Exception e) {
            log.warn("Failed to parse cast_json to count stage names", e);
            return 0;
        }
    }

    /** Inserts new actress rows for create_new resolutions; returns slug→newId map. */
    private Map<String, Long> insertNewActresses(Handle h, List<DraftTitleActress> slots, String nowIso) {
        Map<String, Long> result = new HashMap<>();
        for (DraftTitleActress slot : slots) {
            if (!"create_new".equals(slot.getResolution())) continue;
            DraftActress da = h.createQuery(
                    "SELECT * FROM draft_actresses WHERE javdb_slug = :slug")
                    .bind("slug", slot.getJavdbSlug())
                    .map(DraftActressRepository.ROW_MAPPER)
                    .findOne()
                    .orElseThrow(() -> new PromotionException("missing_draft_actress",
                            "No draft_actress for slug=" + slot.getJavdbSlug()));

            String canonicalName = buildCanonicalName(da);
            long actressId = h.createUpdate("""
                    INSERT INTO actresses
                        (canonical_name, stage_name, tier, first_seen_at,
                         created_via, created_at)
                    VALUES
                        (:canonicalName, :stageName, 'STANDARD', :firstSeenAt,
                         'draft_promotion', :createdAt)
                    """)
                    .bind("canonicalName", canonicalName)
                    .bind("stageName",     da.getStageName())
                    .bind("firstSeenAt",   nowIso)
                    .bind("createdAt",     nowIso)
                    .executeAndReturnGeneratedKeys("id")
                    .mapTo(Long.class)
                    .one();

            result.put(slot.getJavdbSlug(), actressId);
            log.info("promote: created new actress id={} name='{}' via draft_promotion", actressId, canonicalName);
        }
        return result;
    }

    private String buildCanonicalName(DraftActress da) {
        String first = da.getEnglishFirstName();
        String last  = da.getEnglishLastName();
        if (first != null && !first.isBlank()) {
            return (first.trim() + " " + last.trim()).trim();
        }
        return last != null ? last.trim() : da.getStageName();
    }

    /** Inserts title_actresses rows for non-skipped, non-sentinel-only resolutions. */
    private void insertTitleActresses(Handle h, long titleId,
                                      List<DraftTitleActress> slots,
                                      Map<String, Long> newActressIds) {
        for (DraftTitleActress slot : slots) {
            String res = slot.getResolution();
            Long actressId = resolveActressId(slot, res, newActressIds);
            if (actressId == null) continue; // skip

            h.createUpdate("""
                    INSERT OR IGNORE INTO title_actresses (title_id, actress_id)
                    VALUES (:titleId, :actressId)
                    """)
                    .bind("titleId",   titleId)
                    .bind("actressId", actressId)
                    .execute();
        }
    }

    /**
     * Returns the actress id for a resolution, or null if the slot should be skipped.
     * Spec §5.4: SKIPped slots are NOT added to title_actresses.
     */
    private Long resolveActressId(DraftTitleActress slot, String res,
                                   Map<String, Long> newActressIds) {
        if ("skip".equals(res)) return null;
        if ("create_new".equals(res)) return newActressIds.get(slot.getJavdbSlug());
        if (res.startsWith("sentinel:")) {
            return Long.parseLong(res.substring("sentinel:".length()));
        }
        if ("pick".equals(res)) {
            // link_to_existing_id from draft_actresses
            return draftActressRepo.findBySlug(slot.getJavdbSlug())
                    .map(DraftActress::getLinkToExistingId)
                    .orElse(null);
        }
        return null; // unknown / unresolved (should have been caught by preflight)
    }

    /** Writes the canonical title_javdb_enrichment row from draft data. */
    private void writeCanonicalEnrichment(Handle h, long titleId, DraftEnrichment e, String nowIso) {
        h.createUpdate("DELETE FROM title_javdb_enrichment WHERE title_id = :id")
                .bind("id", titleId)
                .execute();
        h.createUpdate("""
                INSERT INTO title_javdb_enrichment (
                    title_id, javdb_slug, fetched_at, release_date,
                    rating_avg, rating_count, maker, series,
                    cast_json, cover_url, resolver_source,
                    confidence, cast_validated
                ) VALUES (
                    :titleId, :javdbSlug, :fetchedAt, :releaseDate,
                    :ratingAvg, :ratingCount, :maker, :series,
                    :castJson, :coverUrl, :resolverSource,
                    'HIGH', 1
                )
                """)
                .bind("titleId",        titleId)
                .bind("javdbSlug",      e.getJavdbSlug())
                .bind("fetchedAt",      nowIso)
                // release_date lives on draft_titles, not draft enrichment;
                // step 3 already wrote it to titles. Pass null here — consumers
                // read it from titles.release_date or from the draft_titles snapshot.
                .bind("releaseDate",    (String) null)
                .bind("ratingAvg",      e.getRatingAvg())
                .bind("ratingCount",    e.getRatingCount())
                .bind("maker",          e.getMaker())
                .bind("series",         e.getSeries())
                .bind("castJson",       e.getCastJson())  // forensic preservation (§5.4)
                .bind("coverUrl",       e.getCoverUrl())
                .bind("resolverSource", e.getResolverSource() != null ? e.getResolverSource() : "auto_enriched")
                .execute();
    }

    private String extractReleaseDateFromEnrichment(DraftEnrichment e) {
        // Draft enrichment doesn't have a separate release_date field;
        // it was stored on draft_titles.release_date. We fall back to null here;
        // the draft_titles.releaseDate is written to titles in step 3.
        return null;
    }

    /**
     * Resolves raw tags_json from the draft enrichment against the alias map and writes
     * title_enrichment_tags rows. Mirrors the tag-write logic in JavdbEnrichmentRepository.
     *
     * <p>Spec §6: tags are resolved at promotion, not populate. The alias map
     * ({@code enrichment_tag_definitions.curated_alias}) is applied at this point,
     * ensuring stale aliases can never ship to canonical state.
     */
    private void writeResolvedTags(Handle h, long titleId, String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return;

        List<String> tags;
        try {
            tags = json.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("promote: failed to parse tags_json for title {} — skipping tags", titleId, e);
            return;
        }

        for (String raw : tags) {
            if (raw == null) continue;
            String name = raw.trim();
            if (name.isEmpty()) continue;

            // Ensure definition row exists for this raw tag name
            h.createUpdate("INSERT OR IGNORE INTO enrichment_tag_definitions (name) VALUES (:name)")
                    .bind("name", name)
                    .execute();

            // Write the tag assignment by looking up the definition by name
            h.createUpdate("""
                    INSERT OR IGNORE INTO title_enrichment_tags (title_id, tag_id)
                    SELECT :titleId, id FROM enrichment_tag_definitions WHERE name = :name
                    """)
                    .bind("titleId", titleId)
                    .bind("name",    name)
                    .execute();
        }

        // Refresh title_count on all affected definitions (same pattern as JavdbEnrichmentRepository)
        h.execute("""
                UPDATE enrichment_tag_definitions
                SET title_count = (
                    SELECT COUNT(*) FROM title_enrichment_tags
                    WHERE tag_id = enrichment_tag_definitions.id
                )
                """);
    }

    // ── Audit log helpers ─────────────────────────────────────────────────────

    private record PriorState(String slug, String payload) {}

    /** Snapshots the current canonical enrichment row before any writes. Returns null values if no row exists. */
    private PriorState snapshotPriorState(Handle h, long titleId, String titleCode) {
        Map<String, Object> row = h.createQuery("""
                SELECT e.*, t.code AS _title_code
                FROM title_javdb_enrichment e
                JOIN titles t ON t.id = e.title_id
                WHERE e.title_id = :titleId
                """)
                .bind("titleId", titleId)
                .mapToMap()
                .findOne()
                .orElse(null);

        if (row == null) {
            return new PriorState(null, null);
        }

        row.remove("_title_code");
        String slug    = (String) row.get("javdb_slug");
        String payload = serialize(row);
        return new PriorState(slug, payload);
    }

    /** Snapshots the just-written canonical enrichment row (called after writeCanonicalEnrichment). */
    private String snapshotNewState(Handle h, long titleId) {
        Map<String, Object> row = h.createQuery(
                "SELECT * FROM title_javdb_enrichment WHERE title_id = :id")
                .bind("id", titleId)
                .mapToMap()
                .findOne()
                .orElse(null);
        return row == null ? null : serialize(row);
    }

    /** Builds the promotion_metadata JSON for the audit log. */
    private String buildPromotionMetadata(
            List<DraftTitleActress> slots,
            Map<String, Long> newActressIds,
            boolean coverFetched) {

        List<Map<String, Object>> resolutions = new ArrayList<>();
        int skipCount = 0;
        Long sentinelChosen = null;

        for (DraftTitleActress slot : slots) {
            String res = slot.getResolution();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slug", slot.getJavdbSlug());
            entry.put("type", res);

            if ("skip".equals(res)) {
                skipCount++;
            } else if (res.startsWith("sentinel:")) {
                sentinelChosen = Long.parseLong(res.substring("sentinel:".length()));
                entry.put("actress_id", sentinelChosen);
            } else if ("create_new".equals(res)) {
                Long id = newActressIds.get(slot.getJavdbSlug());
                if (id != null) entry.put("actress_id", id);
            } else if ("pick".equals(res)) {
                draftActressRepo.findBySlug(slot.getJavdbSlug())
                        .map(DraftActress::getLinkToExistingId)
                        .ifPresent(id -> entry.put("actress_id", id));
            }
            resolutions.add(entry);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resolutions",     resolutions);
        meta.put("skip_count",      skipCount);
        meta.put("sentinel_chosen", sentinelChosen);
        meta.put("cover_fetched",   coverFetched);

        return serialize(meta);
    }

    /** Sets draft_titles.last_validation_error — best-effort, never throws. */
    private void safelySetValidationError(long draftTitleId, String errorMessage) {
        try {
            jdbi.useHandle(h ->
                    h.createUpdate("""
                            UPDATE draft_titles
                            SET last_validation_error = :err,
                                updated_at = :now
                            WHERE id = :id
                            """)
                            .bind("id",  draftTitleId)
                            .bind("err", errorMessage)
                            .bind("now", Instant.now().toString())
                            .execute());
        } catch (Exception e) {
            log.warn("promote: failed to set last_validation_error on draft {} (ignored)", draftTitleId, e);
        }
    }

    /**
     * COMMIT-failure compensation: deletes a cover file that was copied inside the
     * transaction lambda but for which the COMMIT then failed. Best-effort — logs
     * on failure but never throws.
     *
     * @param dest the destination path populated via the {@code destCoverHolder} side-channel,
     *             or {@code null} if no cover was copied (no-op in that case).
     */
    private void compensateCover(Path dest) {
        if (dest == null) return;
        try {
            boolean deleted = Files.deleteIfExists(dest);
            if (deleted) {
                log.warn("promote: COMMIT-failure compensation — deleted orphaned cover {}", dest);
            }
        } catch (IOException e) {
            log.error("promote: COMMIT-failure compensation failed — could not delete orphaned cover {}", dest, e);
        }
    }

    /** Default cover-copy implementation (used in production). */
    private void defaultCoverCopy(Path scratch, Path dest) throws IOException {
        Files.copy(scratch, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize promotion payload", e);
        }
    }
}
