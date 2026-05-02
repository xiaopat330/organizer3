package com.organizer3.sync;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.javdb.enrichment.EnrichmentReviewQueueRepository;
import com.organizer3.model.Title;
import com.organizer3.repository.TitlePathHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detects potential recode and actress-rename candidates during sync by performing
 * soft-match identity checks against orphan titles and existing actress names.
 *
 * <p>Candidates are buffered in-memory during the discovery pass, then flushed to
 * {@code enrichment_review_queue} at the end of sync (before orphan pruning). The
 * catastrophic guard refuses to flood the queue if the match rate is anomalously high
 * (likely a volume-mount issue or sync bug, not real renames).
 *
 * <p>The two match types for titles:
 * <ul>
 *   <li><b>normalized_code</b> — uppercase + strip whitespace; catches case-only renames.</li>
 *   <li><b>base_seq</b> — same label + same seq_num; catches suffix differences (e.g.
 *       {@code ABC-001} vs {@code ABC-001_U}).</li>
 *   <li><b>path_history</b> — last-resort: if a folder appears at a path that a previously
 *       known title occupied, and that title still exists in the DB, surface as a
 *       {@code path_history_match} candidate for user review.</li>
 * </ul>
 * Actress matching strips all non-letter/non-digit chars and lowercases both sides.
 */
@Slf4j
public class SyncIdentityMatcher {

    private static final int FLOOR = 50;
    private static final int FRACTION_DIVISOR = 10;

    private final Jdbi jdbi;
    private final EnrichmentReviewQueueRepository reviewQueueRepo;
    private final TitlePathHistoryRepository pathHistoryRepo;

    private record OrphanTitle(long id, String code, String label, int seqNum) {}

    private record ActressInfo(long actressId, String canonicalName, long anchorTitleId) {}

    private record RecodeCandidate(
            long newTitleId, String newFolderCode,
            long orphanId, String orphanCode, String matchType) {}

    private record ActressRenameCandidate(
            long anchorTitleId, long candidateActressId,
            String candidateCanonicalName, String observedFolderName) {}

    private record PathHistoryCandidate(
            long newTitleId, String newFolderCode,
            long historicTitleId, String volumeId, String partitionId, String path) {}

    // Loaded at sync start
    private final Map<String, OrphanTitle> orphansByNormalizedCode = new HashMap<>();
    private final Map<String, OrphanTitle> orphansByBaseSeqKey     = new HashMap<>();
    private final Map<String, ActressInfo> actressesByNormalizedName = new HashMap<>();

    // Buffered during the discovery pass; flushed at the end of sync
    private final List<RecodeCandidate>        recodeCandidates        = new ArrayList<>();
    private final List<ActressRenameCandidate> actressRenameCandidates = new ArrayList<>();
    private final List<PathHistoryCandidate>   pathHistoryCandidates   = new ArrayList<>();

    public SyncIdentityMatcher(Jdbi jdbi, EnrichmentReviewQueueRepository reviewQueueRepo) {
        this(jdbi, reviewQueueRepo, null);
    }

    public SyncIdentityMatcher(Jdbi jdbi, EnrichmentReviewQueueRepository reviewQueueRepo,
                               TitlePathHistoryRepository pathHistoryRepo) {
        this.jdbi           = jdbi;
        this.reviewQueueRepo = reviewQueueRepo;
        this.pathHistoryRepo = pathHistoryRepo;
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    /**
     * Loads orphan titles and actress-with-anchor data from the DB into in-memory maps.
     * Call once per sync, after any location clears (full-sync: after deleteByVolume;
     * partition-sync: after deleteByVolumeAndPartition for all target partitions).
     */
    public void loadForSync() {
        orphansByNormalizedCode.clear();
        orphansByBaseSeqKey.clear();
        actressesByNormalizedName.clear();
        recodeCandidates.clear();
        actressRenameCandidates.clear();
        pathHistoryCandidates.clear();

        List<OrphanTitle> orphans = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.id, t.code, t.label, t.seq_num
                        FROM titles t
                        LEFT JOIN title_locations tl ON tl.title_id = t.id
                        WHERE tl.id IS NULL
                          AND t.label IS NOT NULL
                          AND t.seq_num IS NOT NULL
                        """)
                        .map((rs, ctx) -> new OrphanTitle(
                                rs.getLong("id"),
                                rs.getString("code"),
                                rs.getString("label"),
                                rs.getInt("seq_num")))
                        .list());

        for (OrphanTitle orphan : orphans) {
            orphansByNormalizedCode.putIfAbsent(normalizeCode(orphan.code()), orphan);
            orphansByBaseSeqKey.putIfAbsent(baseSeqKey(orphan.label(), orphan.seqNum()), orphan);
        }
        log.debug("SyncIdentityMatcher: loaded {} orphan titles", orphans.size());

        List<ActressInfo> actresses = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT a.id, a.canonical_name, MIN(t.id) AS anchor_title_id
                        FROM actresses a
                        JOIN titles t ON t.actress_id = a.id
                        GROUP BY a.id, a.canonical_name
                        """)
                        .map((rs, ctx) -> new ActressInfo(
                                rs.getLong("id"),
                                rs.getString("canonical_name"),
                                rs.getLong("anchor_title_id")))
                        .list());

