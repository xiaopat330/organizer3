package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Companion to {@code find_stale_locations}: drops title_location rows that the sync path
 * hasn't observed since the volume's most recent {@code last_synced_at}.
 *
 * <p>Two modes:
 * <ul>
 *   <li>Single — pass {@code locationId} to drop one row by id (no staleness check).
 *   <li>Batch — pass {@code volumeId} to drop every row whose {@code last_seen_at} is older
 *       than the volume's {@code last_synced_at}. The eligibility predicate matches
 *       {@code FindStaleLocationsTool} exactly.
 * </ul>
 *
 * <p>If pruning leaves a title with no remaining locations, the title is surfaced in
 * {@code orphanedTitles}; the title row itself is left intact for the caller to handle.
 */
public class PruneStaleLocationsTool implements Tool {

    private final Jdbi jdbi;

    public PruneStaleLocationsTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name() { return "prune_stale_locations"; }

    @Override
    public String description() {
        return "Drop stale title_location rows without running a full sync_volume. "
             + "Specify locationId for a single-row delete, or volumeId for a batch — every row "
             + "whose last_seen_at predates the volume's last_synced_at (same predicate as "
             + "find_stale_locations). Default dryRun:true. Titles left with no locations are "
             + "surfaced in orphanedTitles (not auto-deleted).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("locationId", "integer", "Specific title_locations.id to drop. Mutually exclusive with volumeId.")
                .prop("volumeId",   "string",  "Batch mode: drop all stale rows on this volume. Mutually exclusive with locationId.")
                .prop("dryRun",     "boolean", "If true (default), return the plan without committing.", true)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        Long locationId = args.hasNonNull("locationId") ? args.get("locationId").asLong() : null;
        String volumeId = Schemas.optString(args, "volumeId", null);
        if (volumeId != null && volumeId.isBlank()) volumeId = null;
        boolean dryRun  = Schemas.optBoolean(args, "dryRun", true);

        if (locationId == null && volumeId == null) {
            return error("must provide either locationId or volumeId");
        }
        if (locationId != null && volumeId != null) {
            return error("locationId and volumeId are mutually exclusive");
        }

        final Long locId = locationId;
        final String volId = volumeId;
        return jdbi.inTransaction(h -> {
            // Resolve the rows that would be pruned.
            List<Row> rows = new ArrayList<>();
            if (locId != null) {
                h.createQuery("""
                            SELECT tl.id, tl.title_id, t.code, tl.volume_id, tl.path
                            FROM title_locations tl
                            JOIN titles t ON t.id = tl.title_id
                            WHERE tl.id = :id
                            """)
                 .bind("id", locId)
                 .map((rs, ctx) -> new Row(
                         rs.getLong("id"),
                         rs.getLong("title_id"),
                         rs.getString("code"),
                         rs.getString("volume_id"),
                         rs.getString("path")))
                 .forEach(rows::add);
                if (rows.isEmpty()) {
                    return error("no title_locations row with id=" + locId);
                }
            } else {
                h.createQuery("""
                            SELECT tl.id, tl.title_id, t.code, tl.volume_id, tl.path
                            FROM title_locations tl
                            JOIN volumes v ON v.id = tl.volume_id
                            JOIN titles  t ON t.id = tl.title_id
                            WHERE tl.volume_id = :vol
                              AND v.last_synced_at IS NOT NULL
                              AND tl.last_seen_at < DATE(v.last_synced_at)
                            ORDER BY tl.last_seen_at
                            """)
                 .bind("vol", volId)
                 .map((rs, ctx) -> new Row(
                         rs.getLong("id"),
                         rs.getLong("title_id"),
                         rs.getString("code"),
                         rs.getString("volume_id"),
                         rs.getString("path")))
                 .forEach(rows::add);
            }

            int deleted = 0;
            List<OrphanedTitle> orphans = List.of();

            if (!dryRun && !rows.isEmpty()) {
                List<Long> ids = rows.stream().map(Row::locationId).toList();
                deleted = h.createUpdate("DELETE FROM title_locations WHERE id IN (<ids>)")
                        .bindList("ids", ids)
                        .execute();
                orphans = findOrphanedTitles(h, rows);
            } else if (dryRun && !rows.isEmpty()) {
                // Compute orphan preview: a title would be orphaned if all its live rows are in our prune set.
                orphans = previewOrphanedTitles(h, rows);
            }

            return new Result(dryRun, rows.size(), deleted, rows, orphans);
        });
    }

    private List<OrphanedTitle> findOrphanedTitles(org.jdbi.v3.core.Handle h, List<Row> pruned) {
        List<Long> titleIds = pruned.stream().map(Row::titleId).distinct().toList();
        if (titleIds.isEmpty()) return List.of();
        return h.createQuery("""
                        SELECT t.id, t.code
                        FROM titles t
                        WHERE t.id IN (<ids>)
                          AND NOT EXISTS (SELECT 1 FROM title_locations tl WHERE tl.title_id = t.id)
                        ORDER BY t.code
                        """)
                .bindList("ids", titleIds)
                .map((rs, ctx) -> new OrphanedTitle(rs.getLong("id"), rs.getString("code")))
                .list();
    }

    private List<OrphanedTitle> previewOrphanedTitles(org.jdbi.v3.core.Handle h, List<Row> wouldPrune) {
        List<Long> titleIds = wouldPrune.stream().map(Row::titleId).distinct().toList();
        List<Long> pruneIds = wouldPrune.stream().map(Row::locationId).toList();
        if (titleIds.isEmpty()) return List.of();
        return h.createQuery("""
                        SELECT t.id, t.code
                        FROM titles t
                        WHERE t.id IN (<titleIds>)
                          AND NOT EXISTS (
                              SELECT 1 FROM title_locations tl
                              WHERE tl.title_id = t.id
                                AND tl.id NOT IN (<pruneIds>)
                          )
                        ORDER BY t.code
                        """)
                .bindList("titleIds", titleIds)
                .bindList("pruneIds", pruneIds)
                .map((rs, ctx) -> new OrphanedTitle(rs.getLong("id"), rs.getString("code")))
                .list();
    }

    private static Result error(String message) {
        return new Result(true, 0, 0, List.of(), List.of(), message);
    }

    public record Row(long locationId, long titleId, String titleCode, String volumeId, String path) {}
    public record OrphanedTitle(long titleId, String titleCode) {}
    public record Result(boolean dryRun,
                         int candidateCount,
                         int deletedCount,
                         List<Row> prunedLocations,
                         List<OrphanedTitle> orphanedTitles,
                         String error) {
        public Result(boolean dryRun, int candidateCount, int deletedCount,
                      List<Row> prunedLocations, List<OrphanedTitle> orphanedTitles) {
            this(dryRun, candidateCount, deletedCount, prunedLocations, orphanedTitles, null);
        }
    }
}
