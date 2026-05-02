package com.organizer3.javdb.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.javdb.JavdbClient;
import com.organizer3.javdb.enrichment.CastMatcher;
import com.organizer3.javdb.enrichment.JavdbExtractor;
import com.organizer3.javdb.enrichment.JavdbSlugResolver;
import com.organizer3.javdb.enrichment.JavdbStagingRepository;
import com.organizer3.javdb.enrichment.TitleExtract;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.web.ImageFetcher;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Populates a new draft for a canonical title by fetching live data from javdb
 * and running canonicalization passes 1-3 on each cast slot.
 *
 * <h3>Outcome of a successful {@link #populate} call</h3>
 * <ul>
 *   <li>One new {@code draft_titles} row (PK = the returned draft id).
 *   <li>One new {@code draft_title_javdb_enrichment} row.
 *   <li>For each javdb cast entry: one {@code draft_actresses} row (upsert) and one
 *       {@code draft_title_actresses} row whose {@code resolution} is either
 *       {@code 'pick'} (auto-linked by passes 1-3) or {@code 'unresolved'}.
 * </ul>
 *
 * <h3>Guard: existing draft</h3>
 * If a draft already exists for the requested title, {@link #populate} returns
 * {@link PopulateResult#alreadyExists()} (caller maps to HTTP 409).
 *
 * <h3>Failure handling</h3>
 * If any step after creating the {@code draft_titles} row fails, the row is
 * deleted, which cascades to all child rows.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §5.3 and §13.
 */
public class DraftPopulator {

    private static final Logger log = LoggerFactory.getLogger(DraftPopulator.class);

    /** Auto-link resolution written by passes 1-3. */
    static final String RESOLUTION_PICK       = "pick";
    /** Cast slot that could not be auto-linked; user must resolve in editor. */
    static final String RESOLUTION_UNRESOLVED = "unresolved";

    private final TitleRepository          titleRepo;
    private final ActressRepository        actressRepo;
    private final JavdbSlugResolver        slugResolver;
    private final JavdbClient              javdbClient;
    private final JavdbExtractor           extractor;
    private final JavdbStagingRepository   stagingRepo;
    private final DraftTitleRepository     draftTitleRepo;
    private final DraftActressRepository   draftActressRepo;
    private final DraftTitleActressesRepository draftCastRepo;
    private final DraftTitleEnrichmentRepository draftEnrichRepo;
    private final DraftCoverScratchStore   coverStore;
    private final ImageFetcher             imageFetcher;
    private final ObjectMapper             json;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DraftPopulator(
            TitleRepository          titleRepo,
            ActressRepository        actressRepo,
            JavdbSlugResolver        slugResolver,
            JavdbClient              javdbClient,
            JavdbExtractor           extractor,
            JavdbStagingRepository   stagingRepo,
            DraftTitleRepository     draftTitleRepo,
            DraftActressRepository   draftActressRepo,
            DraftTitleActressesRepository draftCastRepo,
            DraftTitleEnrichmentRepository draftEnrichRepo,
            DraftCoverScratchStore   coverStore,
            ImageFetcher             imageFetcher,
            ObjectMapper             json) {
        this.titleRepo        = titleRepo;
        this.actressRepo      = actressRepo;
        this.slugResolver     = slugResolver;
        this.javdbClient      = javdbClient;
        this.extractor        = extractor;
        this.stagingRepo      = stagingRepo;
        this.draftTitleRepo   = draftTitleRepo;
        this.draftActressRepo = draftActressRepo;
        this.draftCastRepo    = draftCastRepo;
        this.draftEnrichRepo  = draftEnrichRepo;
        this.coverStore       = coverStore;
        this.imageFetcher     = imageFetcher;
        this.json             = json;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Result discriminant returned by {@link #populate}. */
    public enum Status {
        /** Draft was created successfully. */
        CREATED,
        /** A draft already exists for this title. */
        ALREADY_EXISTS,
        /** The canonical title was not found. */
        TITLE_NOT_FOUND,
        /** javdb returned no match for the title code. */
        JAVDB_NOT_FOUND,
        /** javdb fetch or extraction failed. */
        JAVDB_ERROR
    }

    public record PopulateResult(Status status, Long draftTitleId) {

        static PopulateResult created(long draftTitleId) {
            return new PopulateResult(Status.CREATED, draftTitleId);
        }

        static PopulateResult alreadyExists() {
            return new PopulateResult(Status.ALREADY_EXISTS, null);
        }

        static PopulateResult titleNotFound() {
            return new PopulateResult(Status.TITLE_NOT_FOUND, null);
        }

        static PopulateResult javdbNotFound() {
            return new PopulateResult(Status.JAVDB_NOT_FOUND, null);
        }

        static PopulateResult javdbError() {
            return new PopulateResult(Status.JAVDB_ERROR, null);
        }
    }

    /**
     * Fetches javdb data for the given canonical {@code titleId} and creates a
     * new draft row.
     *
     * @param titleId canonical {@code titles.id}
     * @return a {@link PopulateResult} describing the outcome
     */
    public PopulateResult populate(long titleId) {
        // Guard: canonical title must exist.
        Optional<Title> maybeTitle = titleRepo.findById(titleId);
        if (maybeTitle.isEmpty()) {
            log.warn("draft: populate requested for unknown title id={}", titleId);
            return PopulateResult.titleNotFound();
        }
        Title title = maybeTitle.get();

        // Guard: no existing draft for this title.
        if (draftTitleRepo.findByTitleId(titleId).isPresent()) {
            log.info("draft: populate skipped — draft already exists for title id={}", titleId);
            return PopulateResult.alreadyExists();
        }

        // Resolve the javdb slug (code-search, no actress anchor).
        JavdbSlugResolver.Resolution resolution = slugResolver.resolve(title.getCode(), null);
        if (!(resolution instanceof JavdbSlugResolver.Success success)) {
            log.info("draft: javdb no-match for {} ({})", title.getCode(), resolution);
            return PopulateResult.javdbNotFound();
        }
        String javdbSlug = success.slug();

        // Fetch and extract the title detail page.
        TitleExtract extract;
        try {
            String html = javdbClient.fetchTitlePage(javdbSlug);
            extract = extractor.extractTitle(html, title.getCode(), javdbSlug);
        } catch (Exception e) {
            log.warn("draft: javdb fetch/extract failed for slug={}", javdbSlug, e);
            return PopulateResult.javdbError();
        }

        String nowIso = java.time.Instant.now().toString();
        Long draftId  = null;
        try {
            draftId = writeDraft(titleId, title, javdbSlug, extract, success, nowIso);
            fetchAndStoreCover(draftId, extract.coverUrl());
            return PopulateResult.created(draftId);
        } catch (UnableToExecuteStatementException e) {
            // Race: another populate created the draft between our guard check and insert.
            rollbackDraft(draftId);
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                log.info("draft: populate race — draft appeared concurrently for title id={}", titleId);
                return PopulateResult.alreadyExists();
            }
            log.warn("draft: DB error writing draft for title id={}", titleId, e);
            return PopulateResult.javdbError();
        } catch (Exception e) {
            rollbackDraft(draftId);
            log.warn("draft: unexpected error populating draft for title id={}", titleId, e);
            return PopulateResult.javdbError();
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Writes the draft_titles row, enrichment row, and all cast rows.
     * Returns the new draft title id.
     */
    private long writeDraft(long titleId, Title title, String javdbSlug,
                            TitleExtract extract, JavdbSlugResolver.Success success,
                            String nowIso) {
        // 1. draft_titles
        String gradeString = (title.getGrade() != null) ? title.getGrade().display : null;
        DraftTitle draftTitle = DraftTitle.builder()
                .titleId(titleId)
                .code(title.getCode())
                .titleOriginal(extract.titleOriginal())
                .titleEnglish(title.getTitleEnglish())
                .releaseDate(extract.releaseDate())
                .notes(null)
                .grade(gradeString)
                .gradeSource("canonical")
                .upstreamChanged(false)
                .lastValidationError(null)
                .createdAt(nowIso)
                .updatedAt(nowIso)
                .build();
        long draftId = draftTitleRepo.insert(draftTitle);

        // 2. draft_title_javdb_enrichment
        DraftEnrichment enrichment = DraftEnrichment.builder()
                .draftTitleId(draftId)
                .javdbSlug(javdbSlug)
                .castJson(toJson(extract.cast()))
                .maker(extract.maker())
                .series(extract.series())
                .coverUrl(extract.coverUrl())
                .tagsJson(toJson(extract.tags()))
                .ratingAvg(extract.ratingAvg())
                .ratingCount(extract.ratingCount())
                .resolverSource(resolverSourceLabel(success))
                .updatedAt(nowIso)
                .build();
        draftEnrichRepo.upsert(draftId, enrichment);

        // 3. draft_actresses (upsert) + draft_title_actresses (cast slots)
        List<TitleExtract.CastEntry> castList =
                (extract.cast() != null) ? extract.cast() : List.of();
        writeCastSlots(draftId, castList, nowIso);

        return draftId;
    }

    /** Atomically writes draft_actresses (upsert) and draft_title_actresses for each cast entry. */
    private void writeCastSlots(long draftId, List<TitleExtract.CastEntry> castList, String nowIso) {
        List<DraftTitleActress> slots = new ArrayList<>(castList.size());
        for (TitleExtract.CastEntry entry : castList) {
            Long linked = autoLinkActress(entry);
            DraftActress draftActress = DraftActress.builder()
                    .javdbSlug(entry.slug())
                    .stageName(entry.name())
                    .englishFirstName(null)
                    .englishLastName(null)
                    .linkToExistingId(linked)
                    .createdAt(nowIso)
                    .updatedAt(nowIso)
                    .lastValidationError(null)
                    .build();
            draftActressRepo.upsertBySlug(draftActress);

            String resolution = (linked != null) ? RESOLUTION_PICK : RESOLUTION_UNRESOLVED;
            slots.add(new DraftTitleActress(draftId, entry.slug(), resolution));
        }
        draftCastRepo.replaceForDraft(draftId, slots);
    }

    /**
     * Runs canonicalization passes 1-3 against the cast entry's name and slug.
     *
     * <ul>
     *   <li>Pass 1: exact match on normalized {@code actresses.canonical_name}
     *   <li>Pass 2: exact match on normalized {@code actress_aliases.alias_name}
     *   <li>Pass 3: slug-anchored match via {@code javdb_actress_staging.javdb_slug}
     * </ul>
     *
     * @return the linked {@code actresses.id}, or {@code null} if unresolved
     */
    Long autoLinkActress(TitleExtract.CastEntry entry) {
        // Passes 1 + 2: resolve by normalized name (canonical or alias)
        String normalizedName = CastMatcher.normalize(entry.name());
        if (normalizedName != null) {
            Optional<Actress> byName = actressRepo.resolveByName(normalizedName);
            if (byName.isPresent() && !byName.get().isRejected()) {
                return byName.get().getId();
            }
        }

        // Pass 3: slug-anchored lookup in javdb_actress_staging
        Optional<Long> actressIdOpt = stagingRepo.findActressIdByJavdbSlug(entry.slug());
        if (actressIdOpt.isPresent()) {
            Optional<Actress> actress = actressRepo.findById(actressIdOpt.get());
            if (actress.isPresent() && !actress.get().isRejected()) {
                return actress.get().getId();
            }
        }

        return null;
    }

    /** Fetches the cover from javdb and writes it to the scratch store. Best-effort. */
    private void fetchAndStoreCover(long draftId, String coverUrl) {
        if (coverUrl == null || coverUrl.isBlank()) return;
        try {
            ImageFetcher.Fetched fetched = imageFetcher.fetch(coverUrl);
            coverStore.write(draftId, fetched.bytes());
            log.info("draft: cover stored for draft id={} ({} bytes)", draftId, fetched.bytes().length);
        } catch (Exception e) {
            // Cover failure is non-fatal; the user can trigger a re-fetch via the API.
            log.warn("draft: cover fetch failed for draft id={} url={}", draftId, coverUrl, e);
        }
    }

    /** Deletes a draft title row by id, cascading to all child rows. No-op if id is null. */
    private void rollbackDraft(Long draftId) {
        if (draftId == null) return;
        try {
            draftTitleRepo.delete(draftId);
            log.info("draft: rolled back partial draft id={}", draftId);
        } catch (Exception ex) {
            log.warn("draft: rollback failed for id={}", draftId, ex);
        }
    }

    private static String resolverSourceLabel(JavdbSlugResolver.Success success) {
        return success.source().name().toLowerCase().replace("_", "-");
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize to JSON", e);
        }
    }
}
