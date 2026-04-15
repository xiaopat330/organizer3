package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolve an actress by name or alias and return a full profile snapshot:
 * canonical name, all aliases, operational flags, title counts, and per-volume
 * distribution. Designed to ground subsequent tool calls with a canonical id.
 *
 * <p>Accepts either {@code name} (resolved via {@link ActressRepository#resolveByName})
 * or {@code id} (exact fetch).
 */
public class LookupActressTool implements Tool {

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;

    public LookupActressTool(ActressRepository actressRepo, TitleRepository titleRepo) {
        this.actressRepo = actressRepo;
        this.titleRepo = titleRepo;
    }

    @Override public String name()        { return "lookup_actress"; }
    @Override public String description() { return "Resolve an actress by name or alias, or by id. Returns canonical name, aliases, flags, title count, and per-volume breakdown."; }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("name", "string", "Canonical or alias name to resolve. Either this or 'id' is required.")
                .prop("id",   "integer", "Actress id for direct lookup. Either this or 'name' is required.")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String nameArg = Schemas.optString(args, "name", null);
        long   idArg   = Schemas.optLong(args, "id", -1);
        if ((nameArg == null || nameArg.isBlank()) && idArg < 0) {
            throw new IllegalArgumentException("Must provide either 'name' or 'id'");
        }

        Optional<Actress> resolved = (idArg >= 0)
                ? actressRepo.findById(idArg)
                : actressRepo.resolveByName(nameArg);

        if (resolved.isEmpty()) {
            return new Result(false, nameArg, null);
        }

        Actress a = resolved.get();
        List<String> aliases = actressRepo.findAliases(a.getId()).stream()
                .map(ActressAlias::aliasName).toList();
        List<Title> titles = titleRepo.findByActressIncludingAliases(a.getId());

        Map<String, Long> perVolume = new LinkedHashMap<>();
        for (Title t : titles) {
            for (TitleLocation loc : t.getLocations()) {
                perVolume.merge(loc.getVolumeId(), 1L, Long::sum);
            }
        }

        String matchedAlias = null;
        if (nameArg != null && !nameArg.equalsIgnoreCase(a.getCanonicalName())) {
            matchedAlias = aliases.stream()
                    .filter(x -> x.equalsIgnoreCase(nameArg)).findFirst().orElse(null);
        }

        return new Result(true, nameArg, new Profile(
                a.getId(),
                a.getCanonicalName(),
                a.getStageName(),
                a.getTier() == null ? null : a.getTier().name(),
                a.getGrade() == null ? null : a.getGrade().name(),
                a.isFavorite(),
                a.isBookmark(),
                a.isRejected(),
                aliases,
                matchedAlias,
                titles.size(),
                perVolume
        ));
    }

    public record Result(boolean found, String queriedName, Profile actress) {}

    public record Profile(
            long id,
            String canonicalName,
            String stageName,
            String tier,
            String grade,
            boolean favorite,
            boolean bookmark,
            boolean rejected,
            List<String> aliases,
            String matchedAlias,
            int titleCount,
            Map<String, Long> titlesByVolume
    ) {}
}
