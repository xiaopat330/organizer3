package com.organizer3.javdb.draft;

import com.organizer3.web.StagingCastHelper;
import com.organizer3.web.TitleFolderRenamer;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Self-healing reconciler for promotion folder renames.
 *
 * <p>After a draft is promoted, a best-effort post-commit step renames the title's
 * {@code /fresh/(CODE)} staging folder to the canonical multi-name pattern
 * (e.g. {@code "Waka Misono, Miyu Aizawa (ADN-778)"}) via
 * {@link TitleFolderRenamer#renamePreservingDescriptor}. If that SMB op hard-fails, the rename
 * is skipped and nothing re-attempts it — leaving the promoted title stranded with an
 * un-normalized folder name.
 *
 * <p>This reconciler periodically (via {@link PromotionRenameReconcileScheduler}) finds those
 * stranded promoted titles and re-runs the rename, making the normalization self-healing.
 *
 * <p>Reconciler scope (enrichment-only): only titles with {@code grade_source='enrichment'} —
 * the precise marker that a title was promoted from a draft. Titles with {@code grade_source IS NULL}
 * are duplicate fresh copies of existing library titles and are NOT reconciled here.
 *
 * <p>Backfill scope (all staging): {@link #backfill} covers all staging titles whose computed
 * multi-name target differs from the current basename, regardless of {@code grade_source}.
 */
@Slf4j
public class PromotionFolderRenameReconciler {

    /**
     * A promoted title with a live staging location and its predicted canonical folder name.
     *
     * <p>{@code actressNames} is the ordered list used to build {@code targetName} — filing actress
     * first, then co-credits in rowid order, sentinels excluded.
     */
    public record Candidate(long titleId, String code, List<String> actressNames,
                            String currentPath, String targetName, boolean needsRename) {
        /** Convenience accessor: the filing actress name (first in the list), or null. */
        public String actressName() {
            return actressNames.isEmpty() ? null : actressNames.get(0);
        }
    }

    /** Tally of a reconcile pass. */
    public record ReconcileResult(int candidates, int renamed, int alreadyOk, int failed) {}

    // Scope: enrichment-promoted titles on any serviceable staging volume with a non-NULL filing actress.
    private static final String FIND_PROMOTED_SQL = """
            SELECT t.id, t.code, tl.path AS path
            FROM titles t
            JOIN title_locations tl ON tl.title_id = t.id AND tl.volume_id IN (<vols>) AND tl.stale_since IS NULL
            JOIN actresses a ON a.id = t.actress_id
            WHERE t.grade_source = 'enrichment'
              AND a.canonical_name IS NOT NULL AND a.canonical_name <> ''
            GROUP BY t.id
            ORDER BY t.id
            LIMIT :limit
            """;

    // Scope: all staging titles on any serviceable staging volume with a non-NULL filing actress.
    private static final String FIND_ALL_SQL = """
            SELECT t.id, t.code, tl.path AS path
            FROM titles t
            JOIN title_locations tl ON tl.title_id = t.id AND tl.volume_id IN (<vols>) AND tl.stale_since IS NULL
            WHERE t.actress_id IS NOT NULL
            GROUP BY t.id
            ORDER BY t.id
            LIMIT :limit
            """;

    private final Jdbi jdbi;
    private final TitleFolderRenamer renamer;
    private final Set<String> serviceableVolumeIds;

    public PromotionFolderRenameReconciler(Jdbi jdbi, TitleFolderRenamer renamer,
                                           Set<String> serviceableVolumeIds) {
        this.jdbi = jdbi;
        this.renamer = renamer;
        this.serviceableVolumeIds = serviceableVolumeIds;
    }

    /** Back-compat single-volume ctor. */
    public PromotionFolderRenameReconciler(Jdbi jdbi, TitleFolderRenamer renamer, String unsortedVolumeId) {
        this(jdbi, renamer, Set.of(unsortedVolumeId));
    }

    /**
     * Finds enrichment-promoted titles with a live location on the unsorted volume, computing for
     * each its predicted canonical target folder name and whether a rename is needed. Returns ALL
     * candidates (including those already correctly named); callers filter on
     * {@link Candidate#needsRename()}.
     *
     * <p>Scope: {@code grade_source='enrichment'} only.
     */
    public List<Candidate> findCandidates(int limit) {
        return findCandidatesWithSql(FIND_PROMOTED_SQL, limit);
    }

    /**
     * Finds ALL staging titles with a non-NULL filing actress (regardless of {@code grade_source})
     * and computes their multi-name target. Used by {@link #backfill}.
     *
     * <p>Scope: any {@code grade_source}; {@code actress_id IS NOT NULL}.
     */
    public List<Candidate> findAllCandidates(int limit) {
        return findCandidatesWithSql(FIND_ALL_SQL, limit);
    }

    private List<Candidate> findCandidatesWithSql(String sql, int limit) {
        return jdbi.withHandle(h -> {
            List<Candidate> out = new ArrayList<>();
            h.createQuery(sql)
                    .bindList("vols", List.copyOf(serviceableVolumeIds))
                    .bind("limit", limit)
                    .map((rs, ctx) -> {
                        long titleId = rs.getLong("id");
                        String code = rs.getString("code");
                        String path = rs.getString("path");
                        // Derive the full ordered cast list from canonical DB state.
                        List<String> names = StagingCastHelper.orderedNamesForTitle(h, titleId);
                        if (names.isEmpty()) return null; // NULL actress_id — skip
                        String currentName = TitleFolderRenamer.basename(path);
                        String descriptor = TitleFolderRenamer.extractDescriptor(currentName, code);
                        String targetName = TitleFolderRenamer.targetFolderName(names, descriptor, code);
                        boolean needsRename = !targetName.equals(currentName);
                        return new Candidate(titleId, code, names, path, targetName, needsRename);
                    })
                    .forEach(c -> { if (c != null) out.add(c); });
            return out;
        });
    }

    /**
     * Re-runs the canonical rename for each stranded enrichment-promoted candidate. One failure
     * never aborts the batch.
     *
     * @return a tally of candidates considered, renames performed, already-correct titles, and failures.
     */
    public ReconcileResult reconcile(int limit) {
        return reconcileCandidates(findCandidates(limit));
    }

    /**
     * Backfill: finds ALL staging titles (not just enrichment-promoted) whose computed multi-name
     * target differs from the current basename, and renames them via the existing dual-rewrite path.
     *
     * <p>NULL-{@code actress_id} titles are automatically skipped (the helper returns an empty list).
     * This method is a reusable entry point; the caller controls when to invoke it (e.g. post
     * rebuild+restart, or on-demand via an MCP tool). No live SMB ops are performed when there
     * are no mismatches. Results are identical to {@link #reconcile} but with broader scope.
     *
     * @param limit maximum number of staging titles to inspect.
     * @return a tally of candidates considered, renames performed, already-correct titles, and failures.
     */
    public ReconcileResult backfill(int limit) {
        List<Candidate> candidates = findAllCandidates(limit);
        ReconcileResult result = reconcileCandidates(candidates);
        if (result.renamed() > 0 || result.failed() > 0) {
            log.info("backfill: pass complete — candidates={} renamed={} alreadyOk={} failed={}",
                    result.candidates(), result.renamed(), result.alreadyOk(), result.failed());
        }
        return result;
    }

    private ReconcileResult reconcileCandidates(List<Candidate> candidates) {
        int renamed = 0, alreadyOk = 0, failed = 0;

        for (Candidate c : candidates) {
            if (!c.needsRename()) {
                alreadyOk++;
                continue;
            }
            try {
                TitleFolderRenamer.RenameOutcome outcome =
                        renamer.renamePreservingDescriptor(c.titleId(), c.actressNames(), c.code());
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
