package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Removes a single actress credit (a {@code title_actresses} junction row) from a title.
 *
 * <p>Fills the gap that previously required offline SQL: removing one wrong cast credit while
 * leaving the rest of the title intact. Distinct from {@code titles.actress_id} (the "filing
 * actress" — the folder a title lives under); when the removed credit IS the filing actress,
 * the filing is reassigned to the lowest remaining credited actress (deterministic), or set to
 * null when no credits remain (force case).
 *
 * <p>Refuses to remove the last remaining credit unless {@code force:true} is passed.
 * Defaults to {@code dryRun:true}.
 */
@Slf4j
public class RemoveTitleCreditTool implements Tool {

    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;
    private final TitleActressRepository titleActressRepo;
    private final Jdbi jdbi;
    private final CurationLog curationLog;

    public RemoveTitleCreditTool(TitleRepository titleRepo, ActressRepository actressRepo,
                                 TitleActressRepository titleActressRepo, Jdbi jdbi,
                                 CurationLog curationLog) {
        this.titleRepo = titleRepo;
        this.actressRepo = actressRepo;
        this.titleActressRepo = titleActressRepo;
        this.jdbi = jdbi;
        this.curationLog = curationLog;
    }

    @Override public String name() { return "remove_title_credit"; }

    @Override
    public String description() {
        return "Remove a single actress credit (title_actresses row) from a title. Reassigns the "
             + "filing actress if needed. Defaults to dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("title_id",     "integer", "Title id. Either this or 'title_code' is required.")
                .prop("title_code",   "string",  "Product code, e.g. 'SOE-793'. Case-insensitive. Either this or 'title_id' is required.")
                .prop("actress_id",   "integer", "Actress id of the credit to remove. Either this or 'actress_name' is required.")
                .prop("actress_name", "string",  "Canonical name or alias of the actress to remove. Either this or 'actress_id' is required.")
                .prop("dry_run",      "boolean", "If true (default), return the plan without writing.", true)
                .prop("force",        "boolean", "If true, permit removing the LAST remaining credit (leaving the title with zero cast). Default false.", false)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long titleIdArg    = Schemas.optLong(args, "title_id", -1);
        String titleCode   = Schemas.optString(args, "title_code", null);
        long actressIdArg  = Schemas.optLong(args, "actress_id", -1);
        String actressName = Schemas.optString(args, "actress_name", null);
        boolean dryRun     = Schemas.optBoolean(args, "dry_run", true);
        boolean force      = Schemas.optBoolean(args, "force", false);

        if (titleIdArg < 0 && (titleCode == null || titleCode.isBlank())) {
            throw new IllegalArgumentException("Must provide either 'title_id' or 'title_code'");
        }
        if (actressIdArg < 0 && (actressName == null || actressName.isBlank())) {
            throw new IllegalArgumentException("Must provide either 'actress_id' or 'actress_name'");
        }

        // ── Resolve title ────────────────────────────────────────────────────
        Optional<Title> titleOpt = titleIdArg >= 0
                ? titleRepo.findById(titleIdArg)
                : titleRepo.findByCode(titleCode.trim().toUpperCase());
        if (titleOpt.isEmpty()) {
            return error("title_not_found",
                    "no title found for " + (titleIdArg >= 0 ? "id=" + titleIdArg : "code=" + titleCode));
        }
        Title title = titleOpt.get();

        // ── Resolve actress ──────────────────────────────────────────────────
        Optional<Actress> actressOpt = actressIdArg >= 0
                ? actressRepo.findById(actressIdArg)
                : actressRepo.resolveByName(actressName);
        if (actressOpt.isEmpty()) {
            return error("actress_not_found",
                    "no actress found for " + (actressIdArg >= 0 ? "id=" + actressIdArg : "name=" + actressName));
        }
        Actress actress = actressOpt.get();
        long removeId = actress.getId();

        // ── Verify the credit exists ─────────────────────────────────────────
        List<Long> currentCredits = titleActressRepo.findActressIdsByTitle(title.getId());
        if (!currentCredits.contains(removeId)) {
            Map<String, Object> err = error("credit_not_found",
                    "actress id=" + removeId + " ('" + actress.getCanonicalName()
                    + "') is not currently credited on title " + title.getCode());
            err.put("currentCredits", currentCredits);
            return err;
        }

        // ── Compute remaining credits ────────────────────────────────────────
        List<Long> remainingIds = currentCredits.stream()
                .filter(id -> id != removeId)
                .sorted()
                .toList();
        boolean willLeaveNoCredits = remainingIds.isEmpty();

        if (willLeaveNoCredits && !force) {
            logCuration(title, removeId, actress.getCanonicalName(),
                    title.getActressId(), title.getActressId(), dryRun, "refused");
            return withHint(title);
        }

        // ── Filing-actress handling ──────────────────────────────────────────
        Long oldFiling = title.getActressId();
        Long newFiling = oldFiling;
        boolean filingChanged = false;
        if (oldFiling != null && oldFiling == removeId) {
            newFiling = remainingIds.isEmpty() ? null : remainingIds.get(0);
            filingChanged = true;
        }

        List<Map<String, Object>> remainingCredits = describeActresses(remainingIds);

        // ── Dry-run: return the plan ─────────────────────────────────────────
        if (dryRun) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("ok", true);
            plan.put("dryRun", true);
            plan.put("titleId", title.getId());
            plan.put("titleCode", title.getCode());
            plan.put("removingActress", actressRef(removeId, actress.getCanonicalName()));
            plan.put("remainingCredits", remainingCredits);
            plan.put("oldFilingActressId", oldFiling);
            plan.put("newFilingActressId", newFiling);
            plan.put("willLeaveNoCredits", willLeaveNoCredits);
            logCuration(title, removeId, actress.getCanonicalName(), oldFiling, newFiling,
                    true, "dry-run");
            return plan;
        }

        // ── Live: both writes in one transaction (atomic) ────────────────────
        final Long finalNewFiling = newFiling;
        final boolean finalFilingChanged = filingChanged;
        jdbi.useTransaction(h -> {
            h.createUpdate("DELETE FROM title_actresses WHERE title_id = :tid AND actress_id = :aid")
                    .bind("tid", title.getId())
                    .bind("aid", removeId)
                    .execute();
            if (finalFilingChanged) {
                var update = h.createUpdate("UPDATE titles SET actress_id = :f WHERE id = :tid")
                        .bind("tid", title.getId());
                if (finalNewFiling == null) {
                    update.bindNull("f", Types.BIGINT);
                } else {
                    update.bind("f", finalNewFiling);
                }
                update.execute();
            }
        });

        log.info("remove_title_credit: titleId={} code={} removedActressId={} ({}) remaining={} oldFiling={} newFiling={}",
                title.getId(), title.getCode(), removeId, actress.getCanonicalName(),
                remainingIds, oldFiling, newFiling);

        logCuration(title, removeId, actress.getCanonicalName(), oldFiling, newFiling, false, "ok");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("dryRun", false);
        result.put("titleId", title.getId());
        result.put("titleCode", title.getCode());
        result.put("removingActress", actressRef(removeId, actress.getCanonicalName()));
        result.put("remainingCredits", remainingCredits);
        result.put("oldFilingActressId", oldFiling);
        result.put("newFilingActressId", newFiling);
        result.put("willLeaveNoCredits", willLeaveNoCredits);
        result.put("committed", true);
        return result;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> withHint(Title title) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("ok", false);
        err.put("error", "would_leave_no_credits");
        err.put("message", "removing this credit would leave title " + title.getCode() + " with zero cast");
        err.put("hint", "pass force:true to remove anyway, or use delete_title");
        return err;
    }

    private List<Map<String, Object>> describeActresses(List<Long> ids) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (long id : ids) {
            String n = actressRepo.findById(id).map(Actress::getCanonicalName).orElse(null);
            out.add(actressRef(id, n));
        }
        return out;
    }

    private static Map<String, Object> actressRef(long id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", code);
        m.put("message", message);
        return m;
    }

    private void logCuration(Title title, long removedId, String removedName,
                             Long oldFiling, Long newFiling, boolean dryRun, String status) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("titleId", title.getId());
        inputs.put("titleCode", title.getCode());
        inputs.put("removedActressId", removedId);
        inputs.put("removedActressName", removedName);
        inputs.put("dryRun", dryRun);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("oldFilingActressId", oldFiling);
        after.put("newFilingActressId", newFiling);
        CurationLogRecord rec = new CurationLogRecord(
                Instant.now(), name(), "mcp", "mcp-" + Thread.currentThread().getName(),
                inputs, null, null, after, status, List.of());
        curationLog.append("unknown", rec);
    }
}
