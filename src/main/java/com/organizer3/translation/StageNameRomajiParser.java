package com.organizer3.translation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses the structured JSON output of the {@code label_name} translation strategy and
 * composes a Western-order romaji string.
 *
 * <p>The {@code label_name} prompt instructs the model to output compact JSON:
 * <pre>{"given":"<given name>","surname":"<surname>"}</pre>
 * For mononyms the {@code surname} key is empty. When the model includes the surname it is
 * romanized surname-first (Japanese order), so we compose Western order: given + " " + surname.
 *
 * <p>Robustness strategy:
 * <ol>
 *   <li>Strip any leading/trailing prose and ```json fences.</li>
 *   <li>Parse JSON with lenient Jackson settings (allow single quotes, unquoted fields, trailing
 *       commas).</li>
 *   <li>On any parse failure, fall back: treat input as a plain romanization (also surname-first)
 *       and flip 2-token strings to given-first; leave single-token strings untouched.</li>
 * </ol>
 *
 * <p>This class is a pure-function utility — stateless, no external dependencies.
 */
@Slf4j
public final class StageNameRomajiParser {

    private static final ObjectMapper LENIENT = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);

    private StageNameRomajiParser() {}

    /**
     * Parse the raw model output from the {@code label_name} strategy and compose a
     * Western-order given-first romaji string.
     *
     * @param rawModelOutput the raw string returned by Ollama (may be null/blank)
     * @return composed Western-order romaji, or {@code null} if input is null/blank
     */
    public static String parseAndCompose(String rawModelOutput) {
        if (rawModelOutput == null || rawModelOutput.isBlank()) {
            return null;
        }

        String json = extractJson(rawModelOutput);
        if (json != null) {
            try {
                JsonNode node = LENIENT.readTree(json);
                String given   = textOrEmpty(node, "given");
                String surname = textOrEmpty(node, "surname");

                if (given.isBlank() && surname.isBlank()) {
                    // Nothing useful — fall through to plain-text fallback
                    log.debug("StageNameRomajiParser: JSON parsed but both fields empty in '{}'", rawModelOutput);
                } else {
                    String composedGiven   = titlecase(given.trim());
                    String composedSurname = titlecase(surname.trim());
                    String result = composedSurname.isBlank()
                            ? composedGiven
                            : composedGiven + " " + composedSurname;
                    log.debug("StageNameRomajiParser: JSON parsed given='{}' surname='{}' → '{}'",
                            given, surname, result);
                    return result.isBlank() ? null : result;
                }
            } catch (Exception e) {
                log.debug("StageNameRomajiParser: JSON parse failed for '{}': {}", rawModelOutput, e.getMessage());
                // fall through to plain-text fallback
            }
        }

        // Plain-text fallback: treat as surname-first romanization
        return fallback(rawModelOutput.trim());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Extract the first balanced JSON object from the input, stripping prose, fences, etc.
     * Returns null if no '{' is found.
     */
    private static String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start < 0 || end < start) return null;
        return raw.substring(start, end + 1);
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return "";
        return n.asText("").trim();
    }

    /**
     * Titlecase: if the token is all-lowercase, uppercase the first character only.
     * All-caps mononyms (e.g. "JULIA") are left unchanged. Mixed-case tokens are left unchanged.
     */
    static String titlecase(String token) {
        if (token == null || token.isEmpty()) return token == null ? "" : token;
        // All lowercase → capitalize first letter only
        if (token.equals(token.toLowerCase())) {
            return Character.toUpperCase(token.charAt(0)) + token.substring(1);
        }
        // All uppercase AND length > 1 (mononym like JULIA) → preserve as-is
        // Mixed case (Nanami, already correct) → preserve as-is
        return token;
    }

    /**
     * Plain-text surname-first flip fallback.
     * Exactly 2 whitespace tokens → return token[1] + " " + token[0].
     * Any other count → return trimmed as-is.
     */
    static String fallback(String plain) {
        if (plain == null || plain.isBlank()) return plain;
        String[] tokens = plain.split("\\s+");
        if (tokens.length == 2) {
            return tokens[1] + " " + tokens[0];
        }
        return plain.trim();
    }
}
