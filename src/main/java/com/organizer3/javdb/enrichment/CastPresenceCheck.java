package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared predicate for checking whether an actress appears in a title's
 * {@code title_javdb_enrichment.cast_json} blob.
 *
 * <p>Two concerns are bundled here so that a promotion guard and a YAML-loader
 * guard can both rely on exactly the same logic without diverging:
 * <ol>
 *   <li>{@link #check(long, String)} — actress containment in the cast array.
 *       Returns a three-valued {@link Result} ({@code PRESENT}, {@code ABSENT},
 *       {@code UNCHECKABLE}).</li>
 *   <li>{@link #guardEnforced(long, String)} — the comp/size gate that determines
 *       whether the cast-presence check should be applied to a given title at all.
 *       {@code true} when {@code nfem ≤ 3} AND the title is not tagged
 *       {@code curated_alias='compilation'}.</li>
 * </ol>
 *
 * <p><strong>Normalization:</strong> names are compared after Unicode NFKC
 * normalization followed by whitespace removal.  NFKC first folds ideographic
 * spaces (U+3000) to ASCII space so the subsequent {@code replaceAll("\\s+","")}
 * removes them uniformly.  This is deliberately more aggressive than
 * {@link CastMatcher#normalize}, which only {@code trim()}s; here we want to
 * match {@code "椎名 そら"} against a cast blob that stores {@code "椎名そら"}.
 */
@RequiredArgsConstructor
public class CastPresenceCheck {

    /** Three-valued result for {@link #check}. */
    public enum Result { PRESENT, ABSENT, UNCHECKABLE }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Jdbi jdbi;

    // ─── public API ──────────────────────────────────────────────────────────

    /**
     * Returns whether {@code actressId} appears in {@code castJson}.
     *
     * <ul>
     *   <li>{@code PRESENT} – at least one of the actress's names (stage_name, any
     *       actress_alias, or any alternate_names_json entry) is contained in the
     *       NFKC-stripped cast blob.</li>
     *   <li>{@code UNCHECKABLE} – the actress has no stage_name AND no
     *       Japanese-script-bearing (kanji / hiragana / katakana) alias or alternate
     *       name.  There is nothing to match against the (always-kanji/kana) javdb
     *       cast list.</li>
     *   <li>{@code ABSENT} – checkable names exist but none appear in the cast.</li>
     * </ul>
     */
    public Result check(long actressId, String castJson) {
        Names names = fetchNames(actressId);
        if (names.stageName == null && !hasJapaneseScript(names.aliases) && !hasJapaneseScript(names.alternates)) {
            return Result.UNCHECKABLE;
        }

        String normCast = stripAndNormalize(castJson == null ? "" : castJson);

        // Build the full candidate set — romaji names are included; they simply
        // won't match a kanji-only cast, giving ABSENT rather than UNCHECKABLE.
        List<String> candidates = new ArrayList<>();
        if (names.stageName != null) candidates.add(names.stageName);
        candidates.addAll(names.aliases);
        candidates.addAll(names.alternates);

        for (String name : candidates) {
            String norm = stripAndNormalize(name);
            if (norm != null && !norm.isEmpty() && normCast.contains(norm)) {
                return Result.PRESENT;
            }
        }
        return Result.ABSENT;
    }

    /**
     * Returns {@code true} when the cast-presence guard should be enforced for
     * this title — i.e. when the title is small enough and not a compilation.
     *
     * <p>Specifically: {@code nfem ≤ 3} (where nfem = count of cast entries
     * with {@code gender="F"}) AND the title is NOT tagged with
     * {@code enrichment_tag_definitions.curated_alias = 'compilation'}.
     *
     * <p>The compilation tag is resolved at runtime by alias so that no tag id
     * is hardcoded here.
     *
     * @param titleId  the title whose enrichment tags to check
     * @param castJson the cast blob (already fetched by caller)
     */
    public boolean guardEnforced(long titleId, String castJson) {
        int nfem = countFemale(castJson);
        if (nfem > 3) return false;
        boolean isComp = isCompilation(titleId);
        return !isComp;
    }

    // ─── internals ───────────────────────────────────────────────────────────

    private record Names(String stageName, List<String> aliases, List<String> alternates) {}

    private Names fetchNames(long actressId) {
        return jdbi.withHandle(h -> {
            String stageName = h.createQuery(
                            "SELECT stage_name FROM actresses WHERE id = :id")
                    .bind("id", actressId)
                    .mapTo(String.class)
                    .findOne()
                    .orElse(null);

            List<String> aliases = h.createQuery(
                            "SELECT alias_name FROM actress_aliases WHERE actress_id = :id")
                    .bind("id", actressId)
                    .mapTo(String.class)
                    .list();

            // Fetch the raw alternate_names_json TEXT column and extract name fields.
            String altJson = h.createQuery(
                            "SELECT alternate_names_json FROM actresses WHERE id = :id")
                    .bind("id", actressId)
                    .mapTo(String.class)
                    .findOne()
                    .orElse(null);

            List<String> alternates = parseAlternateNames(altJson);
            return new Names(stageName, aliases, alternates);
        });
    }

    /**
     * Parses the {@code alternate_names_json} column.  The canonical shape is an
     * array of objects {@code [{"name":"..."},...]}; handles null and bare-string
     * elements defensively.
     */
    private static List<String> parseAlternateNames(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode node : root) {
                if (node.isObject()) {
                    JsonNode nameNode = node.get("name");
                    if (nameNode != null && !nameNode.isNull()) {
                        String v = nameNode.asText();
                        if (!v.isBlank()) result.add(v);
                    }
                } else if (node.isTextual()) {
                    String v = node.asText();
                    if (!v.isBlank()) result.add(v);
                }
            }
            return result;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * NFKC-normalize then strip all whitespace.  Returns {@code null} for null input.
     */
    static String stripAndNormalize(String value) {
        if (value == null) return null;
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "");
    }

    /**
     * Returns {@code true} if any name in the list contains at least one
     * Han/Hiragana/Katakana code-point (after NFKC normalization).
     */
    private static boolean hasJapaneseScript(List<String> names) {
        for (String name : names) {
            if (name != null && containsJapaneseScript(name)) return true;
        }
        return false;
    }

    private static boolean containsJapaneseScript(String s) {
        if (s == null) return false;
        String norm = Normalizer.normalize(s, Normalizer.Form.NFKC);
        return norm.codePoints().anyMatch(CastPresenceCheck::isJapaneseCp);
    }

    private static boolean isJapaneseCp(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }

    private static int countFemale(String castJson) {
        if (castJson == null || castJson.isBlank()) return 0;
        try {
            JsonNode root = MAPPER.readTree(castJson);
            if (!root.isArray()) return 0;
            int count = 0;
            for (JsonNode entry : root) {
                JsonNode g = entry.get("gender");
                if (g != null && "F".equals(g.asText())) count++;
            }
            return count;
        } catch (JsonProcessingException e) {
            return 0;
        }
    }

    private boolean isCompilation(long titleId) {
        return jdbi.withHandle(h -> {
            Integer count = h.createQuery("""
                            SELECT COUNT(*)
                            FROM title_enrichment_tags tet
                            JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
                            WHERE tet.title_id = :titleId
                              AND etd.curated_alias = 'compilation'
                            """)
                    .bind("titleId", titleId)
                    .mapTo(Integer.class)
                    .one();
            return count != null && count > 0;
        });
    }
}
