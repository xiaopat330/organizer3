package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Fold one actress record ({@code from}) into another ({@code into}) as a single-transaction
 * DB-only operation. No filesystem changes.
 *
 * <p>Operations (in order, all transactional):
 * <ol>
 *   <li>Reassign {@code titles.actress_id} from → into</li>
 *   <li>Rewrite {@code title_actresses} rows; junction rows that already duplicate an
 *       existing (title_id, into) pair are dropped rather than migrated</li>
 *   <li>Add {@code from}'s canonical name as an alias of {@code into}</li>
 *   <li>Migrate {@code from}'s aliases to {@code into}, skipping duplicates</li>
 *   <li>Update {@code into}'s flags per merge policy (see {@link #mergeFlags})</li>
 *   <li>Delete the {@code from} row</li>
 * </ol>
 *
 * <p>Defaults to {@code dryRun: true} — returns the full plan without executing anything.
 * Only with {@code dryRun: false} does the tool commit.
 *
 * <p>Flag merge policy:
 * <ul>
 *   <li>{@code favorite}: OR</li>
 *   <li>{@code bookmark}: OR; {@code bookmarkedAt} = earliest non-null</li>
 *   <li>{@code grade}: stronger of the two (SSS &gt; SS &gt; ... &gt; F); null treated as lowest</li>
 *   <li>{@code rejected}: AND — merging into a non-rejected actress clears rejected</li>
 *   <li>{@code visitCount}: sum</li>
 *   <li>{@code lastVisitedAt}: later of the two non-null values</li>
 * </ul>
 */
@Slf4j
public class MergeActressesTool implements Tool {

    private final Jdbi jdbi;
    private final ActressRepository actressRepo;

    public MergeActressesTool(Jdbi jdbi, ActressRepository actressRepo) {
        this.jdbi = jdbi;
        this.actressRepo = actressRepo;
    }

    @Override public String name()        { return "merge_actresses"; }
    @Override public String description() {
        return "Fold one actress record into another (DB-only, no filesystem changes). "
             + "Reassigns titles, rewrites title_actresses rows, migrates aliases, merges flags, "
             + "deletes the folded-in row. Defaults to dryRun:true — returns the full plan.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("into",   "integer", "Actress id to keep. All data from 'from' is folded into this record.")
                .prop("from",   "integer", "Actress id to fold in. This record is deleted after merge.")
                .prop("dryRun", "boolean", "If true (default), return the plan without committing.", true)
                .require("into", "from")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long intoId = Schemas.requireLong(args, "into");
        long fromId = Schemas.requireLong(args, "from");
        boolean dryRun = Schemas.optBoolean(args, "dryRun", true);
        return merge(jdbi, actressRepo, intoId, fromId, dryRun);
    }

    /**
     * Core merge entry-point. Reusable from the web layer; {@link #call(JsonNode)} is a thin
     * adapter over this for the MCP interface.
     *
     * @throws IllegalArgumentException if {@code intoId == fromId} or either id is unknown
     */
    public static Result merge(Jdbi jdbi, ActressRepository actressRepo,
                               long intoId, long fromId, boolean dryRun) {
        if (intoId == fromId) {
            throw new IllegalArgumentException("'into' and 'from' must be different actress ids");
        }

        Actress intoActress = actressRepo.findById(intoId).orElseThrow(
                () -> new IllegalArgumentException("No actress with id " + intoId + " (into)"));
        Actress fromActress = actressRepo.findById(fromId).orElseThrow(
                () -> new IllegalArgumentException("No actress with id " + fromId + " (from)"));

        log.info("ActressMerge: start — into={}/\"{}\" from={}/\"{}\" dryRun={}",
                intoId, intoActress.getCanonicalName(), fromId, fromActress.getCanonicalName(), dryRun);

        MergedFlags merged = mergeFlags(intoActress, fromActress);

        return jdbi.inTransaction(h -> {
            Plan plan = buildPlan(h, intoActress, fromActress, merged);
            log.info("ActressMerge: plan — {}", plan.summary());
            if (!dryRun) {
                execute(h, intoId, fromId, fromActress.getCanonicalName(), merged);
                log.info("ActressMerge: committed — into={} from={} (row deleted)", intoId, fromId);
            } else {
                log.info("ActressMerge: dry-run only — no changes committed");
            }
            return new Result(dryRun, plan);
        });
    }

    // ── plan computation (pure SELECT queries) ──────────────────────────────

