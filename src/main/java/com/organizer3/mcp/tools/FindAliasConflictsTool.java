package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Data-integrity check: flag aliases whose {@code alias_name} collides with another
 * actress's {@code canonical_name}, or with a second actress's alias of the same
 * spelling. Either case is a resolution bug — {@link ActressRepository#resolveByName}
 * will pick one actress arbitrarily while the other's titles get mis-attributed.
 *
 * <p>Comparison is case-insensitive and whitespace-normalized, mirroring how the real
 * resolver hashes names.
 */
public class FindAliasConflictsTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT     = 2000;

    private final ActressRepository actressRepo;

    public FindAliasConflictsTool(ActressRepository actressRepo) {
        this.actressRepo = actressRepo;
    }

    @Override public String name()        { return "find_alias_conflicts"; }
    @Override public String description() {
        return "Flag aliases whose name collides with another actress's canonical name (or another actress's alias). "
             + "Such collisions cause resolveByName to pick one actress arbitrarily and mis-attribute titles.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("limit", "integer", "Maximum number of conflicts to return. Default 200, max 2000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        int limit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        // Build name → [owners] for every canonical + every alias, keyed by normalized form.
        Map<String, List<Holder>> index = new HashMap<>();
        for (Actress a : actressRepo.findAll()) {
            addHolder(index, a.getCanonicalName(), new Holder(a.getId(), a.getCanonicalName(), HolderKind.CANONICAL));
            for (ActressAlias al : actressRepo.findAliases(a.getId())) {
                addHolder(index, al.aliasName(), new Holder(a.getId(), al.aliasName(), HolderKind.ALIAS));
            }
        }

        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<Holder>> e : index.entrySet()) {
            List<Holder> owners = e.getValue();
            long distinctActresses = owners.stream().mapToLong(h -> h.actressId).distinct().count();
            if (distinctActresses < 2) continue;
            conflicts.add(new Conflict(e.getKey(), owners));
            if (conflicts.size() >= limit) break;
        }
        return new Result(conflicts.size(), conflicts);
    }

    private static void addHolder(Map<String, List<Holder>> index, String name, Holder holder) {
        if (name == null || name.isBlank()) return;
        String key = name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(holder);
    }

    public enum HolderKind { CANONICAL, ALIAS }
    public record Holder(long actressId, String name, HolderKind kind) {}
    public record Conflict(String normalizedName, List<Holder> owners) {}
    public record Result(int count, List<Conflict> conflicts) {}
}
