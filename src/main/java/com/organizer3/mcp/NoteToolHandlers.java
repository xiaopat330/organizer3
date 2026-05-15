package com.organizer3.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.notes.OrphanNoteFinder;

import java.util.List;

/**
 * MCP tool handlers for note-related operations.
 *
 * <p>Contains two tools that mirror the {@code find_stale_locations} /
 * {@code prune_stale_locations} pattern for orphan-note health sweeps:
 * <ul>
 *   <li>{@link FindOrphanNotes} — read-only; returns notes whose entity no longer exists.</li>
 *   <li>{@link PruneOrphanNotes} — mutating; deletes the orphan rows, with optional dry-run.</li>
 * </ul>
 */
public final class NoteToolHandlers {

    private NoteToolHandlers() {}

    // ── find_orphan_notes ────────────────────────────────────────────────────

    /**
     * Returns every note whose backing entity (actress or title) has been deleted.
     * Outputs a list of {@code {entityType, entityId, body, updatedAt}} descriptors.
     */
    public static class FindOrphanNotes implements Tool {

        private final OrphanNoteFinder finder;

        public FindOrphanNotes(OrphanNoteFinder finder) {
            this.finder = finder;
        }

        @Override public String name() { return "find_orphan_notes"; }

        @Override
        public String description() {
            return "Return notes whose entity_id no longer exists in actresses or titles — "
                 + "the entity was deleted but the note was not cleaned up. "
                 + "Returns {entityType, entityId, body, updatedAt} for each orphan.";
        }

        @Override
        public JsonNode inputSchema() {
            return Schemas.empty();
        }

        @Override
        public Object call(JsonNode args) {
            List<OrphanNoteFinder.OrphanNote> orphans = finder.findAll();
            return new Result(orphans.size(), orphans);
        }

        public record Result(int count, List<OrphanNoteFinder.OrphanNote> orphanNotes) {}
    }

    // ── prune_orphan_notes ───────────────────────────────────────────────────

    /**
     * Companion to {@link FindOrphanNotes}: removes notes whose entity no longer exists.
     *
     * <p>When {@code dryRun=true} (the default), calls {@link OrphanNoteFinder#findAll()} and
     * reports how many rows would be deleted without modifying anything.
     * When {@code dryRun=false}, calls {@link OrphanNoteFinder#pruneAll()} and returns the
     * actual delete count.
     */
    public static class PruneOrphanNotes implements Tool {

        private final OrphanNoteFinder finder;

        public PruneOrphanNotes(OrphanNoteFinder finder) {
            this.finder = finder;
        }

        @Override public String name() { return "prune_orphan_notes"; }

        @Override
        public String description() {
            return "Delete notes whose entity_id no longer exists in actresses or titles. "
                 + "Default dryRun:true — returns the count of notes that would be deleted "
                 + "without modifying anything. Pass dryRun:false to commit the deletions.";
        }

        @Override
        public JsonNode inputSchema() {
            return Schemas.object()
                    .prop("dryRun", "boolean",
                            "If true (default), report the count without deleting.", true)
                    .build();
        }

        @Override
        public Object call(JsonNode args) {
            boolean dryRun = Schemas.optBoolean(args, "dryRun", true);
            if (dryRun) {
                int count = finder.findAll().size();
                return new Result(true, count,
                        "Would delete " + count + " orphan note(s). Pass dryRun:false to commit.");
            } else {
                int deleted = finder.pruneAll();
                return new Result(false, deleted,
                        "Deleted " + deleted + " orphan note(s).");
            }
        }

        public record Result(boolean dryRun, int count, String message) {}
    }
}
