package com.organizer3.javdb.enrichment;

import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Checks whether a given actress (by DB id) appears in a title's cast list.
 *
 * <p>Matching compares the actress's {@code canonical_name}, {@code stage_name}, and
 * {@code actress_aliases} against each cast entry's name field. Names are normalized
 * via Unicode NFKC + ASCII case-folding + trim before comparison so that romanized
 * stage names like {@code "AIKA"} match a DB row whose canonical name is {@code "Aika"}
 * (and so fullwidth Latin from DMM/javdb folds to ASCII).
 */
public class CastMatcher {

    private final ActressRepository actressRepo;

    public CastMatcher(ActressRepository actressRepo) {
        this.actressRepo = actressRepo;
    }

    public record MatchResult(boolean matched, String matchedName, String matchedSlug) {
        public static MatchResult unmatched() { return new MatchResult(false, null, null); }
    }

    /**
     * Returns whether {@code actressId} appears in {@code cast} by name.
     * Returns an unmatched result if the actress is not found in the DB or cast is empty.
     */
    public MatchResult match(long actressId, List<TitleExtract.CastEntry> cast) {
        if (cast == null || cast.isEmpty()) return MatchResult.unmatched();
        Optional<Actress> maybeActress = actressRepo.findById(actressId);
        if (maybeActress.isEmpty()) return MatchResult.unmatched();
        Actress actress = maybeActress.get();
        List<ActressAlias> aliases = actressRepo.findAliases(actressId);
        Set<String> knownNames = buildNormalizedNames(actress, aliases);
        if (knownNames.isEmpty()) return MatchResult.unmatched();
        for (TitleExtract.CastEntry entry : cast) {
            String n = normalize(entry.name());
            if (n != null && knownNames.contains(n)) {
                return new MatchResult(true, entry.name(), entry.slug());
            }
        }
        return MatchResult.unmatched();
    }

    private static Set<String> buildNormalizedNames(Actress actress, List<ActressAlias> aliases) {
        Set<String> names = new HashSet<>();
        addNormalized(names, actress.getCanonicalName());
        addNormalized(names, actress.getStageName());
        for (ActressAlias alias : aliases) {
            addNormalized(names, alias.aliasName());
        }
        return names;
    }

    private static void addNormalized(Set<String> sink, String value) {
        String n = normalize(value);
        if (n != null) sink.add(n);
    }

    /**
     * Normalize a name for comparison: Unicode NFKC (folds fullwidth Latin → ASCII),
     * lowercased with {@link Locale#ROOT} (ASCII case-fold; no-op for kana/kanji),
     * and trimmed. Returns {@code null} if {@code value} is {@code null} or empty
     * after normalization.
     */
    static String normalize(String value) {
        if (value == null) return null;
        String n = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
        return n.isEmpty() ? null : n;
    }
}
