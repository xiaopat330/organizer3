package com.organizer3.javdb.enrichment;

import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks whether a given actress (by DB id) appears in a title's cast list.
 *
 * <p>Matching is name-based: the actress's {@code stage_name} and all known
 * {@code actress_aliases} are compared against each cast entry's name field.
 * This mirrors the name-match logic in {@link EnrichmentRunner#matchAndRecordActressSlug}.
 */
public class CastMatcher {

    private final ActressRepository actressRepo;

    public CastMatcher(ActressRepository actressRepo) {
        this.actressRepo = actressRepo;
    }

    public record MatchResult(boolean matched, String matchedName) {}

    /**
     * Returns whether {@code actressId} appears in {@code cast} by name.
     * Returns an unmatched result if the actress is not found in the DB or cast is empty.
     */
    public MatchResult match(long actressId, List<TitleExtract.CastEntry> cast) {
        if (cast == null || cast.isEmpty()) return new MatchResult(false, null);
        Optional<Actress> maybeActress = actressRepo.findById(actressId);
        if (maybeActress.isEmpty()) return new MatchResult(false, null);
        Actress actress = maybeActress.get();
        List<ActressAlias> aliases = actressRepo.findAliases(actressId);
        Set<String> knownNames = buildNames(actress, aliases);
        for (TitleExtract.CastEntry entry : cast) {
            if (entry.name() != null && knownNames.contains(entry.name())) {
                return new MatchResult(true, entry.name());
            }
        }
        return new MatchResult(false, null);
    }

    private Set<String> buildNames(Actress actress, List<ActressAlias> aliases) {
        Set<String> names = new HashSet<>();
        if (actress.getStageName() != null) names.add(actress.getStageName());
        for (ActressAlias alias : aliases) {
            if (alias.aliasName() != null) names.add(alias.aliasName());
        }
        return names;
    }
}
