package com.organizer3.utilities.avstars;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvVideoRepository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates reads for the Utilities → AV Stars screen. Wraps the browse service for list
 * rendering and adds Utilities-specific concerns (filter/sort options, per-actress tech
 * summary used in the detail pane header).
 *
 * <p>Kept deliberately thin — the consumption-focused data lives in {@link AvBrowseService}
 * and is reused. Only Utilities-only projections are added here.
 */
public final class AvStarsCatalogService {

    public enum Filter { ALL, UNRESOLVED, FAVORITES, BOOKMARKS, REJECTED }
    public enum Sort { VIDEO_COUNT_DESC, STAGE_NAME_ASC, LAST_SCANNED_DESC }

    private final AvActressRepository actressRepo;
    private final AvVideoRepository videoRepo;

    public AvStarsCatalogService(AvActressRepository actressRepo, AvVideoRepository videoRepo) {
        this.actressRepo = actressRepo;
        this.videoRepo = videoRepo;
    }

    /**
     * Filtered + sorted list of actresses. Rejected actresses are excluded by default except
     * for the explicit {@link Filter#REJECTED} filter — the Utilities screen shouldn't spam
     * noise from explicitly-hidden rows.
     */
    public List<Row> list(Filter filter, Sort sort) {
        List<AvActress> all = actressRepo.findAllByVideoCountDesc();
        List<AvActress> filtered = switch (filter) {
            case UNRESOLVED -> all.stream().filter(a -> a.getIafdId() == null || a.getIafdId().isBlank())
                    .filter(a -> !a.isRejected()).toList();
            case FAVORITES -> all.stream().filter(AvActress::isFavorite).filter(a -> !a.isRejected()).toList();
            case BOOKMARKS -> all.stream().filter(AvActress::isBookmark).filter(a -> !a.isRejected()).toList();
            case REJECTED -> all.stream().filter(AvActress::isRejected).toList();
            case ALL -> all.stream().filter(a -> !a.isRejected()).toList();
        };
        Comparator<AvActress> cmp = switch (sort) {
            case VIDEO_COUNT_DESC -> Comparator.comparingInt(AvActress::getVideoCount).reversed()
                    .thenComparing(AvActress::getStageName, Comparator.nullsLast(String::compareToIgnoreCase));
            case STAGE_NAME_ASC -> Comparator.comparing(AvActress::getStageName,
                    Comparator.nullsLast(String::compareToIgnoreCase));
            case LAST_SCANNED_DESC -> Comparator.comparing(AvActress::getLastScannedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return filtered.stream()
                .sorted(cmp)
                .map(this::toRow)
                .toList();
    }

    /** Top-of-screen summary counts. Cheap enough to recompute per request. */
    public Counts counts() {
        List<AvActress> all = actressRepo.findAllByVideoCountDesc();
        int total = 0, resolved = 0, fav = 0;
        for (AvActress a : all) {
            if (a.isRejected()) continue;
            total++;
            if (a.getIafdId() != null && !a.getIafdId().isBlank()) resolved++;
            if (a.isFavorite()) fav++;
        }
        return new Counts(total, resolved, fav);
    }

    /**
     * Derives a compact tech summary for an actress: codec distribution, resolution
     * distribution, total bytes. Null if the actress has no videos. Cached nowhere — the
     * library is small enough that recomputing per detail load is fine.
     */
    public TechSummary techSummary(long actressId) {
        List<AvVideo> videos = videoRepo.findByActress(actressId);
        if (videos.isEmpty()) return null;
        Map<String, Integer> byCodec = new LinkedHashMap<>();
        Map<String, Integer> byResolution = new LinkedHashMap<>();
        long totalBytes = 0;
        for (AvVideo v : videos) {
            byCodec.merge(codecLabel(v.getCodec()), 1, Integer::sum);
            byResolution.merge(resolutionLabel(v.getResolution()), 1, Integer::sum);
            if (v.getSizeBytes() != null) totalBytes += v.getSizeBytes();
        }
        return new TechSummary(videos.size(), totalBytes, byCodec, byResolution);
    }

    private Row toRow(AvActress a) {
        String headshotUrl = a.getHeadshotPath() != null
                ? "/api/av/headshots/" + java.nio.file.Path.of(a.getHeadshotPath()).getFileName()
                : null;
        return new Row(
                a.getId(),
                a.getFolderName(),
                a.getStageName(),
                a.getVolumeId(),
                a.getVideoCount(),
                a.isFavorite(),
                a.isBookmark(),
                a.isRejected(),
                a.getGrade(),
                a.getIafdId() != null && !a.getIafdId().isBlank(),
                headshotUrl);
    }

    private static String codecLabel(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        return raw.toLowerCase();
    }

    private static String resolutionLabel(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        return raw;
    }

    /** List-row projection. Keep lean — detail panes use the existing AvActressDetail. */
    public record Row(long id, String folderName, String stageName, String volumeId,
                      int videoCount, boolean favorite, boolean bookmark, boolean rejected,
                      String grade, boolean resolved, String headshotUrl) {}

    public record Counts(int total, int resolved, int favorites) {}

    public record TechSummary(int videoCount, long totalBytes,
                              Map<String, Integer> byCodec,
                              Map<String, Integer> byResolution) {}
}
