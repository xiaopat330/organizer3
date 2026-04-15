package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetch a full title snapshot by product code: DB record, all locations (volume + path),
 * filing actress, all credited actresses, tags, and video files.
 *
 * <p>Complementary to {@code sample_title_folder} (not yet implemented), which additionally
 * reads the on-disk folder contents.
 */
public class LookupTitleTool implements Tool {

    private final TitleRepository titleRepo;
    private final TitleActressRepository titleActressRepo;
    private final ActressRepository actressRepo;
    private final VideoRepository videoRepo;

    public LookupTitleTool(TitleRepository titleRepo,
                           TitleActressRepository titleActressRepo,
                           ActressRepository actressRepo,
                           VideoRepository videoRepo) {
        this.titleRepo = titleRepo;
        this.titleActressRepo = titleActressRepo;
        this.actressRepo = actressRepo;
        this.videoRepo = videoRepo;
    }

    @Override public String name()        { return "lookup_title"; }
    @Override public String description() { return "Fetch a title by product code. Returns title record, all on-disk locations, filing actress, all credited actresses, tags, and video files."; }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("code", "string", "Product code, e.g. 'MIDE-123'. Case-insensitive.")
                .require("code")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String code = Schemas.requireString(args, "code").toUpperCase();
        Optional<Title> found = titleRepo.findByCode(code);
        if (found.isEmpty()) return new Result(false, code, null);

        Title t = found.get();

        // Filing actress (FK) — nullable
        String filingActress = null;
        if (t.getActressId() != null) {
            filingActress = actressRepo.findById(t.getActressId())
                    .map(Actress::getCanonicalName).orElse(null);
        }

        // All credited actresses from the junction table
        List<Long> junctionIds = titleActressRepo.findActressIdsByTitle(t.getId());
        List<String> creditedNames = junctionIds.isEmpty()
                ? List.of()
                : actressRepo.findByIds(junctionIds).stream()
                        .map(Actress::getCanonicalName).sorted().toList();

        // Effective credits = filing union junction (as the schema doc notes)
        List<String> allCredits = new ArrayList<>(creditedNames);
        if (filingActress != null && !allCredits.contains(filingActress)) {
            allCredits.add(0, filingActress);
        }

        List<LocationDto> locations = new ArrayList<>();
        for (TitleLocation loc : t.getLocations()) {
            locations.add(new LocationDto(
                    loc.getVolumeId(),
                    loc.getPath() == null ? null : loc.getPath().toString(),
                    loc.getPartitionId(),
                    loc.getAddedDate() == null ? null : loc.getAddedDate().toString(),
                    loc.getLastSeenAt() == null ? null : loc.getLastSeenAt().toString()
            ));
        }

        List<VideoDto> videos = videoRepo.findByTitle(t.getId()).stream()
                .map(v -> new VideoDto(
                        v.getVolumeId(),
                        v.getFilename(),
                        v.getPath() == null ? null : v.getPath().toString()))
                .toList();

        return new Result(true, code, new TitleDetail(
                t.getId(),
                t.getCode(),
                t.getLabel(),
                t.getBaseCode(),
                t.getTitleOriginal(),
                t.getTitleEnglish(),
                t.getReleaseDate() == null ? null : t.getReleaseDate().toString(),
                t.isFavorite(),
                t.isBookmark(),
                t.isRejected(),
                filingActress,
                creditedNames,
                allCredits,
                t.getTags(),
                locations,
                videos
        ));
    }

    public record Result(boolean found, String queriedCode, TitleDetail title) {}

    public record TitleDetail(
            long id,
            String code,
            String label,
            String baseCode,
            String titleOriginal,
            String titleEnglish,
            String releaseDate,
            boolean favorite,
            boolean bookmark,
            boolean rejected,
            String filingActress,
            List<String> creditedActresses,
            List<String> allActresses,
            List<String> tags,
            List<LocationDto> locations,
            List<VideoDto> videos
    ) {}

    public record LocationDto(
            String volumeId,
            String path,
            String partitionId,
            String addedDate,
            String lastSeenAt
    ) {}

    public record VideoDto(String volumeId, String filename, String path) {}
}
