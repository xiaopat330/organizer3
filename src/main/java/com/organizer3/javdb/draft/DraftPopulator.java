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
import com.organizer3.translation.ActressFuzzyMatcher;
import com.organizer3.translation.TranslationNormalization;
import com.organizer3.translation.TranslationService;
import com.organizer3.translation.repository.StageNameSuggestionRepository;
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

    // FIX 2: Default timeout/poll constants for the blocking stage-name resolution.
    // ~2500ms gives the LLM worker enough time to process a freshly-enqueued row;
    // 250ms poll is fine-grained enough without burning CPU.
    static final long STAGE_NAME_BLOCKING_TIMEOUT_MS      = 2500L;
    static final long STAGE_NAME_BLOCKING_POLL_INTERVAL_MS = 250L;

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
    private final TranslationService            translationService;
    private final ActressFuzzyMatcher           fuzzyMatcher;
    // FIX 3a: needed to persist corrected order when a REVERSAL-rule match occurs.
    // May be null if not wired (backward-compat); FIX 3a is skipped when null.
    private final StageNameSuggestionRepository stageNameSuggestionRepo;

    /** Backward-compatible constructor (no stageNameSuggestionRepo — FIX 3a skipped). */
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
            ObjectMapper             json,
            TranslationService       translationService,
            ActressFuzzyMatcher      fuzzyMatcher) {
        this(titleRepo, actressRepo, slugResolver, javdbClient, extractor, stagingRepo,
                draftTitleRepo, draftActressRepo, draftCastRepo, draftEnrichRepo,
                coverStore, imageFetcher, json, translationService, fuzzyMatcher, null);
    }

    /** Full constructor including FIX 3a stageNameSuggestionRepo. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public DraftPopulator(
            TitleRepository               titleRepo,
            ActressRepository             actressRepo,
            JavdbSlugResolver             slugResolver,
            JavdbClient                   javdbClient,
            JavdbExtractor                extractor,
            JavdbStagingRepository        stagingRepo,
            DraftTitleRepository          draftTitleRepo,
            DraftActressRepository        draftActressRepo,
            DraftTitleActressesRepository draftCastRepo,
            DraftTitleEnrichmentRepository draftEnrichRepo,
            DraftCoverScratchStore        coverStore,
            ImageFetcher                  imageFetcher,
            ObjectMapper                  json,
            TranslationService            translationService,
            ActressFuzzyMatcher           fuzzyMatcher,
            StageNameSuggestionRepository stageNameSuggestionRepo) {
        this.titleRepo               = titleRepo;
        this.actressRepo             = actressRepo;
        this.slugResolver            = slugResolver;
        this.javdbClient             = javdbClient;
        this.extractor               = extractor;
        this.stagingRepo             = stagingRepo;
        this.draftTitleRepo          = draftTitleRepo;
        this.draftActressRepo        = draftActressRepo;
        this.draftCastRepo           = draftCastRepo;
        this.draftEnrichRepo         = draftEnrichRepo;
        this.coverStore              = coverStore;
        this.imageFetcher            = imageFetcher;
        this.json                    = json;
        this.translationService      = translationService;
        this.fuzzyMatcher            = fuzzyMatcher;
        this.stageNameSuggestionRepo = stageNameSuggestionRepo;
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
                .durationMinutes(extract.durationMinutes())
                .publisher(extract.publisher())
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

    /**
     * Carries the outcome of {@link #autoLinkActress}: either a link to an existing Actress,
     * pre-filled English name parts from a fuzzy-matched romaji guess, or nothing.
     * {@code via} records which pass fired (canonical/alias/stage_name/slug/fuzzy/prefill),
     * or {@code null} if no match was found.
     */
    public record AutoLinkResult(Long actressId, String englishFirst, String englishLast, String via) {
        public static final AutoLinkResult EMPTY = new AutoLinkResult(null, null, null, null);
    }

    /** Atomically writes draft_actresses (upsert) and draft_title_actresses for each cast entry.
     *  Only female entries (gender == "F") become slots; males/unknowns are skipped entirely.
     *  The stored cast_json in draft_title_javdb_enrichment retains the full cast. */
    private void writeCastSlots(long draftId, List<TitleExtract.CastEntry> castList, String nowIso) {
        List<DraftTitleActress> slots = new ArrayList<>(castList.size());
        for (TitleExtract.CastEntry entry : castList) {
            if (!"F".equals(entry.gender())) continue;  // skip male / unknown / missing gender
            AutoLinkResult linkResult = autoLinkActress(entry);
            String normalizedStageName = TranslationNormalization.normalize(entry.name());
            DraftActress draftActress = DraftActress.builder()
                    .javdbSlug(entry.slug())
                    .stageName(normalizedStageName.isEmpty() ? entry.name() : normalizedStageName)
                    .englishFirstName(linkResult.englishFirst())
                    .englishLastName(linkResult.englishLast())
                    .linkToExistingId(linkResult.actressId())
                    .createdAt(nowIso)
                    .updatedAt(nowIso)
                    .lastValidationError(null)
                    .build();
            draftActressRepo.upsertBySlug(draftActress);

            String resolution = (linkResult.actressId() != null) ? RESOLUTION_PICK : RESOLUTION_UNRESOLVED;
            slots.add(new DraftTitleActress(draftId, entry.slug(), resolution, linkResult.via()));
        }
        draftCastRepo.replaceForDraft(draftId, slots);
    }

    /**
     * Runs canonicalization passes 1-5 against the cast entry's name and slug.
     *
     * <ul>
     *   <li>Pass 1: exact match on normalized {@code actresses.canonical_name}
     *   <li>Pass 2: exact match on normalized {@code actress_aliases.alias_name}
     *   <li>Pass 2.5: exact match on kanji {@code actresses.stage_name} (cast names from javdb are kanji)
     *   <li>Pass 3: slug-anchored match via {@code javdb_actress_staging.javdb_slug}
     *   <li>Pass 4: curated stage-name lookup returns romaji + fuzzy Actress match
     *   <li>Pass 5a: curated romaji present but no fuzzy match → pre-fill english first/last
     *   <li>Pass 5b: curated miss → enqueued for LLM; returns EMPTY
     * </ul>
     */
    AutoLinkResult autoLinkActress(TitleExtract.CastEntry entry) {
        // Passes 1 + 2: resolve by normalized name (canonical or alias)
        String normalizedName = CastMatcher.normalize(entry.name());
        if (normalizedName != null) {
            Optional<Actress> byName = actressRepo.resolveByName(normalizedName);
            if (byName.isPresent() && !byName.get().isRejected()) {
                // Distinguish Pass 1 (canonical match) from Pass 2 (alias match).
                String via = byName.get().getCanonicalName().equalsIgnoreCase(normalizedName)
                        ? "canonical" : "alias";
                return new AutoLinkResult(byName.get().getId(), null, null, via);
            }
        }

        // Pass 2.5: exact match on kanji stage_name (cast names from javdb are kanji)
        String rawName = entry.name() == null ? null : entry.name().trim();
        if (rawName != null && !rawName.isEmpty()) {
            Optional<Actress> byStage = actressRepo.findByStageName(rawName);
            if (byStage.isPresent()) {
                return new AutoLinkResult(byStage.get().getId(), null, null, "stage_name");
            }
        }

        // Pass 3: slug-anchored lookup in javdb_actress_staging
        Optional<Long> actressIdOpt = stagingRepo.findActressIdByJavdbSlug(entry.slug());
        if (actressIdOpt.isPresent()) {
            Optional<Actress> actress = actressRepo.findById(actressIdOpt.get());
            if (actress.isPresent() && !actress.get().isRejected()) {
                return new AutoLinkResult(actress.get().getId(), null, null, "slug");
            }
        }

        // Pass 4: curated stage-name lookup + fuzzy match.
        // FIX 2: Use the bounded blocking variant so the LLM can produce a result in the
        // ~2500ms window before we give up. On timeout the call returns empty and we fall
        // through to Pass 5b exactly as before (no regression).
        Optional<String> romaji = translationService.resolveStageNameBlocking(
                entry.name(),
                STAGE_NAME_BLOCKING_TIMEOUT_MS,
                STAGE_NAME_BLOCKING_POLL_INTERVAL_MS);
        // Guard: a non-romaji (kanji) "romaji" is unusable — never fuzzy-match it and never
        // split it into english name fields. Treat as a miss → falls through to Pass 5b EMPTY.
        if (romaji.isPresent() && !TranslationNormalization.containsCjk(romaji.get())) {
            Optional<ActressFuzzyMatcher.MatchResult> fuzzy = fuzzyMatcher.match(romaji.get());
            if (fuzzy.isPresent()) {
                Optional<Actress> matched = actressRepo.findById(fuzzy.get().actressId());
                if (matched.isPresent() && !matched.get().isRejected()) {
                    // FIX 3a: If the match was via REVERSAL, the suggestion store has the romaji
                    // in surname-first order. Persist the canonical (given-first) order from the
                    // matched actress's canonical_name so the review UI pre-fills correctly.
                    if (fuzzy.get().rule() == ActressFuzzyMatcher.Rule.REVERSAL
                            && stageNameSuggestionRepo != null
                            && entry.name() != null && !entry.name().isBlank()) {
                        String correctedRomaji = matched.get().getCanonicalName();
                        stageNameSuggestionRepo.recordFinalRomaji(
                                TranslationNormalization.normalize(entry.name()), correctedRomaji);
                        log.debug("autoLink: REVERSAL match — corrected romaji='{}' written for kanji='{}'",
                                correctedRomaji, entry.name());
                    }
                    return new AutoLinkResult(matched.get().getId(), null, null, "fuzzy");
                }
            }
            // Pass 5a: romaji in hand but no unrejected actress match → pre-fill name fields
            String[] parts = ActressFuzzyMatcher.splitRomaji(romaji.get());
            return new AutoLinkResult(null, parts[0], parts[1], "prefill");
        }

        // Pass 5b: resolveStageNameBlocking returned empty → timed out or LLM unavailable
        return AutoLinkResult.EMPTY;
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

    /**
     * Maps a slug-resolution source to its canonical DB label.
     *
     * <p>Must stay in lockstep with {@link com.organizer3.javdb.enrichment.EnrichmentRunner}'s
     * own {@code resolverSourceLabel} — both write into the same {@code resolver_source}
     * column family (draft and canonical), and every other layer (repositories, review-queue
     * writers) documents only the underscore form. An explicit switch — not a
     * {@code String.replace} transform — keeps the two mappings from drifting apart again.
     * Package-visible for {@link DraftPopulatorTest}'s cross-agreement check.
     */
    static String resolverSourceLabel(JavdbSlugResolver.Success success) {
        JavdbSlugResolver.Source source = success.source();
        if (source == null) return "unknown";
        return switch (source) {
            case ACTRESS_FILMOGRAPHY  -> "actress_filmography";
            case CODE_SEARCH_FALLBACK -> "code_search_fallback";
        };
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
