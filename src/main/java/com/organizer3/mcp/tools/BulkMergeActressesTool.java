package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.curation.CurationLog;
import com.organizer3.curation.CurationLogRecord;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.repository.ActressRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Batch wrapper over {@link MergeActressesTool#merge} that processes a list of merge pairs in
 * one call. Each pair may specify its own {@code dropAliases} list. Each merge runs in its own
 * transaction; a failure in one pair accumulates to the result without aborting the rest.
 *
 * <p>A single batch-level curation log entry is emitted after all pairs complete.
 *
 * <p>Input shape:
 * <pre>
 * {
 *   "dryRun": true,          // top-level default, applied to every pair
 *   "merges": [
 *     { "into": 123, "from": 456, "dropAliases": ["Typo Name"] },
 *     { "into": 789, "from": 101 }
 *   ]
 * }
 * </pre>
 *
 * <p>Output shape:
 * <pre>
 * {
 *   "dryRun": true,
 *   "totalMerges": 2,
 *   "successful": 1,
 *   "failed": 1,
 *   "results": [
 *     { "index": 0, "into": 123, "from": 456, "ok": true, "result": { ... } },
 *     { "index": 1, "into": 789, "from": 101, "ok": false, "error": "No actress with id 101" }
 *   ]
 * }
 * </pre>
 */
@Slf4j
public class BulkMergeActressesTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();

    private final Jdbi jdbi;
    private final ActressRepository actressRepo;
    private final CurationLog curationLog;

    public BulkMergeActressesTool(Jdbi jdbi, ActressRepository actressRepo, CurationLog curationLog) {
        this.jdbi = jdbi;
        this.actressRepo = actressRepo;
        this.curationLog = curationLog;
    }

    @Override public String name() { return "bulk_merge_actresses"; }

    @Override
    public String description() {
        return "Batch wrapper over merge_actresses. Accepts a list of {into, from, dropAliases?} "
             + "pairs and processes each sequentially. A failure in one pair is accumulated and "
             + "reported without aborting remaining merges. Defaults to dryRun:true.";
    }

    @Override
    public JsonNode inputSchema() {
        // Build the item schema for each merge pair manually (array-of-objects)
        ObjectNode itemSchema = M.createObjectNode();
        itemSchema.put("type", "object");
        ObjectNode itemProps = itemSchema.putObject("properties");
        itemProps.putObject("into").put("type", "integer").put("description",
                "Actress id to keep. All data from 'from' is folded into this record.");
        itemProps.putObject("from").put("type", "integer").put("description",
                "Actress id to fold in. This record is deleted after merge.");
        ObjectNode dropAliasesItem = itemProps.putObject("dropAliases");
        dropAliasesItem.put("type", "array");
        dropAliasesItem.putObject("items").put("type", "string");
        dropAliasesItem.put("description",
                "Aliases to remove from the canonical actress after merge (case-insensitive). "
              + "Empty/omitted = no aliases dropped beyond standard merge-fold behavior.");
        itemSchema.putArray("required").add("into").add("from");

        // Build the top-level schema
        ObjectNode schema = Schemas.object()
                .prop("dryRun", "boolean",
                        "If true (default), return the plan for all pairs without committing.", true)
                .require()
                .build();

        ObjectNode mergesProp = schema.with("properties").putObject("merges");
        mergesProp.put("type", "array");
        mergesProp.set("items", itemSchema);
        mergesProp.put("description", "List of merge operations to execute.");

        // Add merges to required
        schema.withArray("required").add("merges");

        return schema;
    }

    @Override
    public Object call(JsonNode args) {
        JsonNode mergesNode = args.get("merges");
        if (mergesNode == null || !mergesNode.isArray()) {
            throw new IllegalArgumentException("Missing required argument: merges (must be an array)");
        }
        boolean dryRun = Schemas.optBoolean(args, "dryRun", true);

        int total = mergesNode.size();
        int successful = 0;
        int failed = 0;
        List<Object> results = new ArrayList<>(total);

        log.info("BulkMergeActresses: start — totalPairs={} dryRun={}", total, dryRun);

        for (int i = 0; i < total; i++) {
            JsonNode pair = mergesNode.get(i);
            long intoId;
            long fromId;
            List<String> dropAliases;

            // Parse pair
            try {
                intoId = requireLongFromPair(pair, "into", i);
                fromId = requireLongFromPair(pair, "from", i);
                dropAliases = parseStringArray(pair, "dropAliases");
            } catch (IllegalArgumentException e) {
                log.warn("BulkMergeActresses: pair[{}] parse error — {}", i, e.getMessage());
                failed++;
                results.add(errorEntry(i, 0L, 0L, e.getMessage()));
                continue;
            }

            try {
                MergeActressesTool.Result r = MergeActressesTool.merge(
                        jdbi, actressRepo, intoId, fromId, dryRun, dropAliases);
                log.info("BulkMergeActresses: pair[{}] ok — into={} from={} dryRun={}",
                        i, intoId, fromId, dryRun);
                successful++;
                results.add(successEntry(i, intoId, fromId, r));
            } catch (Exception e) {
                log.warn("BulkMergeActresses: pair[{}] failed — into={} from={} error={}",
                        i, intoId, fromId, e.getMessage());
                failed++;
                results.add(errorEntry(i, intoId, fromId, e.getMessage()));
            }
        }

        log.info("BulkMergeActresses: complete — total={} successful={} failed={} dryRun={}",
                total, successful, failed, dryRun);

        // Emit one batch-level curation log entry
        String status = failed == 0 ? (dryRun ? "dry-run" : "ok") : (successful == 0 ? "failed" : "partial");
        CurationLogRecord rec = new CurationLogRecord(
                Instant.now(), name(), "mcp", "mcp-" + Thread.currentThread().getName(),
                Map.of("totalMerges", total, "dryRun", dryRun),
                null, null,
                Map.of("successful", successful, "failed", failed),
                status, List.of());
        curationLog.append("unknown", rec);

        return Map.of(
                "dryRun", dryRun,
                "totalMerges", total,
                "successful", successful,
                "failed", failed,
                "results", results);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static long requireLongFromPair(JsonNode pair, String field, int index) {
        if (pair == null || !pair.isObject()) {
            throw new IllegalArgumentException("Merge entry at index " + index + " is not an object");
        }
        JsonNode v = pair.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException(
                    "Merge entry at index " + index + " is missing required field: " + field);
        }
        return v.asLong();
    }

    private static List<String> parseStringArray(JsonNode node, String field) {
        if (node == null) return List.of();
        JsonNode arr = node.get(field);
        if (arr == null || !arr.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : arr) {
            String v = item.asText("").trim();
            if (!v.isBlank()) result.add(v);
        }
        return result;
    }

    private static Map<String, Object> successEntry(int index, long intoId, long fromId,
                                                     MergeActressesTool.Result r) {
        return Map.of(
                "index", index,
                "into", intoId,
                "from", fromId,
                "ok", true,
                "result", r);
    }

    private static Map<String, Object> errorEntry(int index, long intoId, long fromId,
                                                   String errorMessage) {
        return Map.of(
                "index", index,
                "into", intoId,
                "from", fromId,
                "ok", false,
                "error", errorMessage == null ? "unknown error" : errorMessage);
    }
}
