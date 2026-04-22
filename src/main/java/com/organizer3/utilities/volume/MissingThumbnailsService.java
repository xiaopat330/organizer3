package com.organizer3.utilities.volume;

import com.organizer3.media.ThumbnailService;
import com.organizer3.model.Title;
import com.organizer3.model.Video;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans a volume for videos whose thumbnails are incomplete or missing. "Missing" here means the
 * on-disk thumbnail cache does not have a {@code .count} marker with all numbered JPEGs present —
 * the same predicate {@link ThumbnailService#isComplete} uses, which is the source of truth
 * across the app (there is no DB column for thumbnail presence).
 *
 * <p>The scan joins videos with their owning title to resolve the {@code titleCode} used as the
 * thumbnail directory name. Returned pairs are ready to feed into
 * {@link ThumbnailService#generateBlocking}.
 */
public final class MissingThumbnailsService {

    private final VideoRepository videoRepo;
    private final TitleRepository titleRepo;
    private final ThumbnailService thumbnailService;

    public MissingThumbnailsService(VideoRepository videoRepo, TitleRepository titleRepo,
                                    ThumbnailService thumbnailService) {
        this.videoRepo = videoRepo;
        this.titleRepo = titleRepo;
        this.thumbnailService = thumbnailService;
    }

    /**
     * Iterate all videos on the volume and return the ones whose thumbnail cache is incomplete.
     * Expensive for large volumes (one filesystem stat per video); not suitable for per-refresh
     * health computation. Intended for the task's scan phase which runs explicitly.
     */
    public List<Missing> findMissing(String volumeId) {
        List<Video> videos = videoRepo.findByVolume(volumeId);
        if (videos.isEmpty()) return List.of();

        // Batch-resolve title codes. videos.getTitleId() is not null for rows from sync.
        Map<Long, String> titleCodeById = new HashMap<>();
        for (Video v : videos) {
            if (v.getTitleId() == null) continue;
            titleCodeById.computeIfAbsent(v.getTitleId(), id ->
                    titleRepo.findById(id).map(Title::getCode).orElse(null));
        }

        List<Missing> missing = new ArrayList<>();
        for (Video v : videos) {
            if (v.getTitleId() == null) continue;
            String titleCode = titleCodeById.get(v.getTitleId());
            if (titleCode == null) continue;
            if (thumbnailService.isComplete(titleCode, v.getFilename(), v.getId())) continue;
            missing.add(new Missing(titleCode, v));
        }
        return missing;
    }

    public record Missing(String titleCode, Video video) {}
}
