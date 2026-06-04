package com.organizer3.javdb.draft;

import com.organizer3.web.TitleFolderRenamer;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-healing reconciler for promotion folder renames.
 *
 * <p>After a draft is promoted, a best-effort post-commit step renames the title's
 * {@code /fresh/(CODE)} staging folder to the canonical {@code Actress (CODE)} pattern via
 * {@link TitleFolderRenamer#renamePreservingDescriptor}. If that SMB op hard-fails (e.g. a
 * broken pipe that survives the renamer's internal retry), the rename is skipped and nothing
 * re-attempts it — leaving the promoted title stranded with an un-normalized folder name.
 *
 * <p>This reconciler periodically (via {@link PromotionRenameReconcileScheduler}) finds those
 * stranded promoted titles and re-runs the rename, making the normalization self-healing.
 *
 * <p>Scope is deliberately narrow: only titles with {@code grade_source='enrichment'} — the
 * precise marker that a title was promoted from a draft. Titles with {@code grade_source IS NULL}
 * are duplicate fresh copies of existing library titles and are NOT reconciled here.
 */
@Slf4j
public class PromotionFolderRenameReconciler {

    /** A promoted title with a live staging location and its predicted canonical folder name. */
    public record Candidate(long titleId, String code, String actressName, String currentPath,
                            String targetName, boolean needsRename) {}

    /** Tally of a reconcile pass. */
    public record ReconcileResult(int candidates, int renamed, int alreadyOk, int failed) {}

    private static final String FIND_SQL = """
            SELECT t.id, t.code, a.canonical_name AS actress, tl.path AS path
            FROM titles t
            JOIN title_locations tl ON tl.title_id = t.id AND tl.volume_id = :vol AND tl.stale_since IS NULL
            JOIN actresses a ON a.id = t.actress_id
            WHERE t.grade_source = 'enrichment'
              AND a.canonical_name IS NOT NULL AND a.canonical_name <> ''
            GROUP BY t.id
            ORDER BY t.id
            LIMIT :limit
            """;

    private final Jdbi jdbi;
    private final TitleFolderRenamer renamer;
    private final String unsortedVolumeId;

    public PromotionFolderRenameReconciler(Jdbi jdbi, TitleFolderRenamer renamer, String unsortedVolumeId) {
        this.jdbi = jdbi;
        this.renamer = renamer;
        this.unsortedVolumeId = unsortedVolumeId;
    }

    /**
     * Finds promoted titles with a live location on the unsorted volume, computing for each its
     * predicted canonical target folder name and whether a rename is needed. Returns ALL candidates
     * (including those already correctly named); callers filter on {@link Candidate#needsRename()}.
     */
    public List<Candidate> findCandidates(int limit) {
        return jdbi.withHandle(h -> {
            List<Candidate> out = new ArrayList<>();
            h.createQuery(FIND_SQL)
                    .bind("vol", unsortedVolumeId)
                    .bind("limit", limit)
                    .map((rs, ctx) -> {
                        long titleId = rs.getLong("id");
                        String code = rs.getString("code");
                        String actress = rs.getString("actress");
                        String path = rs.getString("path");
                        String currentName = TitleFolderRenamer.basename(path);
                        String descriptor = TitleFolderRenamer.extractDescriptor(currentName, code);
                        String targetName = TitleFolderRenamer.targetFolderName(actress, descriptor, code);
                        boolean needsRename = !targetName.equals(currentName);
                        return new Candidate(titleId, code, actress, path, targetName, needsRename);
                    })
                    .forEach(out::add);
            return out;
        });
    }

    /**
     * Re-runs the canonical rename for each stranded candidate. One failure never aborts the batch.
     *
     * @return a tally of candidates considered, renames performed, already-correct titles, and failures.
     */
    public ReconcileResult reconcile(int limit) {
        List<Candidate> candidates = findCandidates(limit);
        int renamed = 0, alreadyOk = 0, failed = 0;

        for (Candidate c : candidates) {
            if (!c.needsRename()) {
                alreadyOk++;
                continue;
            }
            try {
                TitleFolderRenamer.RenameOutcome outcome =
                        renamer.renamePreservingDescriptor(c.titleId(), c.actressName(), c.code());
                if (outcome.renamed()) {
                    renamed++;
                    log.info("reconcile: renamed folder for {} -> {}", c.code(), outcome.newPath());
                } else {
                    alreadyOk++;
                }
            } catch (IllegalStateException e) {
                // Target collision — a real conflict; surface but keep going.
                failed++;
                log.warn("reconcile: rename failed for {} ({}): {}", c.code(), c.titleId(), e.getMessage());
            } catch (Exception e) {
                failed++;
                log.warn("reconcile: rename failed for {} ({}): {}", c.code(), c.titleId(), e.getMessage());
            }
        }

        // Stay quiet on no-op passes (a 10-min sweeper would otherwise spam the log).
        if (renamed > 0 || failed > 0) {
            log.info("reconcile: pass complete — candidates={} renamed={} alreadyOk={} failed={}",
                    candidates.size(), renamed, alreadyOk, failed);
        }
        return new ReconcileResult(candidates.size(), renamed, alreadyOk, failed);
    }
}