        for (ActressInfo actress : actresses) {
            String normName = normalizeName(actress.canonicalName());
            if (!normName.isEmpty()) {
                actressesByNormalizedName.putIfAbsent(normName, actress);
            }
        }
        log.debug("SyncIdentityMatcher: loaded {} actresses with anchor titles", actresses.size());
    }

    // ── Note candidates ───────────────────────────────────────────────────────

    /**
     * Called for each newly created title (code not already in DB before this sync).
     * If a soft-match is found against an orphan title, buffers a recode_candidate.
     *
     * <p>After Phase-3 soft-match passes, if no recode candidate was found and a
     * {@link TitlePathHistoryRepository} is configured, consults path history as a
     * last-resort fallback: a folder reappearing at a historically-known path surfaces
     * as a {@code path_history_match} review-queue candidate.
     *
     * @param parsed      parsed code metadata from the folder name
     * @param newTitle    the newly created title DB row
     * @param volumeId    volume the folder lives on (for path-history lookup)
     * @param partitionId partition within the volume (for path-history lookup)
     * @param folderPath  volume-relative path string (for path-history lookup)
     */
    public void noteTitleCandidate(TitleCodeParser.ParsedCode parsed, Title newTitle,
                                   String volumeId, String partitionId, String folderPath) {
        if (parsed.label() == null || parsed.seqNum() == null) {
            tryPathHistoryFallback(newTitle, volumeId, partitionId, folderPath);
            return;
        }

        String normCode = normalizeCode(parsed.code());
        OrphanTitle byCode = orphansByNormalizedCode.get(normCode);
        if (byCode != null) {
            recodeCandidates.add(new RecodeCandidate(
                    newTitle.getId(), parsed.code(),
                    byCode.id(), byCode.code(), "normalized_code"));
            return;
        }

        String baseSeq = baseSeqKey(parsed.label(), parsed.seqNum());
        OrphanTitle byBaseSeq = orphansByBaseSeqKey.get(baseSeq);
        if (byBaseSeq != null) {
            recodeCandidates.add(new RecodeCandidate(
                    newTitle.getId(), parsed.code(),
                    byBaseSeq.id(), byBaseSeq.code(), "base_seq"));
            return;
        }

        // Phase-3 passes found nothing — try path history as last resort.
        tryPathHistoryFallback(newTitle, volumeId, partitionId, folderPath);
    }

    /**
     * Backward-compatible overload for callers that do not supply path coordinates.
     * Path-history fallback is skipped.
     */
    public void noteTitleCandidate(TitleCodeParser.ParsedCode parsed, Title newTitle) {
        if (parsed.label() == null || parsed.seqNum() == null) return;

        String normCode = normalizeCode(parsed.code());
        OrphanTitle byCode = orphansByNormalizedCode.get(normCode);
        if (byCode != null) {
            recodeCandidates.add(new RecodeCandidate(
                    newTitle.getId(), parsed.code(),
                    byCode.id(), byCode.code(), "normalized_code"));
            return;
        }

        String baseSeq = baseSeqKey(parsed.label(), parsed.seqNum());
        OrphanTitle byBaseSeq = orphansByBaseSeqKey.get(baseSeq);
        if (byBaseSeq != null) {
            recodeCandidates.add(new RecodeCandidate(
                    newTitle.getId(), parsed.code(),
                    byBaseSeq.id(), byBaseSeq.code(), "base_seq"));
        }
    }

    /**
     * Consults path history as a last-resort match for a newly created title.
     * Skipped if no {@link TitlePathHistoryRepository} was provided at construction.
     */
    private void tryPathHistoryFallback(Title newTitle, String volumeId,
                                        String partitionId, String folderPath) {
        if (pathHistoryRepo == null || folderPath == null) return;
        try {
            Optional<Long> historicId = pathHistoryRepo.findByPath(volumeId, partitionId, folderPath);
            historicId.ifPresent(hId -> pathHistoryCandidates.add(new PathHistoryCandidate(
                    newTitle.getId(), newTitle.getCode(), hId, volumeId, partitionId, folderPath)));
        } catch (Exception e) {
            log.warn("Path-history lookup failed for {}/{}/{} — skipping: {}",
                    volumeId, partitionId, folderPath, e.getMessage(), e);
        }
    }

    /**
     * Called when actress name resolution misses (no canonical or alias match found).
     * If a single normalized-name match is found among existing actresses, buffers an
     * actress_rename_candidate anchored to one of that actress's existing titles.
     */
    public void noteActressCandidate(String observedName) {
        String normName = normalizeName(observedName);
        if (normName.isEmpty()) return;

        ActressInfo match = actressesByNormalizedName.get(normName);
        if (match != null) {
            actressRenameCandidates.add(new ActressRenameCandidate(
                    match.anchorTitleId(), match.actressId(),
                    match.canonicalName(), observedName));
        }
    }

    // ── Flush ─────────────────────────────────────────────────────────────────

    /**
     * Writes buffered candidates to the review queue, applying the catastrophic guard.
     * Clears all candidate buffers after writing (or refusing to write).
     *
     * @param totalTitles total title count — used for the recode-candidate catastrophic guard
     */
    public void flushToQueue(int totalTitles) {
        flushRecodeCandidates(totalTitles);
        flushActressRenameCandidates();
        flushPathHistoryCandidates(totalTitles);
        recodeCandidates.clear();
        actressRenameCandidates.clear();
        pathHistoryCandidates.clear();
    }

    private void flushRecodeCandidates(int totalTitles) {
        if (recodeCandidates.isEmpty()) return;
        int threshold = Math.max(FLOOR, totalTitles / FRACTION_DIVISOR);
        if (recodeCandidates.size() > threshold) {
            log.error("Recode-candidate flagging refused: {} candidates would be written (threshold {}). "
                    + "This likely indicates a volume-mount issue or sync bug — investigate before re-running sync.",
                    recodeCandidates.size(), threshold);
            return;
        }
        for (RecodeCandidate c : recodeCandidates) {
            ObjectNode detail = JsonNodeFactory.instance.objectNode();
            detail.put("orphan_code",      c.orphanCode());
            detail.put("orphan_id",        c.orphanId());
            detail.put("new_folder_code",  c.newFolderCode());
            detail.put("match_type",       c.matchType());
            reviewQueueRepo.enqueueWithDetail(
                    c.newTitleId(), null, "recode_candidate", "sync_soft_match", detail.toString());
        }
        log.info("SyncIdentityMatcher: flushed {} recode_candidate(s) to review queue",
                recodeCandidates.size());
    }

    private void flushActressRenameCandidates() {
        if (actressRenameCandidates.isEmpty()) return;
        int threshold = Math.max(FLOOR, actressesByNormalizedName.size() / FRACTION_DIVISOR);
        if (actressRenameCandidates.size() > threshold) {
            log.error("Actress-rename-candidate flagging refused: {} candidates would be written (threshold {}). "
                    + "This likely indicates a volume-mount issue or sync bug — investigate before re-running sync.",
                    actressRenameCandidates.size(), threshold);
            return;
        }
        for (ActressRenameCandidate c : actressRenameCandidates) {
            ObjectNode detail = JsonNodeFactory.instance.objectNode();
            detail.put("candidate_canonical_name", c.candidateCanonicalName());
            detail.put("candidate_id",             c.candidateActressId());
            detail.put("observed_folder_name",     c.observedFolderName());
            reviewQueueRepo.enqueueWithDetail(
                    c.anchorTitleId(), null, "actress_rename_candidate", "sync_soft_match", detail.toString());
        }
        log.info("SyncIdentityMatcher: flushed {} actress_rename_candidate(s) to review queue",
                actressRenameCandidates.size());
    }

    private void flushPathHistoryCandidates(int totalTitles) {
        if (pathHistoryCandidates.isEmpty()) return;
        int threshold = Math.max(FLOOR, totalTitles / FRACTION_DIVISOR);
        if (pathHistoryCandidates.size() > threshold) {
            log.error("Path-history-candidate flagging refused: {} candidates would be written (threshold {}). "
                    + "This likely indicates a volume-mount issue or sync bug — investigate before re-running sync.",
                    pathHistoryCandidates.size(), threshold);
            return;
        }
        for (PathHistoryCandidate c : pathHistoryCandidates) {
            ObjectNode detail = JsonNodeFactory.instance.objectNode();
            detail.put("historic_title_id", c.historicTitleId());
            detail.put("new_folder_code",   c.newFolderCode());
            detail.put("volume_id",         c.volumeId());
            detail.put("partition_id",      c.partitionId());
            detail.put("path",              c.path());
            reviewQueueRepo.enqueueWithDetail(
                    c.newTitleId(), null, "path_history_match", "sync_soft_match", detail.toString());
        }
        log.info("SyncIdentityMatcher: flushed {} path_history_match candidate(s) to review queue",
                pathHistoryCandidates.size());
    }

    // ── Normalization helpers ─────────────────────────────────────────────────

    static String normalizeCode(String code) {
        if (code == null) return "";
        return code.toUpperCase().replaceAll("\\s+", "");
    }

    static String baseSeqKey(String label, int seqNum) {
        return label.toUpperCase() + "-" + seqNum;
    }

    static String normalizeName(String name) {
        if (name == null) return "";
        // Strip all non-letter/non-digit chars and lowercase — catches space/underscore/hyphen differences
        return name.toLowerCase().replaceAll("[^\\p{L}\\d]", "");
    }
}
