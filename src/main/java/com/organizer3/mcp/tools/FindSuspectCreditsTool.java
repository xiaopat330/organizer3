package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For each multi-actress title, flag credited actresses who never co-occur with the rest
 * of the cast on any other title — a strong signal of a typo or mis-credit (e.g. a
 * misspelled name in a compilation).
 *
 * <p>Loads the full {@code title_actresses} junction once, builds in-memory title→cast and
 * actress→titles indexes, then for each cast member of each multi-actress title checks
 * whether she shares at least one other title with any other cast member of the same title.
 * If not, she is flagged. Deliberately strict — low-recall, high-precision.
 */
public class FindSuspectCreditsTool implements Tool {

    private static final int DEFAULT_MIN_CAST = 3;
    private static final int DEFAULT_LIMIT    = 50;
    private static final int MAX_LIMIT        = 500;

    private final Jdbi jdbi;

    public FindSuspectCreditsTool(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override public String name()        { return "find_suspect_credits"; }
    @Override public String description() {
        return "Find multi-actress titles where one credited actress never co-occurs with any other cast member "
             + "on any other title. Strong signal of a typo or mis-credit (e.g. a misspelled name in a compilation).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("min_cast_size", "integer", "Only inspect titles with at least this many credited actresses. Default 3.", DEFAULT_MIN_CAST)
                .prop("limit",         "integer", "Maximum number of suspect credits to return. Default 50, max 500.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int minCast = Math.max(2, Schemas.optInt(args, "min_cast_size", DEFAULT_MIN_CAST));
        int limit   = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        return jdbi.withHandle(h -> {
            Map<Long, Set<Long>> titleCast  = new HashMap<>();
            Map<Long, Set<Long>> actressTitles = new HashMap<>();
            h.createQuery("SELECT title_id, actress_id FROM title_actresses")
                    .map((rs, ctx) -> new long[] { rs.getLong(1), rs.getLong(2) })
                    .forEach(pair -> {
                        long tid = pair[0], aid = pair[1];
                        titleCast.computeIfAbsent(tid, k -> new HashSet<>()).add(aid);
                        actressTitles.computeIfAbsent(aid, k -> new HashSet<>()).add(tid);
                    });

            Map<Long, String> titleCode = new HashMap<>();
            h.createQuery("SELECT id, code FROM titles")
                    .map((rs, ctx) -> Map.entry(rs.getLong(1), rs.getString(2)))
                    .forEach(e -> titleCode.put(e.getKey(), e.getValue()));

            Map<Long, String> actressName = new HashMap<>();
            Map<Long, Integer> actressTitleCount = new HashMap<>();
            h.createQuery("SELECT id, canonical_name FROM actresses")
                    .map((rs, ctx) -> Map.entry(rs.getLong(1), rs.getString(2)))
                    .forEach(e -> actressName.put(e.getKey(), e.getValue()));
            for (Map.Entry<Long, Set<Long>> e : actressTitles.entrySet()) {
                actressTitleCount.put(e.getKey(), e.getValue().size());
            }

            List<Suspect> suspects = new ArrayList<>();
            for (Map.Entry<Long, Set<Long>> e : titleCast.entrySet()) {
                long titleId = e.getKey();
                Set<Long> cast = e.getValue();
                if (cast.size() < minCast) continue;
                for (long ai : cast) {
                    Set<Long> otherCast = new HashSet<>(cast);
                    otherCast.remove(ai);
                    if (neverCoOccursElsewhere(ai, titleId, otherCast, titleCast, actressTitles)) {
                        List<Member> otherMembers = new ArrayList<>();
                        List<Long> sortedOthers = new ArrayList<>(otherCast);
                        Collections.sort(sortedOthers);
                        for (long oid : sortedOthers) {
                            otherMembers.add(new Member(oid, actressName.get(oid)));
                        }
                        suspects.add(new Suspect(
                                titleId,
                                titleCode.get(titleId),
                                new Member(ai, actressName.get(ai)),
                                actressTitleCount.getOrDefault(ai, 0),
                                otherMembers));
                        if (suspects.size() >= limit) break;
                    }
                }
                if (suspects.size() >= limit) break;
            }
            return new Result(suspects.size(), suspects);
        });
    }

    private static boolean neverCoOccursElsewhere(long actressId, long excludeTitleId,
                                                  Set<Long> otherCast,
                                                  Map<Long, Set<Long>> titleCast,
                                                  Map<Long, Set<Long>> actressTitles) {
        Set<Long> titles = actressTitles.getOrDefault(actressId, Set.of());
        for (long tid : titles) {
            if (tid == excludeTitleId) continue;
            Set<Long> cast = titleCast.get(tid);
            if (cast == null) continue;
            for (long member : cast) {
                if (otherCast.contains(member)) return false;
            }
        }
        return true;
    }

    public record Member(long actressId, String name) {}
    public record Suspect(
            long titleId,
            String titleCode,
            Member suspect,
            int suspectTotalTitleCount,
            List<Member> otherCast
    ) {}
    public record Result(int count, List<Suspect> suspects) {}
}
