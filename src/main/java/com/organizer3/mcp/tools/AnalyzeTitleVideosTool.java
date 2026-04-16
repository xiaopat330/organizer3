package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Title;
import com.organizer3.model.Video;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Heuristic analysis of a single title's video set: returns per-video metadata plus a
 * verdict the agent can reason about — "likely_duplicates" (same content, different
 * qualities), "likely_set" (legitimate multi-part release), "ambiguous", or
 * "insufficient_metadata" when any video hasn't been probed yet.
 *
 * <p>Decision rules, in order:
 * <ol>
 *   <li>Single video → "single_video" (nothing to analyze).</li>
 *   <li>Any video missing duration → "insufficient_metadata"; run
 *       {@code probe videos} on the hosting volume first.</li>
 *   <li>All pairwise durations within {@code DUPLICATE_DURATION_TOLERANCE_SEC} of each
 *       other → "likely_duplicates". Same-content rips at different bitrates or codecs
 *       share a duration.</li>
 *   <li>All pairwise durations differ by more than {@code SET_DURATION_SEPARATION_SEC}
 *       → "likely_set". Distinct parts of a release have distinct runtimes.</li>
 *   <li>Otherwise → "ambiguous"; needs manual review.</li>
 * </ol>
 */
public class AnalyzeTitleVideosTool implements Tool {

    /** Durations within this window are treated as "same". */
    static final int DUPLICATE_DURATION_TOLERANCE_SEC = 30;

    /**
     * Minimum pairwise duration gap to call a set legitimate. Values between the two
     * thresholds are reported as ambiguous.
     */
    static final int SET_DURATION_SEPARATION_SEC = 120;

    private final TitleRepository titleRepo;
    private final VideoRepository videoRepo;

    public AnalyzeTitleVideosTool(TitleRepository titleRepo, VideoRepository videoRepo) {
        this.titleRepo = titleRepo;
        this.videoRepo = videoRepo;
    }

    @Override public String name()        { return "analyze_title_videos"; }
    @Override public String description() {
        return "Return all videos for one title with media metadata plus a heuristic verdict "
             + "(likely_duplicates / likely_set / ambiguous / insufficient_metadata / single_video).";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("code", "string", "Title code to inspect (e.g. SKY-283).")
                .require("code")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String code = Schemas.requireString(args, "code");
        Title title = titleRepo.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("No title with code " + code));

        List<Video> videos = videoRepo.findByTitle(title.getId());
        List<VideoRow> rows = new ArrayList<>();
        for (Video v : videos) {
            rows.add(new VideoRow(v.getId(), v.getFilename(), v.getVolumeId(),
                    v.getDurationSec(), v.getWidth(), v.getHeight(),
                    v.getVideoCodec(), v.getAudioCodec(), v.getContainer()));
        }

        String verdict = classify(videos);
        String explanation = explain(videos, verdict);
        return new Result(title.getId(), title.getCode(), videos.size(), verdict, explanation, rows);
    }

    static String classify(List<Video> videos) {
        if (videos.isEmpty())     return "no_videos";
        if (videos.size() == 1)   return "single_video";
        for (Video v : videos) {
            if (v.getDurationSec() == null) return "insufficient_metadata";
        }
        long minDur = Long.MAX_VALUE, maxDur = Long.MIN_VALUE;
        for (Video v : videos) {
            long d = v.getDurationSec();
            if (d < minDur) minDur = d;
            if (d > maxDur) maxDur = d;
        }
        long spread = maxDur - minDur;
        if (spread <= DUPLICATE_DURATION_TOLERANCE_SEC) return "likely_duplicates";
        if (spread >= SET_DURATION_SEPARATION_SEC)      return "likely_set";
        return "ambiguous";
    }

    private static String explain(List<Video> videos, String verdict) {
        return switch (verdict) {
            case "no_videos"              -> "Title has no videos indexed.";
            case "single_video"           -> "Only one video file — nothing to compare.";
            case "insufficient_metadata"  -> "At least one video has no duration yet. Run 'probe videos' on the hosting volume.";
            case "likely_duplicates"      -> "All videos share a near-identical duration (within "
                                              + DUPLICATE_DURATION_TOLERANCE_SEC + "s). Same content, likely quality variants.";
            case "likely_set"             -> "Videos have clearly distinct durations (gap ≥ "
                                              + SET_DURATION_SEPARATION_SEC + "s). Consistent with a multi-part release.";
            case "ambiguous"              -> "Duration spread falls between the duplicate and set thresholds. Needs manual review.";
            default                       -> "";
        };
    }

    public record VideoRow(long videoId, String filename, String volumeId,
                           Long durationSec, Integer width, Integer height,
                           String videoCodec, String audioCodec, String container) {}
    public record Result(long titleId, String titleCode, int videoCount,
                         String verdict, String explanation, List<VideoRow> videos) {}
}