    private static Plan buildPlan(Handle h, Actress into, Actress from, MergedFlags merged) {
        long intoId = into.getId();
        long fromId = from.getId();

        int titlesReassigned = h.createQuery("SELECT COUNT(*) FROM titles WHERE actress_id = :from")
                .bind("from", fromId).mapTo(Integer.class).one();

        int junctionMigrate = h.createQuery("""
                SELECT COUNT(*) FROM title_actresses
                WHERE actress_id = :from
                  AND title_id NOT IN (SELECT title_id FROM title_actresses WHERE actress_id = :into)
                """).bind("from", fromId).bind("into", intoId).mapTo(Integer.class).one();

        int junctionDropDup = h.createQuery("""
                SELECT COUNT(*) FROM title_actresses
                WHERE actress_id = :from
                  AND title_id IN (SELECT title_id FROM title_actresses WHERE actress_id = :into)
                """).bind("from", fromId).bind("into", intoId).mapTo(Integer.class).one();

        int aliasCanonicalInsert = h.createQuery("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM actress_aliases
                    WHERE actress_id = :into AND LOWER(alias_name) = LOWER(:name))
                THEN 0 ELSE 1 END
                """).bind("into", intoId).bind("name", from.getCanonicalName())
                .mapTo(Integer.class).one();

        int aliasMigrate = h.createQuery("""
                SELECT COUNT(*) FROM actress_aliases
                WHERE actress_id = :from
                  AND LOWER(alias_name) NOT IN (
                      SELECT LOWER(alias_name) FROM actress_aliases WHERE actress_id = :into
                  )
                """).bind("from", fromId).bind("into", intoId).mapTo(Integer.class).one();

        int aliasDropDup = h.createQuery("""
                SELECT COUNT(*) FROM actress_aliases
                WHERE actress_id = :from
                  AND LOWER(alias_name) IN (
                      SELECT LOWER(alias_name) FROM actress_aliases WHERE actress_id = :into
                  )
                """).bind("from", fromId).bind("into", intoId).mapTo(Integer.class).one();

        List<Change> changes = new ArrayList<>();
        changes.add(new Change("update", "titles", titlesReassigned,
                "actress_id = " + fromId, "actress_id = " + intoId));
        changes.add(new Change("update", "title_actresses", junctionMigrate,
                "actress_id = " + fromId + " (migrate)", "actress_id = " + intoId));
        changes.add(new Change("delete", "title_actresses", junctionDropDup,
                "actress_id = " + fromId + " (already linked to " + intoId + ")", null));
        changes.add(new Change("insert", "actress_aliases", aliasCanonicalInsert,
                null, "(actress_id=" + intoId + ", alias_name='" + from.getCanonicalName() + "')"));
        changes.add(new Change("insert", "actress_aliases", aliasMigrate,
                "from actress_aliases where actress_id = " + fromId,
                "actress_id = " + intoId));
        changes.add(new Change("delete", "actress_aliases", aliasDropDup + aliasMigrate,
                "actress_id = " + fromId, null));
        changes.add(new Change("update", "actresses", 1,
                "id = " + intoId, "flags merged per policy"));
        changes.add(new Change("delete", "actresses", 1,
                "id = " + fromId, null));

        String summary = String.format("Merge actress %d '%s' into %d '%s' — %d title(s) reassigned, %d alias(es) migrated",
                fromId, from.getCanonicalName(), intoId, into.getCanonicalName(),
                titlesReassigned + junctionMigrate, aliasCanonicalInsert + aliasMigrate);

        return new Plan(summary, changes, new FlagsAfter(
                merged.favorite, merged.bookmark, merged.grade == null ? null : merged.grade.name(),
                merged.rejected, merged.visitCount));
    }

    // ── execution ───────────────────────────────────────────────────────────

    private static void execute(Handle h, long intoId, long fromId, String fromCanonicalName, MergedFlags merged) {
        // 1. Reassign filing actress
        int titlesReassigned = h.createUpdate("UPDATE titles SET actress_id = :into WHERE actress_id = :from")
                .bind("into", intoId).bind("from", fromId).execute();
        log.info("ActressMerge step 1: reassigned titles.actress_id — rows={}", titlesReassigned);

        // 2. Migrate junction rows (INSERT OR IGNORE then DELETE remaining)
        int junctionInserted = h.createUpdate("""
                INSERT OR IGNORE INTO title_actresses (title_id, actress_id)
                SELECT title_id, :into FROM title_actresses WHERE actress_id = :from
                """).bind("into", intoId).bind("from", fromId).execute();
        int junctionDeleted = h.createUpdate("DELETE FROM title_actresses WHERE actress_id = :from")
                .bind("from", fromId).execute();
        log.info("ActressMerge step 2: title_actresses migrated — inserted={} deleted={}",
                junctionInserted, junctionDeleted);

        // 3. Add from's canonical name as alias of into
        int canonicalInserted = h.createUpdate("""
                INSERT OR IGNORE INTO actress_aliases (actress_id, alias_name)
                VALUES (:into, :name)
                """).bind("into", intoId).bind("name", fromCanonicalName).execute();
        log.info("ActressMerge step 3: added canonical-name alias \"{}\" → into={} — inserted={}",
                fromCanonicalName, intoId, canonicalInserted);

        // 4. Migrate from's aliases to into (INSERT OR IGNORE preserves into's existing)
        int aliasInserted = h.createUpdate("""
                INSERT OR IGNORE INTO actress_aliases (actress_id, alias_name)
                SELECT :into, alias_name FROM actress_aliases WHERE actress_id = :from
                """).bind("into", intoId).bind("from", fromId).execute();
        int aliasDeleted = h.createUpdate("DELETE FROM actress_aliases WHERE actress_id = :from")
                .bind("from", fromId).execute();
        log.info("ActressMerge step 4: actress_aliases migrated — inserted={} deleted={}",
                aliasInserted, aliasDeleted);

        // 5. Update into's flags per merge policy
        h.createUpdate("""
                UPDATE actresses SET
                  favorite = :favorite,
                  bookmark = :bookmark,
                  bookmarked_at = :bookmarkedAt,
                  grade = :grade,
                  rejected = :rejected,
                  visit_count = :visitCount,
                  last_visited_at = :lastVisitedAt
                WHERE id = :into
                """)
                .bind("into", intoId)
                .bind("favorite", merged.favorite ? 1 : 0)
                .bind("bookmark", merged.bookmark ? 1 : 0)
                .bind("bookmarkedAt", merged.bookmarkedAt == null ? null : merged.bookmarkedAt.toString())
                .bind("grade", merged.grade == null ? null : merged.grade.name())
                .bind("rejected", merged.rejected ? 1 : 0)
                .bind("visitCount", merged.visitCount)
                .bind("lastVisitedAt", merged.lastVisitedAt == null ? null : merged.lastVisitedAt.toString())
                .execute();
        log.info("ActressMerge step 5: into={} flags updated — favorite={} bookmark={} grade={} rejected={} visitCount={}",
                intoId, merged.favorite, merged.bookmark,
                merged.grade == null ? "null" : merged.grade.name(),
                merged.rejected, merged.visitCount);

        // 6. Delete from's row
        h.createUpdate("DELETE FROM actresses WHERE id = :from")
                .bind("from", fromId).execute();
        log.info("ActressMerge step 6: deleted source actress id={}", fromId);
    }

    // ── flag merge policy ───────────────────────────────────────────────────

    static MergedFlags mergeFlags(Actress into, Actress from) {
        boolean favorite = into.isFavorite() || from.isFavorite();
        boolean bookmark = into.isBookmark() || from.isBookmark();
        LocalDateTime bookmarkedAt = earliestNonNull(into.getBookmarkedAt(), from.getBookmarkedAt());
        if (!bookmark) bookmarkedAt = null;
        Actress.Grade grade = strongerGrade(into.getGrade(), from.getGrade());
        boolean rejected = into.isRejected() && from.isRejected();
        int visitCount = Math.max(0, safeVisitCount(into)) + Math.max(0, safeVisitCount(from));
        LocalDateTime lastVisitedAt = latestNonNull(into.getLastVisitedAt(), from.getLastVisitedAt());
        return new MergedFlags(favorite, bookmark, bookmarkedAt, grade, rejected, visitCount, lastVisitedAt);
    }

    private static int safeVisitCount(Actress a) {
        // Actress.visitCount is a primitive int — 0 if unset. Guard negative defensively.
        return Math.max(0, a.getVisitCount());
    }

    private static Actress.Grade strongerGrade(Actress.Grade a, Actress.Grade b) {
        if (a == null) return b;
        if (b == null) return a;
        // Lower ordinal = stronger (SSS=0, F=13)
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    private static LocalDateTime earliestNonNull(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static LocalDateTime latestNonNull(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    // ── result shapes ───────────────────────────────────────────────────────

    record MergedFlags(boolean favorite, boolean bookmark, LocalDateTime bookmarkedAt,
                       Actress.Grade grade, boolean rejected, int visitCount,
                       LocalDateTime lastVisitedAt) {}

    public record Change(String op, String table, int rows, String predicate, String setting) {}
    public record FlagsAfter(boolean favorite, boolean bookmark, String grade,
                             boolean rejected, int visitCount) {}
    public record Plan(String summary, List<Change> changes, FlagsAfter flagsAfter) {}
    public record Result(boolean dryRun, Plan plan) {}
}
