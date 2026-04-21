package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Delete a title record and all its dependent rows (DB-only, no filesystem changes).
 *
 * <p>Operations (in order, transactional):
 * <ol>
 *   <li>Delete {@code title_tags} rows</li>
 *   <li>Delete {@code title_locations} rows</li>
 *   <li>Delete {@code videos} rows</li>
 *   <li>Delete {@code title_actresses} rows</li>
 *   <li>Delete {@code title_effective_tags} rows (redundant with ON DELETE CASCADE but explicit)</li>
 *   <li>Delete the {@code titles} row</li>
 * </ol>
 *
 * <p>Does not touch on-disk files — removing the physical folder is a separate Phase 3
 * operation gated on Trash. Use this tool to clean up ghost / orphaned DB rows (e.g. the
 * parser-bug "covers" titles surfaced by today's folder audit).
 *
 * <p>Defaults to {@code dryRun: true}.
 */
@Slf4j
public class DeleteTitleTool implements Tool {

    private final Jdbi jdbi;
    private final TitleRepository titleRepo;

    public DeleteTitleTool(Jdbi jdbi, TitleRepository titleRepo) {
        this.jdbi = jdbi;
        this.titleRepo = titleRepo;
    }

    @Override public String name()        { return "delete_title"; }
    @Override public String description() {
        return "Delete a title and all its dependent DB rows (locations, videos, credits, tags). "
             + "DB-only — does not touch files. Defaults to dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("id",     "integer", "Title id to delete.")
                .prop("dryRun", "boolean", "If true (default), return the plan without committing.", true)
                .require("id")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long id = Schemas.requireLong(args, "id");
        boolean dryRun = Schemas.optBoolean(args, "dryRun", true);

        Title title = titleRepo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("No title with id " + id));

        return jdbi.inTransaction(h -> {
            Plan plan = buildPlan(h, title);
            if (!dryRun) {
                log.info("MCP delete_title: committing — id={} code=\"{}\" summary=\"{}\"",
                        id, title.getCode(), plan.summary());
                execute(h, id);
                log.info("MCP delete_title: deleted — id={} code=\"{}\"", id, title.getCode());
            } else {
                log.info("MCP delete_title: dry-run — id={} code=\"{}\" summary=\"{}\"",
                        id, title.getCode(), plan.summary());
            }
            return new Result(dryRun, plan);
        });
    }

    private static Plan buildPlan(Handle h, Title title) {
        long id = title.getId();

        int tagRows        = countRows(h, "title_tags",           id);
        int locationRows   = countRows(h, "title_locations",      id);
        int videoRows      = countRows(h, "videos",               id);
        int creditRows     = countRows(h, "title_actresses",      id);
        int effectiveRows  = countRows(h, "title_effective_tags", id);

        List<Change> changes = new ArrayList<>();
        changes.add(new Change("delete", "title_tags",           tagRows));
        changes.add(new Change("delete", "title_locations",      locationRows));
        changes.add(new Change("delete", "videos",               videoRows));
        changes.add(new Change("delete", "title_actresses",      creditRows));
        changes.add(new Change("delete", "title_effective_tags", effectiveRows));
        changes.add(new Change("delete", "titles",               1));

        String summary = String.format(
                "Delete title id=%d code='%s' — %d location(s), %d video(s), %d credit(s), %d tag row(s)",
                id, title.getCode(),
                locationRows, videoRows, creditRows, tagRows + effectiveRows);

        return new Plan(summary, changes);
    }

    private static int countRows(Handle h, String table, long titleId) {
        return h.createQuery("SELECT COUNT(*) FROM " + table + " WHERE title_id = :id")
                .bind("id", titleId)
                .mapTo(Integer.class)
                .one();
    }

    private static void execute(Handle h, long id) {
        deleteRows(h, "title_tags",           id);
        deleteRows(h, "title_locations",      id);
        deleteRows(h, "videos",               id);
        deleteRows(h, "title_actresses",      id);
        deleteRows(h, "title_effective_tags", id);
        h.createUpdate("DELETE FROM titles WHERE id = :id").bind("id", id).execute();
    }

    private static void deleteRows(Handle h, String table, long titleId) {
        h.createUpdate("DELETE FROM " + table + " WHERE title_id = :id")
                .bind("id", titleId)
                .execute();
    }

    public record Change(String op, String table, int rows) {}
    public record Plan(String summary, List<Change> changes) {}
    public record Result(boolean dryRun, Plan plan) {}
}
