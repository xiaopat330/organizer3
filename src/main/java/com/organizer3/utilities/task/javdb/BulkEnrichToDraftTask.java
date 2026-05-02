package com.organizer3.utilities.task.javdb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.javdb.draft.DraftPopulator;
import com.organizer3.utilities.task.Task;
import com.organizer3.utilities.task.TaskIO;
import com.organizer3.utilities.task.TaskInputs;
import com.organizer3.utilities.task.TaskSpec;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Utilities task that bulk-populates draft rows for a set of canonical title ids.
 *
 * <p>Title ids are passed as a JSON array string via the {@code titleIds} input.
 * The plan phase excludes titles that already have a draft or are already curated
 * (have a {@code title_javdb_enrichment} row). The enrich phase calls
 * {@link DraftPopulator#populate} per title, honours cancellation between titles,
 * and continues on per-title failures so partial results persist.
 *
 * <p>Registered as {@value #ID}. {@link com.organizer3.javdb.enrichment.EnrichmentRunner}
 * pauses while this task is in RUNNING state via a direct in-memory check.
 *
 * <p>See spec/PROPOSAL_DRAFT_MODE.md §10 and §13 (Phase 6).
 */
@Slf4j
public final class BulkEnrichToDraftTask implements Task {

    public static final String ID = "enrichment.bulk_enrich_to_draft";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {};

    private static final TaskSpec SPEC = new TaskSpec(
            ID,
            "Bulk enrich to draft",
            "Fetches javdb metadata for a list of titles and creates draft rows for each. "
                    + "Titles with an existing draft or existing curated enrichment are skipped. "
                    + "Background enrichment runner is paused while this task runs. "
                    + "Per-title progress; resumable (re-run skips already-drafted). Cancellable.",
            List.of(new TaskSpec.InputSpec(
                    "titleIds",
                    "JSON array of canonical title ids to enrich, e.g. [1,2,3]",
                    TaskSpec.InputSpec.InputType.STRING, true))
    );

    private final Jdbi jdbi;
    private final DraftPopulator draftPopulator;

    public BulkEnrichToDraftTask(Jdbi jdbi, DraftPopulator draftPopulator) {
        this.jdbi = jdbi;
        this.draftPopulator = draftPopulator;
    }

    @Override
    public TaskSpec spec() { return SPEC; }

    @Override
    public void run(TaskInputs inputs, TaskIO io) {
        // ── Parse input ──────────────────────────────────────────────────────
        String raw = inputs.getString("titleIds");
        List<Long> requested;
        try {
            requested = JSON.readValue(raw, LONG_LIST);
        } catch (Exception e) {
            io.phaseStart("parse", "Parsing input");
            io.phaseEnd("parse", "failed", "Invalid titleIds JSON: " + e.getMessage());
            return;
        }
        if (requested.isEmpty()) {
            io.phaseStart("plan", "Planning");
            io.phaseEnd("plan", "ok", "No title ids provided — nothing to do");
            return;
        }

        // ── Plan: exclude already-drafted and already-curated ────────────────
        io.phaseStart("plan", "Planning — checking " + requested.size() + " title(s) for eligibility");
        List<Long> eligible   = new ArrayList<>();
        int alreadyDrafted    = 0;
        int alreadyCurated    = 0;

        for (Long titleId : requested) {
            if (hasDraft(titleId)) {
                alreadyDrafted++;
            } else if (hasCuratedEnrichment(titleId)) {
                alreadyCurated++;
            } else {
                eligible.add(titleId);
            }
        }

        String planSummary = eligible.size() + " eligible"
                + (alreadyDrafted > 0 ? " · " + alreadyDrafted + " already have drafts (skipped)" : "")
                + (alreadyCurated > 0 ? " · " + alreadyCurated + " already curated (skipped)" : "");
        io.phaseEnd("plan", "ok", planSummary);

        if (eligible.isEmpty()) {
            return;
        }

        // ── Enrich: populate one title at a time ─────────────────────────────
        io.phaseStart("enrich", "Enriching " + eligible.size() + " title(s)");
        int created  = 0;
        int skipped  = 0;
        int failures = 0;

        for (int i = 0; i < eligible.size(); i++) {
            if (io.isCancellationRequested()) {
                io.phaseLog("enrich",
                        "Cancelled after " + i + " of " + eligible.size() + " title(s)");
                break;
            }
            long titleId = eligible.get(i);
            io.phaseProgress("enrich", i, eligible.size(), "titleId=" + titleId);

            DraftPopulator.PopulateResult result;
            try {
                result = draftPopulator.populate(titleId);
            } catch (RuntimeException e) {
                log.warn("BulkEnrichToDraftTask: populate threw for titleId={}: {}", titleId, e.getMessage(), e);
                io.phaseLog("enrich", "FAIL titleId=" + titleId + " — " + e.getMessage());
                failures++;
                continue;
            }

            switch (result.status()) {
                case CREATED       -> created++;
                case ALREADY_EXISTS -> {
                    // Could have been drafted by another path between plan and enrich phases.
                    skipped++;
                    io.phaseLog("enrich", "SKIP titleId=" + titleId + " — draft appeared concurrently");
                }
                case TITLE_NOT_FOUND -> {
                    failures++;
                    io.phaseLog("enrich", "SKIP titleId=" + titleId + " — title not found");
                }
                case JAVDB_NOT_FOUND -> {
                    failures++;
                    io.phaseLog("enrich", "SKIP titleId=" + titleId + " — no javdb match");
                }
                case JAVDB_ERROR -> {
                    failures++;
                    io.phaseLog("enrich", "FAIL titleId=" + titleId + " — javdb error");
                }
            }
        }

        String enrichSummary = created + " draft(s) created"
                + (skipped   > 0 ? " · " + skipped   + " skipped (concurrent race)" : "")
                + (failures  > 0 ? " · " + failures  + " failure(s)" : "");
        io.phaseEnd("enrich", failures > 0 && created == 0 ? "failed" : "ok", enrichSummary);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean hasDraft(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM draft_titles WHERE title_id = :id")
                        .bind("id", titleId)
                        .mapTo(Integer.class)
                        .one()) > 0;
    }

    private boolean hasCuratedEnrichment(long titleId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) FROM title_javdb_enrichment WHERE title_id = :id")
                        .bind("id", titleId)
                        .mapTo(Integer.class)
                        .one()) > 0;
    }
}
