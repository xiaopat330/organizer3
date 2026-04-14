package com.organizer3.avstars.web;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvScreenshotRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.avstars.repository.AvVideoTagRepository;
import com.organizer3.config.AppConfig;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Backing service for the AV Stars web UI.
 */
@RequiredArgsConstructor
public class AvBrowseService {

    private final AvActressRepository actressRepo;
    private final AvVideoRepository videoRepo;
    private final AvScreenshotRepository screenshotRepo;
    private final AvVideoTagRepository videoTagRepo;

    private static final int TOP_TAGS_LIMIT = 5;

    /** Returns all AV actresses sorted by video count descending, then stage name. */
    public List<AvActressSummary> findAll() {
        return actressRepo.findAllByVideoCountDesc().stream()
                .map(a -> toSummary(a, videoTagRepo.findTopTagSlugsForActress(a.getId(), TOP_TAGS_LIMIT)))
                .toList();
    }

    /** Returns favorited AV actresses sorted by stage name. */
    public List<AvActressSummary> findFavorites() {
        return actressRepo.findFavorites().stream()
                .map(a -> toSummary(a, videoTagRepo.findTopTagSlugsForActress(a.getId(), TOP_TAGS_LIMIT)))
                .toList();
    }

    /** Returns full profile for a single actress. Empty if not found. */
    public Optional<AvActressDetail> getActressDetail(long actressId) {
        return actressRepo.findById(actressId).map(this::toDetail);
    }

    /** Returns video list for an actress. */
    public List<AvVideoSummary> findVideosForActress(long actressId) {
        var videos = videoRepo.findByActress(actressId);
        var videoIds = videos.stream().map(v -> v.getId()).toList();
        Map<Long, List<String>> tagsByVideo = videoTagRepo.findTagSlugsByVideoIds(videoIds);
        return videos.stream()
                .map(v -> toVideoSummary(v, tagsByVideo.getOrDefault(v.getId(), List.of())))
                .toList();
    }

    /** Records a visit for the given actress. Returns updated visitCount and lastVisitedAt. */
    public AvActressSummary recordVisit(long actressId) {
        actressRepo.recordVisit(actressId);
        return actressRepo.findById(actressId)
                .map(a -> toSummary(a, videoTagRepo.findTopTagSlugsForActress(actressId, TOP_TAGS_LIMIT)))
                .orElseThrow(() -> new IllegalArgumentException("Actress not found: " + actressId));
    }

    /** Toggles favorite on an actress. Returns updated summary. */
    public AvActressDetail toggleActressFavorite(long actressId, boolean favorite) {
        actressRepo.toggleFavorite(actressId, favorite);
        return actressRepo.findById(actressId)
                .map(this::toDetail)
                .orElseThrow(() -> new IllegalArgumentException("Actress not found: " + actressId));
    }

    /** Toggles bookmark on an actress. Returns updated summary. */
    public AvActressDetail toggleActressBookmark(long actressId, boolean bookmark) {
        actressRepo.toggleBookmark(actressId, bookmark);
        return actressRepo.findById(actressId)
                .map(this::toDetail)
                .orElseThrow(() -> new IllegalArgumentException("Actress not found: " + actressId));
    }

