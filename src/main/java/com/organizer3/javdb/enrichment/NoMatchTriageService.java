package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.organizer3.javdb.enrichment.NoMatchTriageRepository.FilmographyCandidate;
import com.organizer3.javdb.enrichment.NoMatchTriageRepository.FolderInfo;
import com.organizer3.javdb.enrichment.NoMatchTriageRepository.NoMatchRow;
import com.organizer3.mcp.tools.ForceEnrichTitleTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Business logic for the no-match enrichment triage UI.
 *
 * <p>Assembles {@link NoMatchTriageRow} DTOs (with candidate-actress hints) and
 * implements the four user-driven repair actions: try other actress, manual slug,
 * mark resolved, and open folder.
 */
@Slf4j
@RequiredArgsConstructor
public class NoMatchTriageService {

    /** Filmography cache age (in days) above which we flag the cache as stale. */
    private static final int STALE_TTL_DAYS = 30;

    private final NoMatchTriageRepository repo;
    private final ForceEnrichTitleTool forceEnrichTool;
    private final Clock clock;

    // ── list ──────────────────────────────────────────────────────────────────

    /**
     * Returns all no-match titles assembled into {@link NoMatchTriageRow} DTOs.
     * Candidate actresses (whose cached filmography contains the code) are pre-computed
     * and deduplicated per code so the caller makes at most one lookup per unique code.
     *
     * @param actressId  if non-null, filter to rows linked to this actress
     * @param orphanOnly if true, return only rows with no actress link
     */
    public List<NoMatchTriageRow> list(Long actressId, boolean orphanOnly) {
        List<NoMatchRow> rawRows = repo.listNoMatchRows();

        // Group by title_id to collapse multi-actress rows into a single triage entry.
        Map<Long, NoMatchTriageRow.Builder> byTitle = new LinkedHashMap<>();
        for (NoMatchRow row : rawRows) {
            byTitle.computeIfAbsent(row.titleId(), id -> new NoMatchTriageRow.Builder(row))
                   .addActress(row.actressId(), row.actressStageName());
        }

        // Build candidate hints, deduplicated by base_code.
        Map<String, List<CandidateActress>> byCode = new HashMap<>();

        List<NoMatchTriageRow> results = new ArrayList<>();
        for (NoMatchTriageRow.Builder b : byTitle.values()) {
            String code = b.code();
            String baseCode = b.baseCode();
            String lookupCode = baseCode != null ? baseCode : code;

            List<CandidateActress> candidates = byCode.computeIfAbsent(lookupCode, c -> {
                List<FilmographyCandidate> fc = repo.findActressesByFilmographyCode(c);
                return fc.stream().map(f -> toCandidateActress(f)).toList();
            });

            NoMatchTriageRow row = b.build(candidates);

            // Apply filters.
            if (orphanOnly && !row.orphan()) continue;
            if (actressId != null && !row.linkedActressIds().contains(actressId)) continue;

            results.add(row);
        }
        return results;
    }

    // ── actions ───────────────────────────────────────────────────────────────

    /**
     * "Try other actress" action: validates that {@code actressId} actually has the code
     * in the L2 filmography cache, then calls {@link NoMatchTriageRepository#clearNoMatchAndReQueue}
     * with the actress override.
     *
     * @throws IllegalArgumentException if the actress does not have the code in cache
     */
    public void tryOtherActress(long titleId, long actressId) {
        // Validate by fetching the no-match row to get the code.
        List<NoMatchRow> rows = repo.listNoMatchRows().stream()
                .filter(r -> r.titleId() == titleId)
                .toList();
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No no-match row found for title " + titleId);
        }
        String code = rows.get(0).code();
        String baseCode = rows.get(0).baseCode();
        String lookupCode = baseCode != null ? baseCode : code;

        List<FilmographyCandidate> candidates = repo.findActressesByFilmographyCode(lookupCode);
        boolean actressHasCode = candidates.stream().anyMatch(c -> c.actressId() == actressId);
        if (!actressHasCode) {
            throw new IllegalArgumentException(
                    "Actress " + actressId + " does not have code " + lookupCode + " in filmography cache");
        }

