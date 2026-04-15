package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Find groups of actresses whose names (canonical or alias) have the same set of tokens
 * — typically catching first-name/last-name inversions like "Yua Mikami" vs "Mikami Yua".
 *
 * <p>Tokenizes each name on whitespace, normalizes to lowercase, sorts, and buckets by the
 * resulting tuple. Any bucket containing ≥2 distinct actress ids is reported.
 * Aliases pointing at the same canonical are ignored — they're already resolved.
 */
public class FindNameOrderVariantsTool implements Tool {

    private static final int DEFAULT_MIN_TOKENS = 2;
    private static final int DEFAULT_LIMIT      = 100;
    private static final int MAX_LIMIT          = 1000;

    private final ActressRepository actressRepo;

    public FindNameOrderVariantsTool(ActressRepository actressRepo) {
        this.actressRepo = actressRepo;
    }

    @Override public String name()        { return "find_name_order_variants"; }
    @Override public String description() {
        return "Find actress pairs/groups whose names have the same tokens in different order "
             + "(e.g. 'Yua Mikami' vs 'Mikami Yua'). Reports groups with ≥2 distinct actress ids only.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("min_tokens", "integer", "Only consider names with at least this many tokens. Default 2.", DEFAULT_MIN_TOKENS)
                .prop("limit",      "integer", "Maximum number of variant groups to return. Default 100, max 1000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int minTokens = Math.max(1, Schemas.optInt(args, "min_tokens", DEFAULT_MIN_TOKENS));
        int limit     = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        Map<String, List<Member>> buckets = new LinkedHashMap<>();
        for (Actress a : actressRepo.findAll()) {
            addName(buckets, a.getId(), a.getCanonicalName(), false, minTokens);
            for (ActressAlias alias : actressRepo.findAliases(a.getId())) {
                addName(buckets, a.getId(), alias.aliasName(), true, minTokens);
            }
        }

        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<Member>> e : buckets.entrySet()) {
            List<Member> members = e.getValue();
            long distinctIds = members.stream().mapToLong(m -> m.actressId).distinct().count();
            if (distinctIds < 2) continue;
            groups.add(new Group(Arrays.asList(e.getKey().split(" ")), members));
            if (groups.size() >= limit) break;
        }
        return new Result(groups.size(), groups);
    }

    private static void addName(Map<String, List<Member>> buckets, long actressId, String name,
                                boolean isAlias, int minTokens) {
        if (name == null || name.isBlank()) return;
        String[] toks = name.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (toks.length < minTokens) return;
        String[] sorted = toks.clone();
        Arrays.sort(sorted);
        String key = String.join(" ", sorted);
        buckets.computeIfAbsent(key, k -> new ArrayList<>())
               .add(new Member(actressId, name, isAlias));
    }

    public record Member(long actressId, String name, boolean isAlias) {}
    public record Group(List<String> tokens, List<Member> members) {}
    public record Result(int count, List<Group> groups) {}
}
