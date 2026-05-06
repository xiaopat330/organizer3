package com.organizer3.translation;

import com.organizer3.repository.ActressRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Maps LLM-generated romaji guesses to existing {@link com.organizer3.model.Actress} records
 * via progressively weaker matching rules.
 *
 * <p>Two surfaces:
 * <ul>
 *   <li>{@link #match} — strict, returns at most one result; skips on weak ambiguity.
 *   <li>{@link #findCandidates} — permissive, returns all hits with rule labels.
 * </ul>
 */
@RequiredArgsConstructor
public class ActressFuzzyMatcher {

    private static final Logger log = LoggerFactory.getLogger(ActressFuzzyMatcher.class);

    public enum Rule { EXACT, REVERSAL, PUNCT_NORM, LAST_NAME_ONLY }

    public record MatchResult(long actressId, Rule rule) {}

    private static final int LAST_TOKEN_QUERY_LIMIT = 10;

    private final ActressRepository actressRepo;

    /**
     * Strict surface for auto-link. Returns the first rule that fires, in order.
     * Rule 4 (LAST_NAME_ONLY) returns empty when >1 actress matches — under-link is safer
     * than mis-link.
     */
    public Optional<MatchResult> match(String romaji) {
        if (romaji == null || romaji.isBlank()) return Optional.empty();

        // Rule 1: exact
        var exact = actressRepo.resolveByName(romaji);
        if (exact.isPresent()) return Optional.of(new MatchResult(exact.get().getId(), Rule.EXACT));

        // Rule 2: reversal (skip for single-token input — reversing is a no-op)
        if (!isSingleToken(romaji)) {
            String reversed = joinTokens(reverseTokens(splitTokens(romaji)));
            var rev = actressRepo.resolveByName(reversed);
            if (rev.isPresent()) return Optional.of(new MatchResult(rev.get().getId(), Rule.REVERSAL));
        }

        // Rule 3: punctuation normalization
        String normalized = stripPunctuation(romaji);
        if (!normalized.equals(romaji)) {
            var punct = actressRepo.resolveByName(normalized);
            if (punct.isPresent()) return Optional.of(new MatchResult(punct.get().getId(), Rule.PUNCT_NORM));
        }

        // Rule 4: last-name-only (only for single-token input)
        if (isSingleToken(romaji)) {
            List<com.organizer3.model.Actress> candidates = actressRepo.findByLastTokenCi(romaji, LAST_TOKEN_QUERY_LIMIT);
            if (candidates.size() == 1) {
                return Optional.of(new MatchResult(candidates.get(0).getId(), Rule.LAST_NAME_ONLY));
            }
        }

        return Optional.empty();
    }

    /**
     * Permissive surface for the near-miss picker. Returns ALL hits with their rule labels.
     * An actress that already matched via an earlier rule does not also appear under a later rule
     * (first-rule-wins per actress). Multiple actresses CAN appear under the same rule.
     */
    public List<MatchResult> findCandidates(String romaji) {
        if (romaji == null || romaji.isBlank()) return List.of();

        List<MatchResult> results = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();

        // Rule 1: exact
        actressRepo.resolveByName(romaji).ifPresent(a -> {
            if (seen.add(a.getId())) results.add(new MatchResult(a.getId(), Rule.EXACT));
        });

        // Rule 2: reversal
        if (!isSingleToken(romaji)) {
            String reversed = joinTokens(reverseTokens(splitTokens(romaji)));
            actressRepo.resolveByName(reversed).ifPresent(a -> {
                if (seen.add(a.getId())) results.add(new MatchResult(a.getId(), Rule.REVERSAL));
            });
        }

        // Rule 3: punctuation normalization
        String normalized = stripPunctuation(romaji);
        if (!normalized.equals(romaji)) {
            actressRepo.resolveByName(normalized).ifPresent(a -> {
                if (seen.add(a.getId())) results.add(new MatchResult(a.getId(), Rule.PUNCT_NORM));
            });
        }

        // Rule 4: last-name-only (only for single-token input)
        if (isSingleToken(romaji)) {
            for (com.organizer3.model.Actress a : actressRepo.findByLastTokenCi(romaji, LAST_TOKEN_QUERY_LIMIT)) {
                if (seen.add(a.getId())) results.add(new MatchResult(a.getId(), Rule.LAST_NAME_ONLY));
            }
        }

        return List.copyOf(results);
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    static String[] splitTokens(String romaji) {
        return romaji.trim().split("\\s+");
    }

    static String[] reverseTokens(String[] tokens) {
        String[] reversed = new String[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            reversed[i] = tokens[tokens.length - 1 - i];
        }
        return reversed;
    }

    static String joinTokens(String[] tokens) {
        return String.join(" ", tokens);
    }

    static String stripPunctuation(String romaji) {
        return romaji.replaceAll("[-,]", "").replaceAll("\\s+", " ").trim();
    }

    static boolean isSingleToken(String romaji) {
        return splitTokens(romaji).length == 1;
    }

    /**
     * Splits LLM-returned romaji into {@code [first, last]} name parts.
     *
     * <ul>
     *   <li>Null/blank → {@code [null, null]}.
     *   <li>Single token → {@code [null, token]} — last name is required; first is optional.
     *   <li>Two tokens → {@code [token0, token1]} (assumes "Given Family" LLM output order).
     *   <li>Three+ tokens → {@code [token0, join(rest, " ")]}; logged at DEBUG.
     * </ul>
     */
    public static String[] splitRomaji(String romaji) {
        if (romaji == null || romaji.isBlank()) {
            return new String[]{null, null};
        }
        String[] tokens = romaji.trim().split("\\s+");
        if (tokens.length == 1) {
            return new String[]{null, tokens[0]};
        }
        if (tokens.length == 2) {
            return new String[]{tokens[0], tokens[1]};
        }
        log.debug("splitRomaji: {} tokens in '{}', joining tail as last name", tokens.length, romaji);
        StringBuilder last = new StringBuilder(tokens[1]);
        for (int i = 2; i < tokens.length; i++) {
            last.append(' ').append(tokens[i]);
        }
        return new String[]{tokens[0], last.toString()};
    }
}
