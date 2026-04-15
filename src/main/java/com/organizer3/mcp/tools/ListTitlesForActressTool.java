package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * List all titles that credit an actress. Resolves the actress via name or id,
 * then unions titles linked by {@code actress_id} (filing) and those linked via
 * {@code title_actresses} (credits) — the standard "all titles for this actress" view.
 *
 * <p>Each row includes all locations (volume + path) so the agent can see where
 * a title physically lives without a follow-up call.
 */
public class ListTitlesForActressTool implements Tool {

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;

    public ListTitlesForActressTool(ActressRepository actressRepo, TitleRepository titleRepo) {
        this.actressRepo = actressRepo;
        this.titleRepo = titleRepo;
    }

    @Override public String name()        { return "list_titles_for_actress"; }
    @Override public String description() { return "List all titles credited to an actress (by name or id). Each row includes all on-disk locations."; }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("name", "string", "Canonical or alias name. Either this or 'id' is required.")
                .prop("id",   "integer", "Actress id. Either this or 'name' is required.")
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
        if (resolved.isEmpty()) return new Result(false, nameArg, null, 0, List.of());

        Actress a = resolved.get();
        List<Title> titles = titleRepo.findByActressIncludingAliases(a.getId());

        List<TitleRow> rows = new ArrayList<>(titles.size());
        for (Title t : titles) {
            List<LocationDto> locs = new ArrayList<>();
            for (TitleLocation loc : t.getLocations()) {
                locs.add(new LocationDto(
                        loc.getVolumeId(),
                        loc.getPath() == null ? null : loc.getPath().toString()
                ));
            }
            rows.add(new TitleRow(
                    t.getId(),
                    t.getCode(),
                    t.getTitleOriginal(),
                    t.getReleaseDate() == null ? null : t.getReleaseDate().toString(),
                    t.isFavorite(),
                    t.isBookmark(),
                    t.getTags(),
                    locs
            ));
        }

        return new Result(true, nameArg, a.getCanonicalName(), rows.size(), rows);
    }

    public record Result(
            boolean found,
            String queriedName,
            String resolvedCanonicalName,
            int total,
            List<TitleRow> titles
    ) {}

    public record TitleRow(
            long id,
            String code,
            String titleOriginal,
            String releaseDate,
            boolean favorite,
            boolean bookmark,
            List<String> tags,
            List<LocationDto> locations
    ) {}

    public record LocationDto(String volumeId, String path) {}
}
