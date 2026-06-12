package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.repository.AttributionFindingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detection and persistence for actress-level attribution audit findings.
 *
 * <p>Wraps the detection logic from the two attribution-audit MCP tools
 * ({@code find_enrichment_cast_mismatches} and {@code find_suspect_credits}) into
 * a single service that:
 * <ol>
 *   <li>Exposes per-title detail methods used by the thin MCP wrapper tools.</li>
 *   <li>Aggregates per-actress findings and persists them to {@code attribution_findings}.</li>
 * </ol>
 *
 * <p>The persistence methods are called from {@link RevalidationCronScheduler} Phase 3.
 */
@Slf4j
public class AttributionAuditService {

    private static final int SAMPLE_CAP = 10;
    private static final int SUSPECT_CREDIT_MIN_CAST = 3;

    private final Jdbi jdbi;
    private final AttributionFindingsRepository findingsRepo;
    private final ObjectMapper mapper;

    public AttributionAuditService(Jdbi jdbi, AttributionFindingsRepository findingsRepo,
                                   ObjectMapper mapper) {
        this.jdbi = jdbi;
        this.findingsRepo = findingsRepo;
        this.mapper = mapper;
    }

    // ── Per-title detail (used by thin MCP wrappers) ──────────────────────────

    public long countCastMismatches() {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT COUNT(*) " + MISMATCH_FROM + " WHERE " + MISMATCH_WHERE)
                        .mapTo(Long.class).one());
    }

    public List<MismatchDetail> listCastMismatches(int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.id             AS title_id,
                               t.code           AS code,
                               a.id             AS actress_id,
                               a.canonical_name AS actress_name,
                               a.stage_name     AS stage_name,
                               e.javdb_slug     AS javdb_slug,
                               e.title_original AS javdb_title_original
                        """ + MISMATCH_FROM + " WHERE " + MISMATCH_WHERE + """
                        ORDER BY a.canonical_name, t.code
                        LIMIT :limit
                        """)
                        .bind("limit", limit)
                        .map((rs, ctx) -> new MismatchDetail(
                                rs.getLong("title_id"),
                                rs.getString("code"),
                                rs.getLong("actress_id"),
                                rs.getString("actress_name"),
                                rs.getString("stage_name"),
                                rs.getString("javdb_slug"),
                                rs.getString("javdb_title_original")))
                        .list());
    }

    public SuspectResult findSuspectCredits(int minCast, int limit) {
        return jdbi.withHandle(h -> {
            Map<Long, Set<Long>> titleCast = new HashMap<>();
            Map<Long, Set<Long>> actressTitles = new HashMap<>();
            h.createQuery("SELECT title_id, actress_id FROM title_actresses")
                    .map((rs, ctx) -> new long[]{rs.getLong(1), rs.getLong(2)})
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

            List<SuspectDetail> suspects = new ArrayList<>();
            outer:
            for (Map.Entry<Long, Set<Long>> e : titleCast.entrySet()) {
                long titleId = e.getKey();
                Set<Long> cast = e.getValue();
                if (cast.size() < minCast) continue;
                for (long ai : cast) {
                    Set<Long> otherCast = new HashSet<>(cast);
                    otherCast.remove(ai);
                    if (neverCoOccursElsewhere(ai, titleId, otherCast, titleCast, actressTitles)) {
                        List<SuspectMember> otherMembers = new ArrayList<>();
                        List<Long> sortedOthers = new ArrayList<>(otherCast);
                        Collections.sort(sortedOthers);
                        for (long oid : sortedOthers) {
                            otherMembers.add(new SuspectMember(oid, actressName.get(oid)));
                        }
                        suspects.add(new SuspectDetail(
                                titleId, titleCode.get(titleId),
                                new SuspectMember(ai, actressName.get(ai)),
                                actressTitleCount.getOrDefault(ai, 0),
                                otherMembers));
                        if (suspects.size() >= limit) break outer;
                    }
                }
            }
            return new SuspectResult(suspects.size(), suspects);
        });
    }

    // ── Aggregate per-actress (for cron persistence) ─────────────────────────

    /**
     * Refresh per-actress findings for up to {@code batchSize} actresses
     * (0 = no limit). Returns the count of actresses evaluated.
     */
    public int refreshFindings(int batchSize) {
        String now = Instant.now().toString();

        // === cast_mismatch: mismatch title codes keyed by actress_id ===
        Map<Long, List<String>> mismatchesByActress = new HashMap<>();
        jdbi.useHandle(h ->
                h.createQuery("""
                        SELECT a.id AS actress_id, t.code AS code
                        """ + MISMATCH_FROM + " WHERE " + MISMATCH_WHERE + """
                        ORDER BY a.id
                        """)
                        .map((rs, ctx) -> Map.entry(rs.getLong("actress_id"), rs.getString("code")))
                        .forEach(entry ->
                                mismatchesByActress
                                        .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                                        .add(entry.getValue())));

        // Total gated enriched titles per actress (denominator)
        Map<Long, Integer> totalByActress = new HashMap<>();
        jdbi.useHandle(h ->
                h.createQuery("""
                        SELECT ta.actress_id, COUNT(*) as cnt
                        FROM title_actresses ta
                        JOIN title_javdb_enrichment e ON e.title_id = ta.title_id
                        JOIN actresses a ON a.id = ta.actress_id
                        WHERE COALESCE(a.is_sentinel, 0) = 0
                          AND a.stage_name IS NOT NULL
                          AND e.cast_json IS NOT NULL
                        GROUP BY ta.actress_id
                        """)
                        .map((rs, ctx) -> Map.entry(rs.getLong("actress_id"), rs.getInt("cnt")))
                        .forEach(entry -> totalByActress.put(entry.getKey(), entry.getValue())));

        // === suspect_credit: suspect title codes keyed by actress_id ===
        Map<Long, List<String>> suspectTitlesByActress = buildSuspectTitlesByActress(SUSPECT_CREDIT_MIN_CAST);

        // All actresses across both finding types
        Set<Long> allActresses = new HashSet<>();
        allActresses.addAll(mismatchesByActress.keySet());
        allActresses.addAll(totalByActress.keySet());
        allActresses.addAll(suspectTitlesByActress.keySet());

        // Snapshot existing findings before processing
        List<AttributionFindingsRepository.Finding> existingFindings =
                findingsRepo.list(null, Integer.MAX_VALUE);

        // Apply batch limit
        List<Long> actressesToProcess = new ArrayList<>(allActresses);
        if (batchSize > 0 && actressesToProcess.size() > batchSize) {
            actressesToProcess = actressesToProcess.subList(0, batchSize);
        }

        Set<Long> evaluatedActresses = new HashSet<>(actressesToProcess);

        int processed = 0;
        for (long actressId : actressesToProcess) {
            // -- cast_mismatch --
            List<String> mismatches = mismatchesByActress.getOrDefault(actressId, List.of());
            int total = totalByActress.getOrDefault(actressId, 0);
            if (!mismatches.isEmpty() && total > 0) {
                double metric = (double) mismatches.size() / total;
                List<String> sample = mismatches.size() <= SAMPLE_CAP
                        ? mismatches : mismatches.subList(0, SAMPLE_CAP);
                log.warn("attribution-audit: actress {} cast_mismatch metric={} ({}/{})",
                        actressId, String.format("%.2f", metric), mismatches.size(), total);
                findingsRepo.upsert(actressId, "cast_mismatch", metric, toJson(sample), now);
                String[] stagingInfo = getStagingInfo(actressId);
                findingsRepo.reopenSuppressedIfChanged(actressId, "cast_mismatch",
                        stagingInfo[0], stagingInfo[1]);
            }

            // -- suspect_credit --
            List<String> suspectTitles = suspectTitlesByActress.getOrDefault(actressId, List.of());
            if (!suspectTitles.isEmpty()) {
                int actressTotalTitles = jdbi.withHandle(h ->
                        h.createQuery("SELECT COUNT(*) FROM title_actresses WHERE actress_id = :aid")
                                .bind("aid", actressId).mapTo(Integer.class).one());
                double metric = actressTotalTitles > 0
                        ? (double) suspectTitles.size() / actressTotalTitles : 1.0;
                List<String> sample = suspectTitles.size() <= SAMPLE_CAP
                        ? suspectTitles : suspectTitles.subList(0, SAMPLE_CAP);
                findingsRepo.upsert(actressId, "suspect_credit", metric, toJson(sample), now);
                String[] stagingInfo = getStagingInfo(actressId);
                findingsRepo.reopenSuppressedIfChanged(actressId, "suspect_credit",
                        stagingInfo[0], stagingInfo[1]);
            }

            processed++;
        }

        // Mark vanished findings as resolved (only within evaluated actress set)
        for (AttributionFindingsRepository.Finding f : existingFindings) {
            if (!evaluatedActresses.contains(f.actressId())) continue;
            if ("resolved".equals(f.status())) continue;
            boolean stillPresent = switch (f.findingClass()) {
                case "cast_mismatch" ->
                        !mismatchesByActress.getOrDefault(f.actressId(), List.of()).isEmpty();
                case "suspect_credit" ->
                        !suspectTitlesByActress.getOrDefault(f.actressId(), List.of()).isEmpty();
                default -> false;
            };
            if (!stillPresent) {
                findingsRepo.markResolved(f.actressId(), f.findingClass(), now);
            }
        }

        return processed;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<Long, List<String>> buildSuspectTitlesByActress(int minCast) {
        return jdbi.withHandle(h -> {
            Map<Long, Set<Long>> titleCast = new HashMap<>();
            Map<Long, Set<Long>> actressTitles = new HashMap<>();
            h.createQuery("SELECT title_id, actress_id FROM title_actresses")
                    .map((rs, ctx) -> new long[]{rs.getLong(1), rs.getLong(2)})
                    .forEach(pair -> {
                        titleCast.computeIfAbsent(pair[0], k -> new HashSet<>()).add(pair[1]);
                        actressTitles.computeIfAbsent(pair[1], k -> new HashSet<>()).add(pair[0]);
                    });
            Map<Long, String> titleCode = new HashMap<>();
            h.createQuery("SELECT id, code FROM titles")
                    .map((rs, ctx) -> Map.entry(rs.getLong(1), rs.getString(2)))
                    .forEach(e -> titleCode.put(e.getKey(), e.getValue()));

            Map<Long, List<String>> result = new HashMap<>();
            for (Map.Entry<Long, Set<Long>> e : titleCast.entrySet()) {
                long titleId = e.getKey();
                Set<Long> cast = e.getValue();
                if (cast.size() < minCast) continue;
                for (long ai : cast) {
                    Set<Long> otherCast = new HashSet<>(cast);
                    otherCast.remove(ai);
                    if (neverCoOccursElsewhere(ai, titleId, otherCast, titleCast, actressTitles)) {
                        result.computeIfAbsent(ai, k -> new ArrayList<>()).add(
                                titleCode.getOrDefault(titleId, String.valueOf(titleId)));
                    }
                }
            }
            return result;
        });
    }

    private String[] getStagingInfo(long actressId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT a.stage_name, jas.javdb_slug
                        FROM actresses a
                        LEFT JOIN javdb_actress_staging jas ON jas.actress_id = a.id
                        WHERE a.id = :aid
                        """)
                        .bind("aid", actressId)
                        .map((rs, ctx) -> new String[]{rs.getString("stage_name"), rs.getString("javdb_slug")})
                        .findFirst()
                        .orElse(new String[]{null, null}));
    }

    private String toJson(List<String> list) {
        try {
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ── Shared SQL from FindEnrichmentCastMismatchesTool ──────────────────────

    static final String MISMATCH_FROM = """
            FROM titles t
            JOIN title_actresses ta ON ta.title_id = t.id
            JOIN actresses a        ON a.id = ta.actress_id
            JOIN title_javdb_enrichment e ON e.title_id = t.id
            """;

    static final String MISMATCH_WHERE = """
            COALESCE(a.is_sentinel, 0) = 0
              AND a.stage_name IS NOT NULL
              AND e.cast_json IS NOT NULL
              AND REPLACE(e.cast_json, ' ', '') NOT LIKE '%' || REPLACE(a.stage_name, ' ', '') || '%'
              AND (
                a.alternate_names_json IS NULL
                OR NOT EXISTS (
                  SELECT 1 FROM json_each(a.alternate_names_json) alt
                  WHERE json_extract(alt.value, '$.name') IS NOT NULL
                    AND REPLACE(e.cast_json, ' ', '')
                        LIKE '%' || REPLACE(json_extract(alt.value, '$.name'), ' ', '') || '%'
                )
              )
              AND NOT EXISTS (
                SELECT 1
                FROM json_each(e.cast_json) je
                JOIN actress_aliases aa ON aa.actress_id = a.id
                WHERE json_extract(je.value, '$.name') = aa.alias_name
              )
            """;

    // ── Shared graph helper ───────────────────────────────────────────────────

    static boolean neverCoOccursElsewhere(long actressId, long excludeTitleId,
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

    // ── Detail records ────────────────────────────────────────────────────────

    public record MismatchDetail(
            long titleId, String code, long actressId, String actressName,
            String stageName, String javdbSlug, String javdbTitleOriginal) {}

    public record SuspectMember(long actressId, String name) {}

    public record SuspectDetail(
            long titleId, String titleCode, SuspectMember suspect,
            int suspectTotalTitleCount, List<SuspectMember> otherCast) {}

    public record SuspectResult(int count, List<SuspectDetail> suspects) {}
}
