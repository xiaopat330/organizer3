package com.organizer3.javdb.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.covers.CoverPath;
import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.javdb.enrichment.EnrichmentHistoryRepository;
import com.organizer3.javdb.enrichment.JavdbStagingRepository;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.translation.NameComposer;
import com.organizer3.translation.repository.StageNameSuggestionRepository;
import com.organizer3.web.CoverWriteService;
import com.organizer3.web.PostCommitSmbExecutor;
import com.organizer3.web.StagingCastHelper;
import com.organizer3.web.TitleFolderRenamer;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Return type of {@link #promote}. Carries the canonical title id and whether the
     * staging folder was successfully renamed post-commit (best-effort; may be false on
     * collision or SMB failure without affecting the committed metadata).
     */
    public record PromotionResult(long titleId, boolean folderRenamed) {}

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
    private final StageNameSuggestionRepository suggestionRepo;
    // FIX 1: javdb_actress_staging repo — used to learn slug→actress mappings at promotion.
    private final JavdbStagingRepository        javdbStagingRepo;
    // FIX 1: actress repo — used to read/backfill stage_name on promoted actresses.
    private final ActressRepository             actressRepo;
    // Phase 2: serviceable staging volumes + folder renamer for post-commit best-effort rename.
    // Multi-volume (spec/PROPOSAL_UNPROCESSED_MULTI_VOLUME.md): curated_at and the cover write
    // target whichever serviceable volume the title actually lives on.
    private final java.util.Set<String>         serviceableVolumeIds;
    private final TitleFolderRenamer            renamer;
    // Best-effort NAS cover write at promotion so the cover rides the folder rename and
    // survives cross-volume redistribution. Nullable — short ctor leaves it null (skips the write).
    private final CoverWriteService             coverWriteService;
    // Kanji-presence guard — checks whether an actress's kanji name appears in the title's
    // cast_json. Nullable — short ctor leaves it null (guard disabled).
    private final com.organizer3.javdb.enrichment.CastPresenceCheck castPresenceCheck;
    // Review queue repo — used to enqueue guard_cast_mismatch entries on divert.
    private final com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository reviewQueueRepo;
    // Age-at-release recomputer — called once per successful promotion. Nullable — short ctor leaves it null.
    private final com.organizer3.db.AgeAtReleaseRecomputer ageRecomputer;
    // Post-commit SMB executor — when present, the NAS cover-write + folder rename are
    // dispatched off the request thread. Nullable — short/legacy-full ctors leave it null,
    // which preserves the original inline behavior (and every existing test unchanged).
    private final PostCommitSmbExecutor postCommitExecutor;

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
            ObjectMapper                  json,
            StageNameSuggestionRepository suggestionRepo) {
        this(jdbi, draftTitleRepo, draftActressRepo, draftCastRepo, draftEnrichRepo,
                coverStore, coverPath, castValidator, titleRepo, historyRepo, effectiveTags,
                json, suggestionRepo, null, null, null, null, null, null, null, null);
    }

    /** Full constructor including FIX 1 dependencies (javdbStagingRepo, actressRepo),
     *  Phase 2 dependencies (unsortedVolumeId, renamer), Item B guard dependencies,
     *  and ageRecomputer for Task 2b.
     *
     *  <p>Delegates to the async-capable constructor with a null {@link PostCommitSmbExecutor}
     *  so existing call sites (incl. {@code Application.java}) keep compiling and get the
     *  inline post-commit SMB path unchanged. */
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
            ObjectMapper                  json,
            StageNameSuggestionRepository suggestionRepo,
            JavdbStagingRepository        javdbStagingRepo,
            ActressRepository             actressRepo,
            java.util.Set<String>         serviceableVolumeIds,
            TitleFolderRenamer            renamer,
            CoverWriteService             coverWriteService,
            com.organizer3.javdb.enrichment.CastPresenceCheck        castPresenceCheck,
            com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository reviewQueueRepo,
            com.organizer3.db.AgeAtReleaseRecomputer ageRecomputer) {
        this(jdbi, draftTitleRepo, draftActressRepo, draftCastRepo, draftEnrichRepo,
                coverStore, coverPath, castValidator, titleRepo, historyRepo, effectiveTags,
                json, suggestionRepo, javdbStagingRepo, actressRepo, serviceableVolumeIds,
                renamer, coverWriteService, castPresenceCheck, reviewQueueRepo, ageRecomputer,
                null);
    }

    /** Async-capable full constructor — identical to the full constructor above, plus a
     *  trailing {@link PostCommitSmbExecutor}. When non-null, {@link #promote} dispatches the
     *  post-commit NAS cover-write + folder rename to the executor instead of running them
     *  inline on the request thread. */
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
            ObjectMapper                  json,
            StageNameSuggestionRepository suggestionRepo,
            JavdbStagingRepository        javdbStagingRepo,
            ActressRepository             actressRepo,
            java.util.Set<String>         serviceableVolumeIds,
            TitleFolderRenamer            renamer,
            CoverWriteService             coverWriteService,
            com.organizer3.javdb.enrichment.CastPresenceCheck        castPresenceCheck,
            com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository reviewQueueRepo,
            com.organizer3.db.AgeAtReleaseRecomputer ageRecomputer,
            PostCommitSmbExecutor        postCommitExecutor) {
        this.jdbi              = jdbi;
        this.draftTitleRepo    = draftTitleRepo;
        this.draftActressRepo  = draftActressRepo;
        this.draftCastRepo     = draftCastRepo;
        this.draftEnrichRepo   = draftEnrichRepo;
        this.coverStore        = coverStore;
        this.coverPath         = coverPath;
        this.castValidator     = castValidator;
        this.titleRepo         = titleRepo;
        this.historyRepo       = historyRepo;
        this.effectiveTags     = effectiveTags;
        this.json              = json;
        this.suggestionRepo    = suggestionRepo;
        this.javdbStagingRepo  = javdbStagingRepo;
        this.actressRepo       = actressRepo;
        this.serviceableVolumeIds = serviceableVolumeIds;
        this.renamer           = renamer;
        this.coverWriteService = coverWriteService;
        this.castPresenceCheck = castPresenceCheck;
        this.reviewQueueRepo   = reviewQueueRepo;
        this.ageRecomputer     = ageRecomputer;
        this.postCommitExecutor = postCommitExecutor;
        this.coverCopier       = this::defaultCoverCopy;
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

        // Check 2: cast mode rule (§5.1)
        List<String> castErrors = castValidator.validate(slots.stream()
                .map(DraftTitleActress::getResolution).toList());
        errors.addAll(castErrors);

        // Check 3: english_last_name required for create_new primary slots.
        // Siblings (link_to_draft_slug IS NOT NULL) reuse the primary's name — no last name needed.
        for (DraftTitleActress slot : slots) {
            if ("create_new".equals(slot.getResolution())) {
                Optional<DraftActress> actress = draftActressRepo.findBySlug(slot.getJavdbSlug());
                if (actress.isEmpty()) {
                    if (!errors.contains("MISSING_ENGLISH_LAST_NAME")) {
                        errors.add("MISSING_ENGLISH_LAST_NAME");
                    }
                } else if (actress.get().getLinkToDraftSlug() == null) {
                    // Only primaries need english_last_name.
                    if (actress.get().getEnglishLastName() == null ||
                            actress.get().getEnglishLastName().isBlank()) {
                        if (!errors.contains("MISSING_ENGLISH_LAST_NAME")) {
                            errors.add("MISSING_ENGLISH_LAST_NAME");
                        }
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
     * COMMIT-failure compensation deletes the copied file. Step 10 (folder rename) runs
     * post-commit, best-effort.
     *
     * @param draftTitleId    the {@code draft_titles.id} to promote.
     * @param expectedUpdatedAt  optimistic-lock token.
     * @return a {@link PromotionResult} with the canonical title id and whether the folder
     *         was renamed post-commit.
     * @throws DraftNotFoundException   if no draft exists.
     * @throws PreFlightFailedException if pre-flight checks fail.
     * @throws OptimisticLockException  if the lock token doesn't match.
     * @throws PromotionException       on transaction-level failure.
     */
    public PromotionResult promote(long draftTitleId, String expectedUpdatedAt) {
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
        // Side-channel for post-commit rename (mirrors destCoverHolder pattern)
        final Long[] primaryHolder = {null};

        long titleId;
        try {
            titleId = jdbi.inTransaction(h -> executePromotionTransaction(
                    h, draftTitleId, expectedUpdatedAt, destCoverHolder, primaryHolder));
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

        // Post-commit: capture the scratch cover bytes NOW (in the request thread), before the
        // scratch delete below, so the NAS cover-write step — whether it runs inline or is
        // dispatched to postCommitExecutor — always has bytes to write and can never race the
        // scratch delete. Gated on destCoverHolder so NAS + local cache stay in lockstep
        // (Step 8 wrote the cache iff destCoverHolder is non-null).
        byte[] coverBytesTmp = null;
        if (destCoverHolder[0] != null) {
            try {
                coverBytesTmp = coverStore.read(draftTitleId).orElse(null);
            } catch (IOException e) {
                log.warn("promote: failed to read scratch cover for NAS write (draft {}): {}",
                        draftTitleId, e.getMessage());
            }
        }
        final byte[] coverBytesForNas = coverBytesTmp;

        // Cover-write confirmation (spec/PROPOSAL_COVER_CONFIRMATION.md): resolve the destination
        // location ONCE here in the request thread — same query the async task used to run — mark
        // it pending pessimistically BEFORE the async dispatch, and capture the resolved values as
        // finals so the task reuses them (no duplicate in-task query). The flag is cleared only
        // when the NAS write completes without throwing; a dropped/crashed/failed write leaves the
        // row pending for PromotionCoverReconciler to heal. Only mark when a cover actually exists
        // to write and a serviceable location resolves.
        Long   coverLocIdTmp   = null;
        String coverDestVolTmp = null;
        String coverDestPathTmp = null;
        if (coverBytesForNas != null && coverWriteService != null
                && serviceableVolumeIds != null && !serviceableVolumeIds.isEmpty()) {
            // Resolve the staging volume AND path (plus row id) in one query so all originate from
            // the same resolution act — the cover must be written to whichever serviceable volume
            // the title actually lives on, never a differently-sourced volume.
            Object[] loc = jdbi.withHandle(h -> h.createQuery(
                    "SELECT id AS id, volume_id AS v, path AS p FROM title_locations " +
                    "WHERE title_id = :tid AND volume_id IN (<vols>) AND stale_since IS NULL " +
                    "ORDER BY added_date ASC, volume_id ASC LIMIT 1")
                    .bind("tid", titleId)
                    .bindList("vols", java.util.List.copyOf(serviceableVolumeIds))
                    .map((rs, ctx) -> new Object[]{ rs.getLong("id"), rs.getString("v"), rs.getString("p") })
                    .findFirst().orElse(null));
            if (loc != null && loc[2] != null) {
                coverLocIdTmp    = (Long) loc[0];
                coverDestVolTmp  = (String) loc[1];
                coverDestPathTmp = (String) loc[2];
                final long locId = coverLocIdTmp;
                final String pendingNow = java.time.Instant.now().toString();
                jdbi.useHandle(h -> h.createUpdate(
                        "UPDATE title_locations SET cover_pending_since = :now WHERE id = :id")
                        .bind("now", pendingNow)
                        .bind("id", locId)
                        .execute());
            }
        }
        final Long   coverLocId    = coverLocIdTmp;
        final String coverDestVol  = coverDestVolTmp;
        final String coverDestPath = coverDestPathTmp;

        // Post-commit Step 10 (Phase 2) + NAS cover write: a single Runnable performing, in
        // order, (a) the best-effort NAS cover write — so the cover rides the folder rename and
        // survives cross-volume redistribution — then (b) the best-effort folder rename. The
        // rename builds the ordered multi-name list from canonical DB state (post-commit) so the
        // folder name includes ALL credited cast, with the filing actress first. Dispatched to
        // postCommitExecutor when present (async — returns immediately after COMMIT); run inline
        // otherwise (current/test behavior). renamedHolder lets the inline path recover the real
        // outcome; the async path cannot know it synchronously.
        final boolean[] renamedHolder = {false};
        Runnable folderOps = () -> {
            // (a) NAS cover write. Pre-rename path is intentional — the folder rename below
            // moves the cover along. Reuse the location resolved in the request thread (coverLocId
            // is non-null iff a serviceable location was found and marked pending above).
            if (coverBytesForNas != null && coverWriteService != null && coverLocId != null) {
                try {
                    Title t = titleRepo.findById(titleId).orElse(null);
                    if (t != null) {
                        boolean ok = coverWriteService.saveToNasBestEffort(
                                coverDestPath, t.getBaseCode(), coverBytesForNas, coverDestVol);
                        if (ok) {
                            // Confirmed: the NAS write completed without throwing — clear the flag.
                            jdbi.useHandle(h -> h.createUpdate(
                                    "UPDATE title_locations SET cover_pending_since = NULL WHERE id = :id")
                                    .bind("id", coverLocId)
                                    .execute());
                        }
                        // on !ok: leave pending — PromotionCoverReconciler heals it.
                    }
                } catch (Exception e) {
                    log.warn("promote: NAS cover write failed post-commit (titleId={}): {}", titleId, e.getMessage());
                }
            }

            // (b) Folder rename.
            if (primaryHolder[0] != null && renamer != null) {
                try {
                    java.util.List<String> castNames =
                            StagingCastHelper.orderedNamesForTitle(jdbi, titleId);
                    if (!castNames.isEmpty()) {
                        TitleFolderRenamer.RenameOutcome outcome =
                                renamer.renamePreservingDescriptor(titleId, castNames, draftCheck.getCode());
                        renamedHolder[0] = outcome.renamed();
                    } else {
                        log.warn("promote: no cast names found for rename (titleId={}, code={}); skipping rename",
                                titleId, draftCheck.getCode());
                    }
                } catch (IllegalStateException e) {
                    log.warn("promote: folder rename skipped — collision (titleId={}, code={}): {}",
                            titleId, draftCheck.getCode(), e.getMessage());
                } catch (Exception e) {
                    log.warn("promote: folder rename failed post-commit (titleId={}, code={}): {}",
                            titleId, draftCheck.getCode(), e.getMessage(), e);
                }
            }
        };

        boolean folderRenamed;
        if (postCommitExecutor != null) {
            postCommitExecutor.submit("promote:" + draftCheck.getCode(), folderOps);
            folderRenamed = false; // dispatched async — outcome cannot be reported synchronously
        } else {
            folderOps.run();
            folderRenamed = renamedHolder[0];
        }

        // Post-commit: delete scratch cover — best-effort; GC sweep catches leaks.
        // Safe even when folderOps was just dispatched async — coverBytesForNas was already
        // captured above, so the async NAS write can't race this delete.
        try {
            coverStore.delete(draftTitleId);
        } catch (IOException e) {
            log.warn("promote: failed to delete scratch cover for draft {} (non-fatal)", draftTitleId, e);
        }

        // Post-commit: recompute age_at_release for all title_actresses rows.
        // The newly promoted title may have a release date that was previously unavailable.
        if (ageRecomputer != null) {
            try {
                int changed = ageRecomputer.recomputeAll();
                log.info("promote age_at_release recompute: {} rows changed (titleId={})", changed, titleId);
            } catch (Exception e) {
                log.warn("promote: age_at_release recompute failed for titleId={} (non-fatal)", titleId, e);
            }
        }

        log.info("draft promoted: draftId={} → titleId={} folderRenamed={}", draftTitleId, titleId, folderRenamed);
        return new PromotionResult(titleId, folderRenamed);
    }

    // ── Transaction body ─────────────────────────────────────────────────────

    private long executePromotionTransaction(
            Handle h, long draftTitleId, String expectedUpdatedAt,
            Path[] destCoverHolder, Long[] primaryHolder) throws IOException {

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

        // Load cast slots in deterministic rowid order (for primary-actress selection).
        List<DraftTitleActress> slots = h.createQuery(
                "SELECT * FROM draft_title_actresses WHERE draft_title_id = :id ORDER BY rowid")
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

        // ── Primary actress selection (Phase 2) ──────────────────────────────────
        // Iterate slots in rowid order; prefer the first non-sentinel resolved slot
        // (pick or create_new); fall back to the first sentinel slot; skip/unresolved → null.
        Long primaryActressId = null;
        Long firstSentinelId  = null;
        for (DraftTitleActress slot : slots) {
            String res = slot.getResolution();
            if (res == null) continue;
            if ("skip".equals(res)) continue;
            Long resolved = resolveActressId(slot, res, newActressIds);
            if (resolved == null) continue;
            if (res.startsWith("sentinel:")) {
                if (firstSentinelId == null) firstSentinelId = resolved;
            } else {
                // pick or create_new — non-sentinel preferred
                primaryActressId = resolved;
                break;
            }
        }
        if (primaryActressId == null) {
            primaryActressId = firstSentinelId; // may still be null if all-skip (not reachable in production)
        }
        // Publish to the side-channel for post-commit use (mirrors destCoverHolder pattern)
        primaryHolder[0] = primaryActressId;

        // ── Step 3: UPDATE titles row from draft ─────────────────────────────────
        h.createUpdate("""
                UPDATE titles SET
                    title_original = :titleOriginal,
                    title_english  = :titleEnglish,
                    release_date   = :releaseDate,
                    notes          = :notes,
                    grade          = :grade,
                    grade_source   = 'enrichment',
                    actress_id     = COALESCE(:primaryActressId, actress_id)
                WHERE id = :id
                """)
                .bind("titleOriginal",    draft.getTitleOriginal())
                .bind("titleEnglish",     draft.getTitleEnglish())
                .bind("releaseDate",      draft.getReleaseDate())
                .bind("notes",            draft.getNotes())
                .bind("grade",            draft.getGrade())
                .bind("primaryActressId", primaryActressId)
                .bind("id",               canonicalTitleId)
                .execute();

        // ── Step 3b: bookmark-on-promote (additive — never clears an existing bookmark) ──
        // bookmarked_at MUST be a LocalDateTime string (no 'Z'/offset): titles.bookmarked_at is
        // read back via Title.ROW_MAPPER's LocalDateTime.parse (incl. the in-txn read at Step 8),
        // and the canonical toggleBookmark path stores LocalDateTime.now().toString(). Do NOT use
        // nowIso (Instant, trailing 'Z') here — it is unparseable and rolls the promotion back.
        if (draft.isBookmarkOnPromote()) {
            h.createUpdate("UPDATE titles SET bookmark = 1, bookmarked_at = :now WHERE id = :id")
                    .bind("now", LocalDateTime.now().toString())
                    .bind("id", canonicalTitleId)
                    .execute();
        }

        // ── Step 4: Replace title_actresses cast ─────────────────────────────────
        h.createUpdate("DELETE FROM title_actresses WHERE title_id = :id")
                .bind("id", canonicalTitleId)
                .execute();
        insertTitleActresses(h, canonicalTitleId, slots, newActressIds, draft.getCode(), enrichment.getCastJson());

        // ── Step 5: INSERT OR REPLACE title_javdb_enrichment ─────────────────────
        // Pass the draft's Japanese/English titles so the enrichment row carries title_original
        // (the column the translation sweeper reads). See writeCanonicalEnrichment for why.
        // release_date is read from draft.getReleaseDate() (draft_titles.release_date) rather
        // than duplicated onto DraftEnrichment — it was already being written there at draft
        // creation (see DraftPopulator.writeDraft) and step 3 above already copied it onto
        // titles.release_date, so this is the same value, not a second source of truth.
        writeCanonicalEnrichment(h, canonicalTitleId, enrichment, nowIso,
                draft.getTitleOriginal(), draft.getTitleEnglish(), draft.getReleaseDate());

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

        // ── Step 9b: Stamp curated_at on the staging-volume location ─────────────
        // Written inside the transaction so it is durable and atomic with the metadata writes.
        // The scope guard (volume_id = :vol AND stale_since IS NULL) is a no-op when the
        // title has no live staging-volume location (e.g. a library title — both paths no-op).
        // Inlined here rather than injecting UnsortedEditorRepository into DraftPromotionService
        // to avoid constructor churn; the repo defines markCurated as the canonical SQL.
        if (serviceableVolumeIds != null && !serviceableVolumeIds.isEmpty()) {
            h.createUpdate("""
                    UPDATE title_locations SET curated_at = :now
                    WHERE title_id = :titleId AND volume_id IN (<volumeIds>) AND stale_since IS NULL
                    """)
                    .bind("now",      nowIso)
                    .bind("titleId",  canonicalTitleId)
                    .bindList("volumeIds", java.util.List.copyOf(serviceableVolumeIds))
                    .execute();
        }

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

        List<String> castErrors = castValidator.validate(slots.stream()
                .map(DraftTitleActress::getResolution).toList());
        errors.addAll(castErrors);

        for (DraftTitleActress slot : slots) {
            if ("create_new".equals(slot.getResolution())) {
                Optional<DraftActress> actress = h.createQuery(
                        "SELECT * FROM draft_actresses WHERE javdb_slug = :slug")
                        .bind("slug", slot.getJavdbSlug())
                        .map(DraftActressRepository.ROW_MAPPER)
                        .findOne();
                if (actress.isEmpty()) {
                    if (!errors.contains("MISSING_ENGLISH_LAST_NAME")) {
                        errors.add("MISSING_ENGLISH_LAST_NAME");
                    }
                } else if (actress.get().getLinkToDraftSlug() == null) {
                    // Only primaries need english_last_name.
                    if (actress.get().getEnglishLastName() == null ||
                            actress.get().getEnglishLastName().isBlank()) {
                        if (!errors.contains("MISSING_ENGLISH_LAST_NAME")) {
                            errors.add("MISSING_ENGLISH_LAST_NAME");
                        }
                    }
                }
            }
        }

        return errors.isEmpty() ? PreFlightResult.success() : PreFlightResult.failure(errors);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Inserts new actress rows for create_new resolutions; returns slug→newId map.
     *
     * <p>Three cases per slot:
     * <ul>
     *   <li><b>Primary</b> ({@code link_to_draft_slug IS NULL}) — inserts a new actresses row
     *       and seeds aliases.</li>
     *   <li><b>Sibling-of-primary-in-batch</b> ({@code link_to_draft_slug} points to another slug
     *       in this batch) — reuses the primary's freshly-allocated actress id.</li>
     *   <li><b>Orphan sibling</b> ({@code link_to_draft_slug} non-null but not in this batch) —
     *       re-elects: oldest orphan becomes primary, others resolve to it. Re-election rewrites
     *       {@code link_to_draft_slug} globally for future orphans.</li>
     * </ul>
     */
    private Map<String, Long> insertNewActresses(Handle h, List<DraftTitleActress> slots, String nowIso) {
        // Collect the set of all create_new slugs in this batch.
        Set<String> batchSlugs = slots.stream()
                .filter(s -> "create_new".equals(s.getResolution()))
                .map(DraftTitleActress::getJavdbSlug)
                .collect(Collectors.toSet());

        // Load all draft_actress rows for this batch up front.
        Map<String, DraftActress> daBySlug = new HashMap<>();
        for (String slug : batchSlugs) {
            DraftActress da = h.createQuery(
                    "SELECT * FROM draft_actresses WHERE javdb_slug = :slug")
                    .bind("slug", slug)
                    .map(DraftActressRepository.ROW_MAPPER)
                    .findOne()
                    .orElseThrow(() -> new PromotionException("missing_draft_actress",
                            "No draft_actress for slug=" + slug));
            daBySlug.put(slug, da);
        }

        Map<String, Long> result = new HashMap<>();

        for (DraftTitleActress slot : slots) {
            if (!"create_new".equals(slot.getResolution())) continue;
            if (result.containsKey(slot.getJavdbSlug())) continue; // already resolved

            DraftActress da = daBySlug.get(slot.getJavdbSlug());
            String linkToDraft = da.getLinkToDraftSlug();

            if (linkToDraft == null) {
                // Primary: insert actress + alias rebuild.
                long actressId = insertPrimaryActress(h, da, nowIso);
                result.put(slot.getJavdbSlug(), actressId);

            } else if (batchSlugs.contains(linkToDraft)) {
                // Sibling whose primary is also in this batch — defer; primary must come first.
                // Handled in the second pass below.

            } else {
                // Orphan sibling: primary was deleted before promotion. Re-elect among orphans.
                resolveOrphanSiblings(h, da, slot.getJavdbSlug(), linkToDraft, daBySlug, batchSlugs, result, nowIso);
            }
        }

        // Second pass: resolve siblings whose primary is now in result.
        for (DraftTitleActress slot : slots) {
            if (!"create_new".equals(slot.getResolution())) continue;
            if (result.containsKey(slot.getJavdbSlug())) continue;

            DraftActress da = daBySlug.get(slot.getJavdbSlug());
            String linkToDraft = da.getLinkToDraftSlug();
            if (linkToDraft != null && batchSlugs.contains(linkToDraft)) {
                Long primaryId = result.get(linkToDraft);
                if (primaryId == null) {
                    throw new PromotionException("sibling_resolution_failed",
                            "Primary slug=" + linkToDraft + " not resolved before sibling slug=" + slot.getJavdbSlug());
                }
                result.put(slot.getJavdbSlug(), primaryId);
            }
        }

        return result;
    }

    private long insertPrimaryActress(Handle h, DraftActress da, String nowIso) {
        String canonicalName = buildCanonicalName(da);
        long actressId = h.createUpdate("""
                INSERT INTO actresses
                    (canonical_name, stage_name, tier, first_seen_at,
                     created_via, created_at)
                VALUES
                    (:canonicalName, :stageName, 'LIBRARY', :firstSeenAt,
                     'draft_promotion', :createdAt)
                """)
                .bind("canonicalName", canonicalName)
                .bind("stageName",     da.getStageName())
                .bind("firstSeenAt",   nowIso.substring(0, 10))   // date-only: actresses.first_seen_at maps to LocalDate
                .bind("createdAt",     nowIso)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long.class)
                .one();

        seedAliases(h, actressId, canonicalName, da);

        log.info("promote: created new actress id={} name='{}' via draft_promotion", actressId, canonicalName);
        return actressId;
    }

    /**
     * Re-elects among orphan siblings sharing the same dead {@code link_to_draft_slug} value.
     * The orphan with the lowest {@code created_at} becomes the new primary; others link to it.
     * Rewrites {@code link_to_draft_slug} globally on the dead slug so future orphans don't
     * re-elect again.
     */
    private void resolveOrphanSiblings(
            Handle h,
            DraftActress initialOrphan,
            String initialOrphanSlug,
            String deadPrimarySlug,
            Map<String, DraftActress> daBySlug,
            Set<String> batchSlugs,
            Map<String, Long> result,
            String nowIso) {

        // Collect all in-batch orphans sharing the same dead primary slug.
        List<DraftActress> orphans = new ArrayList<>();
        for (String slug : batchSlugs) {
            DraftActress da = daBySlug.get(slug);
            if (deadPrimarySlug.equals(da.getLinkToDraftSlug())) {
                orphans.add(da);
            }
        }

        // Elect the oldest as new primary.
        orphans.sort((a, b) -> {
            String ca = a.getCreatedAt() != null ? a.getCreatedAt() : "";
            String cb = b.getCreatedAt() != null ? b.getCreatedAt() : "";
            return ca.compareTo(cb);
        });
        DraftActress elected = orphans.get(0);

        long primaryId = insertPrimaryActress(h, elected, nowIso);
        result.put(elected.getJavdbSlug(), primaryId);

        // Remaining orphans resolve to the elected primary.
        for (DraftActress orphan : orphans) {
            if (!orphan.getJavdbSlug().equals(elected.getJavdbSlug())) {
                result.put(orphan.getJavdbSlug(), primaryId);
            }
        }

        // Rewrite link_to_draft_slug globally for any surviving siblings pointing at the dead primary,
        // so future promotions know the elected primary without re-electing.
        // Exclude the elected primary itself to prevent a self-loop (primary must stay NULL).
        h.createUpdate("""
                UPDATE draft_actresses
                SET link_to_draft_slug = :newPrimary
                WHERE link_to_draft_slug = :deadPrimary
                  AND javdb_slug != :newPrimary
                """)
                .bind("newPrimary", elected.getJavdbSlug())
                .bind("deadPrimary", deadPrimarySlug)
                .execute();

        // Ensure the elected primary has link_to_draft_slug = NULL (it was pointing at the dead slug).
        h.createUpdate("""
                UPDATE draft_actresses
                SET link_to_draft_slug = NULL
                WHERE javdb_slug = :slug
                """)
                .bind("slug", elected.getJavdbSlug())
                .execute();
    }

    /**
     * Seeds {@code actress_aliases} from draft data for a newly-inserted primary actress.
     *
     * <p>Candidates:
     * <ul>
     *   <li>Kanji form — {@code draft_actresses.stage_name} (NFKC-normalized post-Slice C).</li>
     *   <li>LLM romaji — {@code stage_name_suggestion.suggested_romaji} via
     *       {@link StageNameSuggestionRepository#findLatestUsableSuggestion}.</li>
     *   <li>User-edited romaji — composed from {@code english_first_name}/{@code english_last_name}
     *       matching {@link #buildCanonicalName}.</li>
     * </ul>
     *
     * <p>Each candidate that differs from {@code canonicalName} is inserted with
     * {@code INSERT OR IGNORE}, making this idempotent.
     */
    private void seedAliases(Handle h, long actressId, String canonicalName, DraftActress da) {
        List<String> candidates = new ArrayList<>();

        if (da.getStageName() != null && !da.getStageName().isBlank()) {
            candidates.add(da.getStageName());
        }

        if (da.getStageName() != null) {
            suggestionRepo.findLatestUsableSuggestion(da.getStageName())
                    .ifPresent(candidates::add);
        }

        String composed = buildCanonicalName(da);
        candidates.add(composed);

        for (String alias : candidates) {
            if (alias == null || alias.isBlank()) continue;
            if (alias.equals(canonicalName)) continue;
            h.createUpdate("""
                    INSERT OR IGNORE INTO actress_aliases (actress_id, alias_name)
                    VALUES (:actressId, :aliasName)
                    """)
                    .bind("actressId", actressId)
                    .bind("aliasName", alias)
                    .execute();
        }
    }

    private String buildCanonicalName(DraftActress da) {
        String last = da.getEnglishLastName();
        if (last != null && !last.isBlank()) {
            return NameComposer.compose(da.getEnglishFirstName(), last);
        }
        return da.getStageName();
    }

    /**
     * Inserts title_actresses rows for non-skipped, non-sentinel-only resolutions.
     *
     * <p>FIX 1: For every slot that resolves to an actress (pick or create_new), within this
     * same promotion transaction:
     * <ul>
     *   <li>Registers the javdb slug→actress mapping in {@code javdb_actress_staging} via
     *       {@link JavdbStagingRepository#upsertActressSlugOnly}. On slug-collision (slug already
     *       owned by a different actress) the upsert is skipped — promotion still succeeds.</li>
     *   <li>Backfills {@code actresses.stage_name} from the draft_actress row's kanji
     *       stage_name, but ONLY when the actress currently has no stage_name set (never
     *       overwrites an existing value).</li>
     * </ul>
     */
    private void insertTitleActresses(Handle h, long titleId,
                                      List<DraftTitleActress> slots,
                                      Map<String, Long> newActressIds,
                                      String titleCode,
                                      String castJson) {
        // castJson comes from the draft enrichment row (already loaded before step 4).
        // We intentionally do NOT read from title_javdb_enrichment here because that row
        // is written in step 5, AFTER this method returns.

        // Evaluate guard activation once per title (before the loop).
        boolean guardActive = castPresenceCheck != null
                && castJson != null
                && castPresenceCheck.guardEnforced(titleId, castJson);

        for (DraftTitleActress slot : slots) {
            String res = slot.getResolution();
            Long actressId = resolveActressId(slot, res, newActressIds);
            if (actressId == null) continue; // skip

            // ── KANJI-PRESENCE GUARD (Item B) ────────────────────────────────────────
            // Run only for slug/fuzzy/null (legacy) resolutions, on small non-comp casts.
            // EXEMPT: canonical, alias, stage_name, manual, create_new, sentinel:*, skip.
            String via = slot.getResolvedVia();
            boolean guardedVia = "slug".equals(via) || "fuzzy".equals(via) || via == null;
            // create_new and sentinel:* are also exempt by resolution type — the actress was
            // explicitly created from this cast entry or selected via sentinel, so there is no
            // attribution ambiguity that the kanji check can resolve.
            boolean exemptByResolution = "create_new".equals(res) || res.startsWith("sentinel:");
            boolean shouldGuard = guardedVia && !exemptByResolution;

            if (shouldGuard && castPresenceCheck != null && castJson != null) {
                com.organizer3.javdb.enrichment.CastPresenceCheck.Result presence =
                        castPresenceCheck.check(actressId, castJson);
                if (presence == com.organizer3.javdb.enrichment.CastPresenceCheck.Result.ABSENT
                        && guardActive) {
                    // DIVERT: skip FIX 1a, FIX 1b, and the title_actresses insert.
                    int nfem = castPresenceCheck.countFemales(castJson);
                    List<String> castNames = castPresenceCheck.extractCastNames(castJson);
                    String actressName = fetchActressName(h, actressId);
                    String stageName = fetchActressStageName(h, actressId);
                    String detailJson = buildGuardDetail(actressId, actressName, stageName, via, nfem, castNames);
                    log.warn("promote: guard_cast_mismatch: title={} code={} actress={} (id={}) via={} not in cast {}",
                            titleId, titleCode, actressName, actressId, via, castNames);
                    if (reviewQueueRepo != null) {
                        reviewQueueRepo.enqueueWithDetail(titleId, slot.getJavdbSlug(),
                                "guard_cast_mismatch", "promotion_guard", detailJson, h);
                    }
                    continue; // skip FIX 1a, FIX 1b, and the title_actresses insert
                } else if (presence == com.organizer3.javdb.enrichment.CastPresenceCheck.Result.ABSENT
                        || presence == com.organizer3.javdb.enrichment.CastPresenceCheck.Result.UNCHECKABLE) {
                    // Gated out (comp-tagged or nfem>=4) or uncheckable — attribute normally, log WARN.
                    int nfem = castPresenceCheck.countFemales(castJson);
                    List<String> castNames = castPresenceCheck.extractCastNames(castJson);
                    String actressName = fetchActressName(h, actressId);
                    String stageName = fetchActressStageName(h, actressId);
                    String detailJson = buildGuardDetail(actressId, actressName, stageName, via, nfem, castNames);
                    log.warn("promote: guard not enforced ({}): title={} actress={} (id={}) via={} detail={}",
                            presence, titleId, actressName, actressId, via, detailJson);
                    // fall through to attribute normally
                }
                // PRESENT: attribute normally, no log needed
            }

            h.createUpdate("""
                    INSERT OR IGNORE INTO title_actresses (title_id, actress_id)
                    VALUES (:titleId, :actressId)
                    """)
                    .bind("titleId",   titleId)
                    .bind("actressId", actressId)
                    .execute();

            // FIX 1a: Register slug→actress mapping in javdb_actress_staging.
            // Slug is not available for sentinel: resolutions (no draft_actress row backing them).
            // Synthetic slugs (manual:N from the draft editor) must also be excluded — they are
            // not real javdb slugs and must never be written into javdb_actress_staging.
            String javdbSlug = slot.getJavdbSlug();
            if (javdbStagingRepo != null && javdbSlug != null && !javdbSlug.isBlank()
                    && !res.startsWith("sentinel:") && !isSyntheticSlug(javdbSlug)) {
                javdbStagingRepo.upsertActressSlugOnly(h, actressId, javdbSlug, titleCode);
                // (returns false on slug-collision → already logged by the repo; we just continue)
            }

            // FIX 1b: Backfill actresses.stage_name from the draft kanji name, only when empty.
            // draft_actresses.stage_name holds the NFKC-normalised kanji from javdb.
            if (actressRepo != null && javdbSlug != null && !javdbSlug.isBlank()
                    && !res.startsWith("sentinel:") && !isSyntheticSlug(javdbSlug)) {
                DraftActress da = h.createQuery(
                        "SELECT * FROM draft_actresses WHERE javdb_slug = :slug")
                        .bind("slug", javdbSlug)
                        .map(DraftActressRepository.ROW_MAPPER)
                        .findOne()
                        .orElse(null);
                if (da != null) {
                    String kanjiStageName = da.getStageName();
                    if (kanjiStageName != null && !kanjiStageName.isBlank()) {
                        // Only write when the actress currently has no stage_name.
                        int updated = h.createUpdate("""
                                UPDATE actresses
                                SET stage_name = :stageName
                                WHERE id = :id
                                  AND (stage_name IS NULL OR stage_name = '')
                                  AND is_sentinel = 0
                                """)
                                .bind("stageName", kanjiStageName)
                                .bind("id",        actressId)
                                .execute();
                        if (updated > 0) {
                            log.info("promote: backfilled stage_name='{}' for actress id={}",
                                    kanjiStageName, actressId);
                        }
                    }
                }
            }
        }
    }

    private String fetchActressName(Handle h, long actressId) {
        return h.createQuery("SELECT canonical_name FROM actresses WHERE id = :id")
                .bind("id", actressId).mapTo(String.class).findOne().orElse("unknown");
    }

    private String fetchActressStageName(Handle h, long actressId) {
        return h.createQuery("SELECT stage_name FROM actresses WHERE id = :id")
                .bind("id", actressId).mapTo(String.class).findOne().orElse(null);
    }

    private String buildGuardDetail(long actressId, String actressName, String stageName,
                                     String resolvedVia, int nfem, List<String> castNames) {
        try {
            return json.writeValueAsString(Map.of(
                    "actressId",   actressId,
                    "actressName", actressName != null ? actressName : "",
                    "stageName",   stageName   != null ? stageName   : "",
                    "resolvedVia", resolvedVia != null ? resolvedVia : "",
                    "nfem",        nfem,
                    "castNames",   castNames));
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Returns true if the slug is a synthetic manual slug (e.g. {@code "manual:1"}) generated
     * by the draft editor for manually-added slots. Such slugs are not real javdb slugs and
     * must never be written into {@code javdb_actress_staging}.
     */
    private static boolean isSyntheticSlug(String slug) {
        return slug != null && slug.startsWith("manual:");
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
    private void writeCanonicalEnrichment(Handle h, long titleId, DraftEnrichment e, String nowIso,
                                          String titleOriginal, String titleEnglish, String releaseDate) {
        // The background translation sweeper (JavdbEnrichmentRepository.findTitlesAwaitingTranslation)
        // reads title_javdb_enrichment.title_original — NOT titles.title_original — to decide what to
        // enqueue. If we omit it here, a draft-promoted title's Japanese title only lands in
        // titles.title_original (via Step 3) and is never picked up for translation, so it never gets
        // an English title. We must mirror the draft's Japanese title into this column so the sweeper
        // sees it, exactly as the direct-enrichment path does.
        //
        // title_original_en: if the user already typed an English title in the draft, store it so the
        // sweeper's non-empty title_original_en guard SKIPS auto-translation (user's English wins).
        // Otherwise leave NULL so the sweeper auto-translates, matching the direct path.
        String titleEnglishOrNull = (titleEnglish != null && !titleEnglish.isBlank()) ? titleEnglish : null;
        h.createUpdate("DELETE FROM title_javdb_enrichment WHERE title_id = :id")
                .bind("id", titleId)
                .execute();
        h.createUpdate("""
                INSERT INTO title_javdb_enrichment (
                    title_id, javdb_slug, fetched_at, release_date,
                    rating_avg, rating_count, maker, publisher, series,
                    duration_minutes, cast_json, cover_url, resolver_source,
                    title_original, title_original_en,
                    confidence, cast_validated
                ) VALUES (
                    :titleId, :javdbSlug, :fetchedAt, :releaseDate,
                    :ratingAvg, :ratingCount, :maker, :publisher, :series,
                    :durationMinutes, :castJson, :coverUrl, :resolverSource,
                    :titleOriginal, :titleOriginalEn,
                    'HIGH', 1
                )
                """)
                .bind("titleId",         titleId)
                .bind("javdbSlug",       e.getJavdbSlug())
                .bind("fetchedAt",       nowIso)
                .bind("releaseDate",     releaseDate)
                .bind("ratingAvg",       e.getRatingAvg())
                .bind("ratingCount",     e.getRatingCount())
                .bind("maker",           e.getMaker())
                .bind("publisher",       e.getPublisher())
                .bind("series",          e.getSeries())
                .bind("durationMinutes", e.getDurationMinutes())
                .bind("castJson",        e.getCastJson())  // forensic preservation (§5.4)
                .bind("coverUrl",        e.getCoverUrl())
                .bind("resolverSource",  e.getResolverSource() != null ? e.getResolverSource() : "auto_enriched")
                // Bind title_original as-is (may be null/blank in odd cases — the sweeper's own
                // IS NOT NULL / != '' predicate handles empties).
                .bind("titleOriginal",   titleOriginal)
                .bind("titleOriginalEn", titleEnglishOrNull)
                .execute();
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