    /** Toggles favorite on a video. Returns updated video summary. */
    public AvVideoSummary toggleVideoFavorite(long videoId, boolean favorite) {
        videoRepo.toggleFavorite(videoId, favorite);
        return videoRepo.findById(videoId)
                .map(v -> toVideoSummary(v, videoTagRepo.findTagSlugsByVideoIds(List.of(videoId)).getOrDefault(videoId, List.of())))
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));
    }

    /** Toggles bookmark on a video. Returns updated video summary. */
    public AvVideoSummary toggleVideoBookmark(long videoId, boolean bookmark) {
        videoRepo.toggleBookmark(videoId, bookmark);
        return videoRepo.findById(videoId)
                .map(v -> toVideoSummary(v, videoTagRepo.findTagSlugsByVideoIds(List.of(videoId)).getOrDefault(videoId, List.of())))
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));
    }

    /** Marks a video as watched. Returns updated video summary. */
    public AvVideoSummary recordVideoWatch(long videoId) {
        videoRepo.recordWatch(videoId);
        return videoRepo.findById(videoId)
                .map(v -> toVideoSummary(v, videoTagRepo.findTagSlugsByVideoIds(List.of(videoId)).getOrDefault(videoId, List.of())))
                .orElseThrow(() -> new IllegalArgumentException("Video not found: " + videoId));
    }

    /** Returns full detail for a single video including SMB URL and screenshot URLs. Empty if not found. */
    public Optional<AvVideoDetail> getVideoDetail(long videoId) {
        var videoOpt = videoRepo.findById(videoId);
        if (videoOpt.isEmpty()) return Optional.empty();
        var v = videoOpt.get();
        var actress = actressRepo.findById(v.getAvActressId()).orElse(null);
        String stageName  = actress != null ? actress.getStageName()  : null;
        String folderName = actress != null ? actress.getFolderName() : null;
        String smbUrl     = deriveSmbUrl(v, folderName);
        List<String> screenshotUrls = screenshotRepo.findByVideoId(videoId).stream()
                .map(s -> "/api/av/screenshots/" + videoId + "/" + s.getSeq())
                .toList();
        List<String> tags = videoTagRepo.findTagSlugsByVideoIds(List.of(videoId))
                .getOrDefault(videoId, List.of());
        return Optional.of(toVideoDetail(v, stageName, folderName, smbUrl, screenshotUrls, tags));
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private AvActressSummary toSummary(AvActress a, List<String> topTags) {
        return AvActressSummary.builder()
                .id(a.getId())
                .stageName(a.getStageName())
                .headshotUrl(deriveHeadshotUrl(a.getHeadshotPath()))
                .activeFrom(a.getActiveFrom())
                .activeTo(a.getActiveTo())
                .videoCount(a.getVideoCount())
                .favorite(a.isFavorite())
                .bookmark(a.isBookmark())
                .resolved(a.getIafdId() != null)
                .visitCount(a.getVisitCount())
                .lastVisitedAt(a.getLastVisitedAt())
                .topTags(topTags != null ? topTags : List.of())
                .build();
    }

    private AvActressDetail toDetail(AvActress a) {
        return AvActressDetail.builder()
                .id(a.getId())
                .stageName(a.getStageName())
                .folderName(a.getFolderName())
                .headshotUrl(deriveHeadshotUrl(a.getHeadshotPath()))
                .resolved(a.getIafdId() != null)
                .activeFrom(a.getActiveFrom())
                .activeTo(a.getActiveTo())
                .iafdId(a.getIafdId())
                .dateOfBirth(a.getDateOfBirth())
                .birthplace(a.getBirthplace())
                .nationality(a.getNationality())
                .ethnicity(a.getEthnicity())
                .hairColor(a.getHairColor())
                .eyeColor(a.getEyeColor())
                .heightCm(a.getHeightCm())
                .weightKg(a.getWeightKg())
                .measurements(a.getMeasurements())
                .cup(a.getCup())
                .tattoos(a.getTattoos())
                .piercings(a.getPiercings())
                .websiteUrl(a.getWebsiteUrl())
                .notes(a.getNotes())
                .grade(a.getGrade())
                .favorite(a.isFavorite())
                .bookmark(a.isBookmark())
                .rejected(a.isRejected())
                .videoCount(a.getVideoCount())
                .totalSizeBytes(a.getTotalSizeBytes())
                .visitCount(a.getVisitCount())
                .lastVisitedAt(a.getLastVisitedAt())
                .build();
    }

    private AvVideoSummary toVideoSummary(AvVideo v, List<String> tags) {
        return AvVideoSummary.builder()
                .id(v.getId())
                .filename(v.getFilename())
                .relativePath(v.getRelativePath())
                .bucket(v.getBucket())
                .sizeBytes(v.getSizeBytes())
                .resolution(v.getResolution())
                .studio(v.getStudio())
                .releaseDate(v.getReleaseDate())
                .parsedTitle(v.getParsedTitle())
                .favorite(v.isFavorite())
                .bookmark(v.isBookmark())
                .watched(v.isWatched())
                .watchCount(v.getWatchCount())
                .lastWatchedAt(v.getLastWatchedAt())
                .tags(tags != null ? tags : List.of())
                .build();
    }

    private static String deriveHeadshotUrl(String headshotPath) {
        if (headshotPath == null) return null;
        return "/api/av/headshots/" + Path.of(headshotPath).getFileName();
    }

    private AvVideoDetail toVideoDetail(AvVideo v, String actressStageName, String actressFolderName,
                                        String smbUrl, List<String> screenshotUrls, List<String> tags) {
        return AvVideoDetail.builder()
                .id(v.getId())
                .actressId(v.getAvActressId())
                .actressStageName(actressStageName)
                .actressFolderName(actressFolderName)
                .volumeId(v.getVolumeId())
                .filename(v.getFilename())
                .relativePath(v.getRelativePath())
                .extension(v.getExtension())
                .bucket(v.getBucket())
                .sizeBytes(v.getSizeBytes())
                .mtime(v.getMtime())
                .addedDate(v.getAddedDate())
                .studio(v.getStudio())
                .releaseDate(v.getReleaseDate())
                .parsedTitle(v.getParsedTitle())
                .resolution(v.getResolution())
                .codec(v.getCodec())
                .favorite(v.isFavorite())
                .bookmark(v.isBookmark())
                .watched(v.isWatched())
                .watchCount(v.getWatchCount())
                .lastWatchedAt(v.getLastWatchedAt())
                .smbUrl(smbUrl)
                .screenshotUrls(screenshotUrls != null ? screenshotUrls : List.of())
                .tags(tags != null ? tags : List.of())
                .build();
    }

    /**
     * Constructs an SMB URL for the video. Returns null if the volume is not found in config
     * or AppConfig is not initialized (e.g. in tests that don't boot the full app).
     *
     * <p>Path segments are percent-encoded so names with spaces (e.g. "Anissa Kate") are
     * safe to use as SMB URLs in media players.
     *
     * <p>Example: {@code //qnap2/AV/stars} + {@code Anissa Kate} + {@code old/file.mp4}
     * → {@code smb://qnap2/AV/stars/Anissa%20Kate/old/file.mp4}
     */
    static String deriveSmbUrl(AvVideo v, String actressFolderName) {
        if (v.getVolumeId() == null || actressFolderName == null) return null;
        try {
            var volOpt = AppConfig.get().volumes().findById(v.getVolumeId());
            if (volOpt.isEmpty()) return null;
            String smbPath = volOpt.get().smbPath(); // e.g. "//qnap2/AV/stars"
            String base = "smb:" + smbPath;           // "smb://qnap2/AV/stars"
            String folder = encodePathSegments(actressFolderName);
            String rel    = encodePathSegments(v.getRelativePath());
            return base + "/" + folder + "/" + rel;
        } catch (Exception e) {
            return null;
        }
    }

    /** Percent-encodes each path segment's spaces and special characters, preserving {@code /} separators. */
    private static String encodePathSegments(String path) {
        // Encode characters that are not safe in URI paths, preserving '/' separators
        return path.replace("%", "%25")
                   .replace(" ", "%20")
                   .replace("#", "%23")
                   .replace("?", "%3F")
                   .replace("[", "%5B")
                   .replace("]", "%5D");
    }
}