        log.info("no-match triage: reassigning title {} ({}) to actress {}", titleId, code, actressId);
        repo.clearNoMatchAndReQueue(titleId, actressId);
    }

    /**
     * "Manual slug entry" action: force-enriches the title with the user-supplied javdb slug,
     * bypassing the resolver. Delegates to {@link ForceEnrichTitleTool} with {@code dry_run=false}.
     *
     * @throws IllegalArgumentException if the slug is not found on javdb or title not in DB
     */
    public void manualSlugEntry(long titleId, String javdbSlug) {
        log.info("no-match triage: manual slug entry for title {} with slug {}", titleId, javdbSlug);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode args = mapper.createObjectNode();
        args.put("title_id", titleId);
        args.put("slug", javdbSlug);
        args.put("dry_run", false);
        ForceEnrichTitleTool.Result result = (ForceEnrichTitleTool.Result) forceEnrichTool.call(args);
        if (!result.ok()) {
            throw new IllegalArgumentException(
                    "force_enrich failed for title " + titleId + " slug " + javdbSlug
                    + ": " + result.error());
        }
    }

    /**
     * "Mark resolved" action: sets the queue row to {@code cancelled} with
     * {@code last_error='user_marked_no_javdb_data'}.
     *
     * @throws IllegalArgumentException if no no-match row was found for this title
     */
    public void markResolved(long titleId) {
        boolean updated = repo.markResolved(titleId);
        if (!updated) {
            throw new IllegalArgumentException("No no-match row found for title " + titleId);
        }
        log.info("no-match triage: marked title {} as resolved (no javdb data)", titleId);
    }

    /**
     * "Open folder" action: returns the NAS path for the title's primary location.
     *
     * @return the FolderInfo if a location is known, or empty
     */
    public Optional<FolderInfo> openFolder(long titleId) {
        return repo.findFolderInfo(titleId);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private CandidateActress toCandidateActress(FilmographyCandidate fc) {
        boolean stale = isStale(fc.fetchedAt(), fc.lastReleaseDate());
        return new CandidateActress(
                fc.actressId(),
                fc.stageName(),
                fc.javdbSlug(),
                fc.titleSlug(),
                fc.fetchedAt(),
                stale);
    }

    /**
     * Returns true if the filmography cache is older than {@link #STALE_TTL_DAYS}, unless
     * the actress is "settled" (last release > 2 years ago).
     */
    private boolean isStale(String fetchedAt, String lastReleaseDate) {
        if (fetchedAt == null) return true;
        // Settled catalog: if last release was > 2 years ago, skip TTL check.
        if (lastReleaseDate != null) {
            try {
                LocalDate lastRelease = LocalDate.parse(lastReleaseDate.substring(0, 10));
                LocalDate twoYearsAgo = LocalDate.now(clock).minusYears(2);
                if (lastRelease.isBefore(twoYearsAgo)) return false;
            } catch (Exception ignored) {}
        }
        try {
            Instant fetched = Instant.parse(fetchedAt);
            Instant threshold = Instant.now(clock).minus(STALE_TTL_DAYS, ChronoUnit.DAYS);
            return fetched.isBefore(threshold);
        } catch (Exception e) {
            return true;
        }
    }

    // ── output DTOs ───────────────────────────────────────────────────────────

    /**
     * A candidate actress suggestion for a no-match title, with staleness flag.
     */
    public record CandidateActress(
            long actressId,
            String stageName,
            String javdbSlug,
            String titleSlug,
            String fetchedAt,
            boolean stale
    ) {}

    /**
     * One assembled triage row for the UI, keyed by title.
     */
    public record NoMatchTriageRow(
            long titleId,
            String code,
            String baseCode,
            /** All actress IDs linked to this title (empty list = orphan). */
            List<Long> linkedActressIds,
            /** All actress names linked to this title (parallel with linkedActressIds). */
            List<String> linkedActressNames,
            String folderPath,
            String volumeId,
            int attempts,
            String updatedAt,
            /** Whether this is an orphan row (no actress link at all). */
            boolean orphan,
            List<CandidateActress> candidates
    ) {
        static class Builder {
            private final NoMatchRow primary;
            private final List<Long>   actressIds   = new ArrayList<>();
            private final List<String> actressNames = new ArrayList<>();

            Builder(NoMatchRow row) {
                this.primary = row;
                addActress(row.actressId(), row.actressStageName());
            }

            String code()     { return primary.code(); }
            String baseCode() { return primary.baseCode(); }

            void addActress(Long actressId, String stageName) {
                if (actressId != null && stageName != null && !actressIds.contains(actressId)) {
                    actressIds.add(actressId);
                    actressNames.add(stageName);
                }
            }

            NoMatchTriageRow build(List<CandidateActress> candidates) {
                return new NoMatchTriageRow(
                        primary.titleId(),
                        primary.code(),
                        primary.baseCode(),
                        List.copyOf(actressIds),
                        List.copyOf(actressNames),
                        primary.folderPath(),
                        primary.volumeId(),
                        primary.attempts(),
                        primary.updatedAt(),
                        actressIds.isEmpty(),
                        candidates);
            }
        }
    }
}
