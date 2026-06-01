package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * UI-serve filter: strips non-female cast entries from cast JSON strings before
 * returning data to the browser.
 *
 * <p>Storage and snapshot builds are NEVER filtered — only the serialization step
 * that hands data to the web layer passes through here.
 *
 * <p>Female entries are those with {@code "gender":"F"}.  Entries with missing,
 * null, {@code "M"}, or {@code "U"} gender are dropped, consistent with the
 * existing {@code ActressBrowseService} SQL predicate
 * {@code json_extract(...'$.gender') = 'F'}.
 */
public final class CastJsonFilter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CastJsonFilter() {}

    /**
     * Returns a JSON string containing only the female-gender entries from the
     * supplied cast array JSON.
     *
     * <p>Input format: {@code [{"slug":"…","name":"…","gender":"F"}, …]}.
     * Output format: same structure, males/unknowns removed.
     *
     * @param castJson raw cast_json string (may be null or blank)
     * @return filtered JSON string, or the original value if it is null/blank or
     *         cannot be parsed
     */
    public static String femaleOnlyCast(String castJson) {
        if (castJson == null || castJson.isBlank()) return castJson;
        try {
            JsonNode root = JSON.readTree(castJson);
            if (!root.isArray()) return castJson;
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode entry : root) {
                String gender = entry.path("gender").asText(null);
                if ("F".equals(gender)) {
                    out.add(entry);
                }
            }
            return JSON.writeValueAsString(out);
        } catch (Exception e) {
            // Malformed JSON — pass through unmodified; caller renders what it can.
            return castJson;
        }
    }

    /**
     * Returns a snapshot detail JSON string with each candidate's {@code cast[]}
     * filtered to females only, leaving all other snapshot fields untouched.
     *
     * <p>Input format: the disambiguation snapshot built by
     * {@link DisambiguationSnapshotter}:
     * {@code {"code":"…","candidates":[{"slug":"…","cast":[…]},…],"linked_slugs":[…]}}.
     *
     * @param detailJson the raw {@code enrichment_review_queue.detail} string
     * @return filtered JSON string, or the original value if null/blank or
     *         the string cannot be parsed / has no {@code candidates} array
     */
    public static String femaleOnlyDetailCandidateCast(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) return detailJson;
        try {
            JsonNode root = JSON.readTree(detailJson);
            if (!root.isObject()) return detailJson;
            JsonNode candidatesNode = root.path("candidates");
            if (!candidatesNode.isArray()) return detailJson;

            ObjectNode out = (ObjectNode) JSON.readTree(detailJson); // deep copy via re-parse
            ArrayNode filteredCandidates = JSON.createArrayNode();
            for (JsonNode candidate : candidatesNode) {
                JsonNode castNode = candidate.path("cast");
                if (castNode.isArray()) {
                    ArrayNode filteredCast = JSON.createArrayNode();
                    for (JsonNode ce : castNode) {
                        String gender = ce.path("gender").asText(null);
                        if ("F".equals(gender)) {
                            filteredCast.add(ce);
                        }
                    }
                    ObjectNode candidateCopy = (ObjectNode) JSON.readTree(candidate.toString());
                    candidateCopy.set("cast", filteredCast);
                    filteredCandidates.add(candidateCopy);
                } else {
                    filteredCandidates.add(candidate);
                }
            }
            out.set("candidates", filteredCandidates);
            return JSON.writeValueAsString(out);
        } catch (Exception e) {
            return detailJson;
        }
    }
}
