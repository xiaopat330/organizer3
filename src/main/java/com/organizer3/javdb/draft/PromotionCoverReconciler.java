package com.organizer3.javdb.draft;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Title;
import com.organizer3.web.CoverWriteService;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Self-healing reconciler for promotion NAS cover writes.
 *
 * <p>At promote, the NAS folder cover is written best-effort off the request thread. That write
 * can be <b>dropped</b> (post-commit queue overflow), interrupted by a <b>crash</b> between commit
 * and write, or <b>fail</b> outright (SMB stall throwing mid-transfer, leaving a zero-byte stub).
 * To make it durable, {@link DraftPromotionService#promote} stamps
 * {@code title_locations.cover_pending_since} pessimistically before the async dispatch and clears
 * it only when the write completes without throwing — so all three failure modes leave the row
 * pending.
 *
 * <p>This reconciler periodically (via {@link PromotionRenameReconcileScheduler}) finds those
 * pending locations and re-pushes the intact local-cache cover, clearing the flag on success. It
 * mirrors {@link PromotionFolderRenameReconciler}: background-safe (no {@code SessionContext}),
 * per-row failure never aborts the batch, quiet on no-op passes.
 *
 * <p>Non-clobbering falls out for free: only {@code cover_pending_since IS NOT NULL} rows are
 * touched, so confirmed rows (user overrides, existing valid covers) are never read or written.
 * The pushed bytes are the local cache = the promoted cover (or the user's override, since
 * {@code CoverWriteService.save} writes the cache too), so a re-push never clobbers user intent.
 */
@Slf4j
public class PromotionCoverReconciler {

    /** Tally of a reconcile pass. */
    public record ReconcileResult(int candidates, int pushed, int noLocalCover, int stillPending, int failed) {}

    // Scope: live serviceable locations still pending a cover-write confirmation.
    private static final String FIND_PENDING_SQL = """
            SELECT tl.id AS id, tl.volume_id AS volume_id, tl.path AS path,
                   t.base_code AS base_code, t.label AS label
            FROM title_locations tl
            JOIN titles t ON t.id = tl.title_id
            WHERE tl.cover_pending_since IS NOT NULL
              AND tl.stale_since IS NULL
              AND tl.volume_id IN (<vols>)
            ORDER BY tl.cover_pending_since
            LIMIT :limit
            """;

    private final Jdbi jdbi;
    private final CoverWriteService coverWriteService;
    private final CoverPath coverPath;
    private final Set<String> serviceableVolumeIds;

    public PromotionCoverReconciler(Jdbi jdbi, CoverWriteService coverWriteService,
                                    CoverPath coverPath, Set<String> serviceableVolumeIds) {
        this.jdbi = jdbi;
        this.coverWriteService = coverWriteService;
        this.coverPath = coverPath;
        this.serviceableVolumeIds = serviceableVolumeIds;
    }

    /** Back-compat single-volume ctor. */
    public PromotionCoverReconciler(Jdbi jdbi, CoverWriteService coverWriteService,
                                    CoverPath coverPath, String unsortedVolumeId) {
        this(jdbi, coverWriteService, coverPath, Set.of(unsortedVolumeId));
    }

    /** A pending location awaiting a NAS cover push. */
    private record PendingLocation(long id, String volumeId, String path, String baseCode, String label) {}

    /**
     * Finds pending locations and, for each with an intact local-cache cover, re-pushes it to the
     * NAS and clears the flag on success. One row's failure never aborts the batch.
     *
     * @param limit maximum number of pending locations to inspect.
     * @return a tally of candidates considered, covers pushed, source-less rows, still-pending
     *         (transient failure) rows, and per-row errors.
     */
    public ReconcileResult reconcile(int limit) {
        List<PendingLocation> pending = jdbi.withHandle(h -> {
            List<PendingLocation> out = new ArrayList<>();
            h.createQuery(FIND_PENDING_SQL)
                    .bindList("vols", List.copyOf(serviceableVolumeIds))
                    .bind("limit", limit)
                    .map((rs, ctx) -> new PendingLocation(
                            rs.getLong("id"), rs.getString("volume_id"), rs.getString("path"),
                            rs.getString("base_code"), rs.getString("label")))
                    .forEach(out::add);
            return out;
        });

        int pushed = 0, noLocalCover = 0, stillPending = 0, failed = 0;
        for (PendingLocation p : pending) {
            try {
                Title minimal = Title.builder().label(p.label()).baseCode(p.baseCode()).build();
                Optional<Path> local = coverPath.find(minimal);
                if (local.isEmpty() || Files.size(local.get()) == 0) {
                    // Source-less (un-enriched / failed-fetch): leave pending, no churn.
                    noLocalCover++;
                    continue;
                }
                byte[] bytes = Files.readAllBytes(local.get());
                boolean ok = coverWriteService.saveToNasBestEffort(p.path(), p.baseCode(), bytes, p.volumeId());
                if (ok) {
                    jdbi.useHandle(h -> h.createUpdate(
                            "UPDATE title_locations SET cover_pending_since = NULL WHERE id = :id")
                            .bind("id", p.id())
                            .execute());
                    pushed++;
                    log.info("reconcile: pushed cover for {} to {} on {}", p.baseCode(), p.path(), p.volumeId());
                } else {
                    // Transient SMB failure — next pass retries.
                    stillPending++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("reconcile: cover push failed for {} (locId={}): {}",
                        p.baseCode(), p.id(), e.getMessage());
            }
        }

        // Stay quiet on no-op passes (a 10-min sweeper would otherwise spam the log).
        if (pushed > 0 || failed > 0) {
            log.info("reconcile: cover pass complete — candidates={} pushed={} noLocalCover={} stillPending={} failed={}",
                    pending.size(), pushed, noLocalCover, stillPending, failed);
        }
        return new ReconcileResult(pending.size(), pushed, noLocalCover, stillPending, failed);
    }
}
