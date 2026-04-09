package com.organizer3.web;

import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.model.Video;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VideoRepository;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.smb.SmbConnectionFactory.SmbShareHandle;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for discovering video files on SMB shares and streaming them to the browser.
 *
 * <p>Video discovery happens on-demand when the user first visits a title's detail page.
 * The service scans the title folder on SMB, persists discovered files to the {@code videos}
 * table, and returns them. Subsequent visits serve from the DB.
 *
 * <p>Streaming is a direct SMB-to-HTTP byte proxy with Range request support.
 * No local caching — plays immediately from the SMB share.
 */
@Slf4j
@RequiredArgsConstructor
public class VideoStreamService {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "m4v", "mkv", "avi", "wmv", "mov", "ts", "rmvb"
    );

    /** Common subfolder names where video files are typically stored. */
    private static final List<String> VIDEO_SUBFOLDER_NAMES = List.of(
            "video", "h265", "h264", "hevc", "4k", "fhd", "hd"
    );

    private static final java.util.Map<String, String> MIME_TYPES = java.util.Map.of(
            "mp4", "video/mp4",
            "m4v", "video/mp4",
            "mkv", "video/x-matroska",
            "avi", "video/x-msvideo",
            "wmv", "video/x-ms-wmv",
            "mov", "video/quicktime",
            "ts", "video/mp2t",
            "rmvb", "application/vnd.rn-realmedia-vbr"
    );

    private final TitleRepository titleRepo;
    private final VideoRepository videoRepo;
    private final SmbConnectionFactory smbFactory;

    /**
     * Returns videos for the given title code. Discovers from SMB on first call,
     * returns from DB on subsequent calls.
     */
    public List<VideoInfo> findVideos(String titleCode) throws IOException {
        Title title = titleRepo.findByCode(titleCode).orElse(null);
        if (title == null) return List.of();

        // Check if we already have videos in the DB
        List<Video> existing = title.getId() != null ? videoRepo.findByTitle(title.getId()) : List.of();
        if (!existing.isEmpty()) {
            return existing.stream().map(v -> toInfo(v, null)).toList();
        }

        // Discover from SMB
        return discoverVideos(title);
    }

    /**
     * Returns the video record and its MIME type for streaming.
     */
    public Optional<Video> findVideoById(long videoId) {
        return videoRepo.findById(videoId);
    }

    /**
     * Returns the MIME type for a video file based on its extension.
     */
    public String mimeType(Video video) {
        String ext = extensionOf(video.getFilename());
        return MIME_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    /**
     * Opens an SMB connection for streaming the given video file.
     * The caller must close the returned handle.
     */
    public SmbShareHandle openStream(Video video) throws IOException {
        return smbFactory.open(video.getVolumeId());
    }

    /**
     * Returns the SMB-relative path for a video file (combining the title location
     * path with the video path within the title folder).
     */
    public String smbRelativePath(Video video) {
        return video.getPath().toString();
    }

    private List<VideoInfo> discoverVideos(Title title) throws IOException {
        if (title.getLocations().isEmpty()) return List.of();

        // Try each location until we find video files
        for (TitleLocation loc : title.getLocations()) {
            List<VideoInfo> found = discoverFromLocation(title, loc);
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    private List<VideoInfo> discoverFromLocation(Title title, TitleLocation loc) throws IOException {
        String volumeId = loc.getVolumeId();
        Path titlePath = loc.getPath();

        try (SmbShareHandle handle = smbFactory.open(volumeId)) {
            String titleDir = titlePath.toString();
            List<DiscoveredFile> found = new ArrayList<>();

            // 1. Check video subfolders first
            for (String sub : VIDEO_SUBFOLDER_NAMES) {
                String subDir = titleDir + "/" + sub;
                if (handle.folderExists(subDir)) {
                    scanFolder(handle, subDir, titleDir, found);
                }
            }

            // 2. Also check the title root (some titles have video files at the root)
            scanFolder(handle, titleDir, titleDir, found);

            // Persist and return
            List<VideoInfo> result = new ArrayList<>();
            for (DiscoveredFile df : found) {
                Video video = videoRepo.save(Video.builder()
                        .titleId(title.getId())
                        .volumeId(volumeId)
                        .filename(df.filename)
                        .path(Path.of(df.fullPath))
                        .lastSeenAt(LocalDate.now())
                        .build());
                result.add(toInfo(video, df.fileSize));
            }
            log.info("Discovered {} video(s) for {}", result.size(), title.getCode());
            return result;
        } catch (IOException e) {
            log.warn("Failed to discover videos for {} on volume {}: {}",
                    title.getCode(), loc.getVolumeId(), e.getMessage());
            return List.of();
        }
    }

    private void scanFolder(SmbShareHandle handle, String folder, String titleDir,
                            List<DiscoveredFile> accumulator) throws IOException {
        // Track filenames we've already found to avoid duplicates when scanning root after subfolders
        Set<String> seenFilenames = new java.util.HashSet<>();
        for (DiscoveredFile df : accumulator) {
            seenFilenames.add(df.filename);
        }

        List<String> entries = handle.listDirectory(folder);
        for (String name : entries) {
            if (seenFilenames.contains(name)) continue;
            String ext = extensionOf(name);
            if (VIDEO_EXTENSIONS.contains(ext)) {
                String fullPath = folder + "/" + name;
                long size;
                try {
                    size = handle.fileSize(fullPath);
                } catch (IOException e) {
                    size = -1;
                }
                accumulator.add(new DiscoveredFile(name, fullPath, size));
                seenFilenames.add(name);
            }
        }
    }

    private VideoInfo toInfo(Video video, Long discoveredSize) {
        return new VideoInfo(
                video.getId(),
                video.getFilename(),
                video.getPath().toString(),
                discoveredSize,
                mimeType(video)
        );
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private record DiscoveredFile(String filename, String fullPath, long fileSize) {}

    /** JSON-serializable video info returned to the frontend. */
    public record VideoInfo(
            long id,
            String filename,
            String path,
            Long fileSize,
            String mimeType
    ) {}
}
