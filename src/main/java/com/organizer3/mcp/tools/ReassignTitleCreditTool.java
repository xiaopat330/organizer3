package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.db.AgeAtReleaseRecomputer;
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
 * Reassigns a single actress credit (a {@code title_actresses} junction row) from one actress to
 * another on a title.
 *
 * <p>Fills the gap that previously had no sanctioned path: adding or reassigning a cast credit on a
 * title that lives on a normal library volume (the web "unsorted editor" is scoped only to the
 * "unsorted" volume). The new credit is added <em>before</em> the old credit is removed, inside a
 * single transaction, so the title is never left with zero cast. Distinct from
 * {@code titles.actress_id} (the "filing actress" — the folder a title lives under); when the
 * replaced credit IS the filing actress, the filing is moved to the new actress.
 *
 * <p>Defaults to {@code dryRun:true}.
 */
@Slf4j
public class ReassignTitleCreditTool implements Tool {

    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;
    private final TitleActressRepository titleActressRepo;
    private final Jdbi jdbi;
    private final CurationLog curationLog;
    private final AgeAtReleaseRecomputer ageRecomputer;

    public ReassignTitleCreditTool(TitleRepository titleRepo, ActressRepository actressRepo,
                                   TitleActressRepository titleActressRepo, Jdbi jdbi,
                                   CurationLog curationLog, AgeAtReleaseRecomputer ageRecomputer) {
        this.titleRepo = titleRepo;
        this.actressRepo = actressRepo;
        this.titleActressRepo = titleActressRepo;
        this.jdbi = jdbi;
        this.curationLog = curationLog;
        this.ageRecomputer = ageRecomputer;
    }

    @Override public String name() { return "reassign_title_credit"; }

