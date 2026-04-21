package com.organizer3.avstars.sync;

import com.organizer3.avstars.model.AvActress;
import com.organizer3.avstars.model.AvVideo;
import com.organizer3.avstars.repository.AvActressRepository;
import com.organizer3.avstars.repository.AvVideoRepository;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.sync.MediaExtensions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Syncs an {@code avstars} volume: one top-level folder per actress, with arbitrary
 * sub-folder nesting. Fully independent of the JAV sync pipeline.
 *
 * <p>Algorithm per sync run:
 * <ol>
 *   <li>Stamp sync start time</li>
 *   <li>For each top-level directory: findOrCreate an {@code av_actress} row</li>
 *   <li>Walk each actress folder recursively, skipping ignored subfolder names</li>
 *   <li>Upsert every video file found into {@code av_videos}</li>
 *   <li>Recompute video_count + total_size_bytes for each actress</li>
 *   <li>Delete av_videos rows not seen this sync run (orphans)</li>
 *   <li>Stamp volume last_synced_at</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class AvStarsSyncOperation {

    private final AvActressRepository actressRepo;
    private final AvVideoRepository videoRepo;
    private final VolumeRepository volumeRepo;

    public void execute(VolumeConfig volume, VolumeStructureDef structure,
                        VolumeFileSystem fs, SessionContext ctx, CommandIO io) throws IOException {
        LocalDateTime syncStart = LocalDateTime.now();
        Set<String> ignoredFolders = structure.ignoredSubfolders().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        io.println("Syncing " + volume.id() + " (av stars) ...");
        ensureVolumeRecord(volume);

        Path root = Path.of("/");
        List<Path> topLevel = fs.listDirectory(root).stream()
                .filter(p -> fs.isDirectory(p) && !isHidden(p) && !isIgnored(p, ignoredFolders))
                .sorted()
                .toList();

        int actressCount = 0;
        int videoCount = 0;

        try (var progress = io.startProgress("Scanning", topLevel.size())) {
            for (Path actressDir : topLevel) {
                String folderName = actressDir.getFileName().toString();
                progress.setLabel(folderName);

                long actressId = actressRepo.upsert(AvActress.builder()
                        .volumeId(volume.id())
                        .folderName(folderName)
                        .stageName(folderName)
                        .firstSeenAt(syncStart)
                        .build());

                int videosForActress = scanActressFolder(
                        actressDir, actressId, volume.id(), fs, ignoredFolders, syncStart, videoCount);
                videoCount += videosForActress;

                // Recompute counts from DB
                List<AvVideo> actressVideos = videoRepo.findByActress(actressId);
                long totalBytes = actressVideos.stream()
                        .mapToLong(v -> v.getSizeBytes() != null ? v.getSizeBytes() : 0L)
                        .sum();
                actressRepo.updateCounts(actressId, actressVideos.size(), totalBytes);
                actressRepo.updateLastScanned(actressId, syncStart);

                actressCount++;
                progress.advance();
            }
        }

        // Orphan cleanup — remove video rows not touched during this sync run
        videoRepo.deleteOrphanedByVolume(volume.id(), syncStart);

        // Stamp volume
        volumeRepo.updateLastSyncedAt(volume.id(), syncStart);

        log.info("AvStars sync finalized — volume={} actresses={} videos={}",
                volume.id(), actressCount, videoCount);
        io.println("Done. " + actressCount + " actresses, " + videoCount + " videos indexed.");
    }

    /**
     * Walks an actress folder recursively, upserting all video files found. Returns the
     * number of video files upserted.
     */
    private int scanActressFolder(Path actressDir, long actressId, String volumeId,
                                  VolumeFileSystem fs, Set<String> ignoredFolders,
                                  LocalDateTime syncStart, int ignored) throws IOException {
        List<Path> allPaths = fs.walk(actressDir);
        int count = 0;

        for (Path path : allPaths) {
            if (fs.isDirectory(path)) continue;
            if (isIgnoredByAncestor(path, actressDir, ignoredFolders)) continue;
            if (!MediaExtensions.isVideo(path)) continue;

            String relativePath = actressDir.relativize(path).toString();
            String filename = path.getFileName().toString();
            String ext = extension(filename);
            String bucket = bucketOf(relativePath);

            // Attempt to get file size; proceed with null if unavailable
            Long sizeBytes = null;
            try {
                sizeBytes = fileSizeOf(path, fs);
            } catch (Exception e) {
                log.debug("Could not determine size for {}: {}", path, e.getMessage());
            }

            videoRepo.upsert(AvVideo.builder()
                    .avActressId(actressId)
                    .volumeId(volumeId)
                    .relativePath(relativePath)
                    .filename(filename)
                    .extension(ext)
                    .sizeBytes(sizeBytes)
                    .lastSeenAt(syncStart)
                    .bucket(bucket)
                    .build());
            count++;
        }
        return count;
    }

    /** Returns true if the path's own filename starts with '.' (hidden). */
    private static boolean isHidden(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(".");
    }

    /** Returns true if the path's filename matches an ignored folder name (case-insensitive). */
    private static boolean isIgnored(Path path, Set<String> ignoredFolders) {
        return ignoredFolders.contains(path.getFileName().toString().toLowerCase());
    }

    /**
     * Returns true if any path component between actressDir and path (exclusive of both)
     * is in the ignored folder set, or starts with '.'.
     */
    private static boolean isIgnoredByAncestor(Path path, Path actressDir,
                                                Set<String> ignoredFolders) {
        Path rel = actressDir.relativize(path);
        // Walk each component except the final filename
        for (int i = 0; i < rel.getNameCount() - 1; i++) {
            String component = rel.getName(i).toString();
            if (component.startsWith(".") || ignoredFolders.contains(component.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** Returns the first-level subfolder name under the actress root, or null if at root. */
    private static String bucketOf(String relativePath) {
        int slash = relativePath.indexOf('/');
        if (slash < 0) return null;
        String first = relativePath.substring(0, slash);
        return first.isEmpty() ? null : first;
    }

    /** Extracts the file extension (without the dot), or null if none. */
    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : null;
    }

    /**
     * Attempts to read the file size. VolumeFileSystem doesn't expose size directly,
     * so we use a 0-byte read to detect existence only. Real size would need smbj metadata.
     * Returns null if the size can't be determined.
     */
    private Long fileSizeOf(Path path, VolumeFileSystem fs) {
        // VolumeFileSystem doesn't expose a size() method — size is populated later by
        // a richer SMB metadata call if/when added. Return null for now.
        return null;
    }

    private void ensureVolumeRecord(VolumeConfig volume) {
        volumeRepo.findById(volume.id()).ifPresentOrElse(
                v -> {},
                () -> {
                    com.organizer3.model.Volume v = new com.organizer3.model.Volume(
                            volume.id(), volume.structureType());
                    volumeRepo.save(v);
                });
    }
}
