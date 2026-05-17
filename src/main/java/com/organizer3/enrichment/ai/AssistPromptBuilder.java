package com.organizer3.enrichment.ai;

import java.util.List;

/**
 * Builds the system and user prompt for the AI picker assist.
 *
 * <p>Ported verbatim (system prompt + user-prompt structure) from the
 * {@code _sandbox/ollama-picker-poc} {@code PickerPromptBuilder}. The kanji-bridge rule
 * in {@link #SYSTEM} is load-bearing — it instructs the LLM to romaji-bridge
 * filed-under actress names against kanji cast lists.
 *
 * <p>Output format requested: strict JSON
 * {@code {"pick": <integer 1..N or null>, "confidence": "high"|"medium"|"low", "reason": "<one sentence>"}}.
 *
 * <p>JSON output is enforced via Ollama's {@code format:"json"} mode AND a prompt
 * instruction, so the happy path produces clean JSON. Callers should still defend
 * against malformed output via a regex fallback.
 */
public class AssistPromptBuilder {

    public static final String SYSTEM = """
            You are a JAV (Japanese Adult Video) catalog matcher.
            Your task: given a media folder on disk, pick the single correct candidate from a numbered list of javdb search results.

            Rules:
            - Match on: product code label, actress names/slugs (linked_slugs), release date, maker, and cast.
            - Linked actress slugs and cast-name matches are the strongest signal — if exactly one candidate's cast includes the filed-under actress, that candidate is almost certainly correct.
            - The product code may not appear inside Japanese titles. Do not require the code to be quoted in the title text.
            - If exactly one candidate clearly matches on cast or code, pick it confidently.
            - Only abstain when truly multiple candidates are plausible OR when none of the candidates match the linked actress at all.
            - Romaji↔kanji bridging: the filed-under actress is given in romaji (e.g. "Yu Tano", "Mika Azuma"), but candidate cast lists are usually in kanji/hiragana/katakana (e.g. "田野憂", "東実果"). Japanese names romanize as either GIVEN+SURNAME or SURNAME+GIVEN, so "Yu Tano" could be the kanji name "田野憂" (Tano Yu) — same person, different script. Use your Japanese name knowledge to identify when a kanji cast entry plausibly transliterates to the romaji actress. Treat such a match as a positive cast-match signal.

            Return the candidate's NUMBER (1, 2, 3, …) — not its slug.
            Abstain by setting "pick" to null.

            You MUST reply with ONLY valid JSON, no explanation outside the JSON, no markdown fences.
            Schema: {"pick": <integer 1..N or null>, "confidence": "high"|"medium"|"low", "reason": "<one short sentence>"}
            """;

    /**
     * Input bundle for the user-prompt builder. Contains everything the LLM needs to
     * pick among javdb candidates for an ambiguous {@code enrichment_review_queue} row.
     *
     * <p>Wave 1 Track C scope: defines the shape only. Wave 3 Track F will build these
     * from a join of {@code enrichment_review_queue.detail}, {@code titles},
     * {@code title_locations}, and {@code title_actresses}.
     */
    public record Input(
            String code,
            String label,
            String folderPath,           // full path; only the last segment is shown
            List<String> actressNames,   // linked actress canonical names (romaji)
            List<String> linkedSlugs,    // detail.linked_slugs[] from snapshot
            List<Candidate> candidates   // detail.candidates[] in original order
    ) {
        public record Candidate(
                String slug,
                String titleOriginal,
                String releaseDate,
                String maker,
                List<String> castNames,
                Integer durationMinutes,
                Double ratingAvg,
                Integer ratingCount
        ) {}
    }

    public static String buildUserPrompt(Input row) {
        StringBuilder sb = new StringBuilder();

        // HINT BLOCK
        sb.append("=== FOLDER HINT ===\n");
        sb.append("Product code: ").append(row.code()).append("\n");
        if (row.label() != null) {
            sb.append("Label: ").append(row.label()).append("\n");
        }
        if (row.folderPath() != null) {
            // Show just the last segment (folder name) to avoid leaking volume paths
            String[] parts = row.folderPath().split("/");
            String folder = parts[parts.length - 1];
            sb.append("Folder name: ").append(folder).append("\n");
        }
        if (row.actressNames() != null && !row.actressNames().isEmpty()) {
            sb.append("Filed under actress(es): ").append(String.join(", ", row.actressNames())).append("\n");
        }
        if (row.linkedSlugs() != null && !row.linkedSlugs().isEmpty()) {
            sb.append("Linked javdb actress slug(s): ").append(String.join(", ", row.linkedSlugs())).append("\n");
        }

        // CANDIDATES BLOCK
        sb.append("\n=== CANDIDATES ===\n");
        List<Input.Candidate> candidates = row.candidates();
        for (int i = 0; i < candidates.size(); i++) {
            Input.Candidate c = candidates.get(i);
            sb.append(i + 1).append(". slug=").append(c.slug()).append("\n");
            if (c.titleOriginal() != null) {
                sb.append("   title: ").append(c.titleOriginal()).append("\n");
            }
            if (c.releaseDate() != null) {
                sb.append("   release_date: ").append(c.releaseDate()).append("\n");
            }
            if (c.maker() != null) {
                sb.append("   maker: ").append(c.maker()).append("\n");
            }
            if (c.castNames() != null && !c.castNames().isEmpty()) {
                sb.append("   cast: ").append(String.join(", ", c.castNames())).append("\n");
            } else {
                sb.append("   cast: (none listed)\n");
            }
            if (c.durationMinutes() != null) {
                sb.append("   duration: ").append(c.durationMinutes()).append(" min\n");
            }
            if (c.ratingAvg() != null) {
                sb.append("   rating: ").append(String.format("%.2f", c.ratingAvg()));
                if (c.ratingCount() != null) sb.append(" (").append(c.ratingCount()).append(" reviews)");
                sb.append("\n");
            }
        }

        sb.append("\nPick the correct candidate or abstain. Reply ONLY with JSON.\n");
        return sb.toString();
    }
}