    @Override
    public String description() {
        return "Reassign a single actress credit (title_actresses row) from one actress to another "
             + "on a title. Adds the new credit and removes the old atomically (never leaves zero "
             + "cast), and moves the filing actress when the replaced credit was the filing actress. "
             + "Defaults to dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("title_id",         "integer", "Title id. Either this or 'title_code' is required.")
                .prop("title_code",       "string",  "Product code, e.g. 'SOE-793'. Case-insensitive. Either this or 'title_id' is required.")
                .prop("from_actress_id",  "integer", "Actress id of the credit to replace. Either this or 'from_actress_name' is required.")
                .prop("from_actress_name","string",  "Canonical name or alias of the actress whose credit is being replaced. Either this or 'from_actress_id' is required.")
                .prop("to_actress_id",    "integer", "Actress id of the new credit. Either this or 'to_actress_name' is required.")
                .prop("to_actress_name",  "string",  "Canonical name or alias of the new actress. Either this or 'to_actress_id' is required.")
                .prop("dry_run",          "boolean", "If true (default), return the plan without writing.", true)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long titleIdArg     = Schemas.optLong(args, "title_id", -1);
        String titleCode    = Schemas.optString(args, "title_code", null);
        long fromIdArg      = Schemas.optLong(args, "from_actress_id", -1);
        String fromName     = Schemas.optString(args, "from_actress_name", null);
        long toIdArg        = Schemas.optLong(args, "to_actress_id", -1);
        String toName       = Schemas.optString(args, "to_actress_name", null);
        boolean dryRun      = Schemas.optBoolean(args, "dry_run", true);

        if (titleIdArg < 0 && (titleCode == null || titleCode.isBlank())) {
            throw new IllegalArgumentException("Must provide either 'title_id' or 'title_code'");
        }
        if (fromIdArg < 0 && (fromName == null || fromName.isBlank())) {
            throw new IllegalArgumentException("Must provide either 'from_actress_id' or 'from_actress_name'");
        }
        if (toIdArg < 0 && (toName == null || toName.isBlank())) {
            throw new IllegalArgumentException("Must provide either 'to_actress_id' or 'to_actress_name'");
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

        // ── Resolve from-actress (the credit being replaced) ─────────────────
        Optional<Actress> fromOpt = fromIdArg >= 0
                ? actressRepo.findById(fromIdArg)
                : actressRepo.resolveByName(fromName);
        if (fromOpt.isEmpty()) {
            return error("from_actress_not_found",
                    "no actress found for " + (fromIdArg >= 0 ? "id=" + fromIdArg : "name=" + fromName));
        }
        Actress fromActress = fromOpt.get();
        long fromId = fromActress.getId();

        // ── Resolve to-actress (the new credit) ──────────────────────────────
        Optional<Actress> toOpt = toIdArg >= 0
                ? actressRepo.findById(toIdArg)
                : actressRepo.resolveByName(toName);
        if (toOpt.isEmpty()) {
            return error("to_actress_not_found",
                    "no actress found for " + (toIdArg >= 0 ? "id=" + toIdArg : "name=" + toName));
        }
        Actress toActress = toOpt.get();
        long toId = toActress.getId();

        if (fromId == toId) {
            return error("same_actress", "from and to actress are the same (id=" + fromId + ")");
        }

        // ── Verify the credit being replaced exists ──────────────────────────
        List<Long> currentCredits = titleActressRepo.findActressIdsByTitle(title.getId());
        if (!currentCredits.contains(fromId)) {
            Map<String, Object> err = error("credit_not_found",
                    "actress id=" + fromId + " ('" + fromActress.getCanonicalName()
                    + "') is not currently credited on title " + title.getCode());
            err.put("currentCredits", currentCredits);
            return err;
        }

        boolean toAlreadyCredited = currentCredits.contains(toId);

        // ── Filing-actress handling ──────────────────────────────────────────
        Long oldFiling = title.getActressId();
        Long newFiling = oldFiling;
        boolean filingChanged = false;
        if (oldFiling != null && oldFiling == fromId) {
            newFiling = toId;
            filingChanged = true;
        }

        // ── Compute resulting credits (drop from, add to if not already) ─────
        List<Long> resultingIds = new ArrayList<>();
        for (long id : currentCredits) {
            if (id != fromId) {
                resultingIds.add(id);
            }
        }
        if (!resultingIds.contains(toId)) {
            resultingIds.add(toId);
        }
        resultingIds.sort(Long::compareTo);
        List<Map<String, Object>> resultingCredits = describeActresses(resultingIds);

        // ── Dry-run: return the plan ─────────────────────────────────────────
        if (dryRun) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("ok", true);
            plan.put("dryRun", true);
            plan.put("titleId", title.getId());
            plan.put("titleCode", title.getCode());
            plan.put("fromActress", actressRef(fromId, fromActress.getCanonicalName()));
            plan.put("toActress", actressRef(toId, toActress.getCanonicalName()));
            plan.put("toAlreadyCredited", toAlreadyCredited);
            plan.put("resultingCredits", resultingCredits);
            plan.put("oldFilingActressId", oldFiling);
            plan.put("newFilingActressId", newFiling);
            plan.put("filingChanged", filingChanged);
            logCuration(title, fromId, fromActress.getCanonicalName(), toId,
                    toActress.getCanonicalName(), oldFiling, newFiling, true, "dry-run");
            return plan;
        }

        // ── Live: add-then-remove in one transaction (atomic, never empty) ───
        final Long finalNewFiling = newFiling;
        final boolean finalFilingChanged = filingChanged;
        jdbi.useTransaction(h -> {
            // Add the new credit first so the title is never momentarily empty.
            h.createUpdate("INSERT OR IGNORE INTO title_actresses (title_id, actress_id) VALUES (:tid, :aid)")
                    .bind("tid", title.getId())
                    .bind("aid", toId)
                    .execute();
            h.createUpdate("DELETE FROM title_actresses WHERE title_id = :tid AND actress_id = :aid")
                    .bind("tid", title.getId())
                    .bind("aid", fromId)
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

        int changed = ageRecomputer.recomputeAll();
        log.info("reassign_title_credit age_at_release recompute: {} rows changed (titleId={})", changed, title.getId());

        log.info("reassign_title_credit: titleId={} code={} fromActressId={} ({}) toActressId={} ({}) oldFiling={} newFiling={}",
                title.getId(), title.getCode(), fromId, fromActress.getCanonicalName(),
                toId, toActress.getCanonicalName(), oldFiling, newFiling);

        logCuration(title, fromId, fromActress.getCanonicalName(), toId,
                toActress.getCanonicalName(), oldFiling, newFiling, false, "ok");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("dryRun", false);
        result.put("titleId", title.getId());
        result.put("titleCode", title.getCode());
        result.put("fromActress", actressRef(fromId, fromActress.getCanonicalName()));
        result.put("toActress", actressRef(toId, toActress.getCanonicalName()));
        result.put("toAlreadyCredited", toAlreadyCredited);
        result.put("resultingCredits", resultingCredits);
        result.put("oldFilingActressId", oldFiling);
        result.put("newFilingActressId", newFiling);
        result.put("filingChanged", filingChanged);
        result.put("committed", true);
        return result;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private void logCuration(Title title, long fromId, String fromName, long toId, String toName,
                             Long oldFiling, Long newFiling, boolean dryRun, String status) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("titleId", title.getId());
        inputs.put("titleCode", title.getCode());
        inputs.put("fromActressId", fromId);
        inputs.put("fromActressName", fromName);
        inputs.put("toActressId", toId);
        inputs.put("toActressName", toName);
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
