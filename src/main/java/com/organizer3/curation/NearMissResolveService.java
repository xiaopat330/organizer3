package com.organizer3.curation;

import com.organizer3.javdb.draft.DraftActress;
import com.organizer3.javdb.draft.DraftActressRepository;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import com.organizer3.translation.NameComposer;
import com.organizer3.translation.TranslationNormalization;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Atomically resolves a pending-kanji identity decision (alias-of-existing or new-canonical),
 * cascading to every unresolved draft that shares the same normalized kanji stage name.
 */
public class NearMissResolveService {

    private final ActressRepository actressRepo;
    private final DraftActressRepository draftActressRepo;

    public NearMissResolveService(ActressRepository actressRepo,
                                   DraftActressRepository draftActressRepo) {
        this.actressRepo      = actressRepo;
        this.draftActressRepo = draftActressRepo;
    }

    public enum Outcome { ALIAS, CANONICAL }

    public record ResolveRequest(
            String kanji,
            String primarySlug,
            Outcome outcome,
            Long aliasOfActressId,
            String englishFirst,
            String englishLast,
            String llmRomaji
    ) {}

    public record ResolveResult(int updatedDrafts, int insertedAliases) {}

    public ResolveResult resolve(ResolveRequest req) {
        validate(req);

        String normalizedKanji = TranslationNormalization.normalize(req.kanji());
        String composedName    = NameComposer.compose(req.englishFirst(), req.englishLast());

        if (req.outcome() == Outcome.ALIAS) {
            return resolveAlias(normalizedKanji, composedName, req);
        } else {
            return resolveCanonical(normalizedKanji, composedName, req);
        }
    }

    // ── ALIAS outcome ────────────────────────────────────────────────────────

    private ResolveResult resolveAlias(String normalizedKanji, String composedName,
                                        ResolveRequest req) {
        long actressId = req.aliasOfActressId();

        Actress actress = actressRepo.findById(actressId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No actress found with id " + actressId));
        String existingCanonical = actress.getCanonicalName();

        Set<String> aliasSet = buildAliasSet(normalizedKanji, req.llmRomaji(), composedName,
                existingCanonical);

        int inserted = 0;
        for (String alias : aliasSet) {
            if (actressRepo.insertAliasIfAbsent(actressId, alias)) {
                inserted++;
            }
        }

        int updated = draftActressRepo.cascadeAliasResolution(
                normalizedKanji, actressId, req.englishFirst(), req.englishLast());

        return new ResolveResult(updated, inserted);
    }

    private Set<String> buildAliasSet(String normalizedKanji, String llmRomaji,
                                       String composedName, String existingCanonical) {
        Set<String> set = new LinkedHashSet<>();
        addIfUsable(set, normalizedKanji, existingCanonical);
        addIfUsable(set, llmRomaji, existingCanonical);
        if (composedName != null && !composedName.equals(llmRomaji)) {
            addIfUsable(set, composedName, existingCanonical);
        }
        return set;
    }

    private static void addIfUsable(Set<String> set, String value, String exclude) {
        if (value == null || value.isBlank()) return;
        if (value.equals(exclude)) return;
        set.add(value);
    }

    // ── CANONICAL outcome ────────────────────────────────────────────────────

    private ResolveResult resolveCanonical(String normalizedKanji, String composedName,
                                            ResolveRequest req) {
        // Tools-page entry passes primarySlug=null. Per spec §4.4, auto-elect the oldest
        // unresolved draft for this kanji as the primary (invisible to the user — every
        // sibling resolves to the same actresses.id post-promotion regardless).
        String primarySlug = (req.primarySlug() == null || req.primarySlug().isBlank())
                ? draftActressRepo.findOldestUnresolvedSlugByKanji(normalizedKanji)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No unresolved draft found for kanji: " + normalizedKanji))
                : req.primarySlug();

        DraftActress primary = draftActressRepo.findBySlug(primarySlug)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Primary draft not found: " + primarySlug));

        if (primary.getLinkToExistingId() != null || primary.getLinkToDraftSlug() != null) {
            throw new IllegalArgumentException(
                    "Primary draft is already resolved: " + primarySlug);
        }

        int updated = draftActressRepo.cascadeCanonicalResolution(
                normalizedKanji, primarySlug, req.englishFirst(), req.englishLast());

        return new ResolveResult(updated, 0);
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private static void validate(ResolveRequest req) {
        if (req.kanji() == null || req.kanji().isBlank()) {
            throw new IllegalArgumentException("kanji is required");
        }
        if (req.englishLast() == null || req.englishLast().isBlank()) {
            throw new IllegalArgumentException("englishLast is required");
        }
        if (req.outcome() == Outcome.ALIAS && req.aliasOfActressId() == null) {
            throw new IllegalArgumentException("aliasOfActressId is required for outcome ALIAS");
        }
        // primarySlug is OPTIONAL for CANONICAL — null means "auto-pick the oldest
        // unresolved draft for this kanji" (Tools-page entry). Editor entry passes the
        // explicit slug. Either is valid; resolveCanonical handles both.
    }
}
