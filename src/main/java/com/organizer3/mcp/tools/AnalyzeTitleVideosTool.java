package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.titlefolder.TitleFolderService;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * MCP adapter over {@link TitleFolderService#analyzeVideos(String)}.
 *
 * <p>Returns per-video metadata plus a heuristic verdict (likely_duplicates /
 * likely_set / ambiguous / insufficient_metadata / single_video / no_videos).
 * All real logic — including the duration-spread classifier and the verdict
 * explanation — lives in the service. This adapter exists only to translate
 * the JSON-RPC envelope and shape the legacy MCP result records.
 */
public class AnalyzeTitleVideosTool implements Tool {

    private final TitleFolderService folderService;

    public AnalyzeTitleVideosTool(TitleRepository titleRepo, VideoRepository videoRepo) {
        this(new TitleFolderService(titleRepo, videoRepo, null));
    }

    public AnalyzeTitleVideosTool(TitleFolderService folderService) {
        this.folderService = folderService;
    }

    /** Convenience ctor for callers that already wire jdbi/repos separately. */
    public AnalyzeTitleVideosTool(TitleRepository titleRepo, VideoRepository videoRepo, Jdbi jdbi) {
        this(new TitleFolderService(titleRepo, videoRepo, jdbi));
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
        TitleFolderService.AnalysisResult r = folderService.analyzeVideos(code);
        List<VideoRow> rows = r.videos().stream()
                .map(v -> new VideoRow(v.videoId(), v.filename(), v.volumeId(),
                        v.durationSec(), v.width(), v.height(),
                        v.videoCodec(), v.audioCodec(), v.container()))
                .toList();
        return new Result(r.titleId(), r.titleCode(), r.videoCount(),
                r.verdict(), r.explanation(), rows);
    }

    // Legacy MCP records — kept stable for any external MCP-client consumers.
    public record VideoRow(long videoId, String filename, String volumeId,
                           Long durationSec, Integer width, Integer height,
                           String videoCodec, String audioCodec, String container) {}
    public record Result(long titleId, String titleCode, int videoCount,
                         String verdict, String explanation, List<VideoRow> videos) {}
}
