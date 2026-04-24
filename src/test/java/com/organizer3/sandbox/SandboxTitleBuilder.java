package com.organizer3.sandbox;

import com.organizer3.filesystem.VolumeFileSystem;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for fake title folder structures on the NAS sandbox.
 *
 * <p>Files are 1 byte — organize services inspect filenames, not content.
 * FS creation ({@link #build}) and DB registration ({@link #registerInDb}) are separate.
 *
 * <p>Usage:
 * <pre>
 *   Path titleDir = new SandboxTitleBuilder()
 *       .inDir(methodRunDir)
 *       .withCode("MIDE-123")
 *       .withCover("mide123pl.jpg")
 *       .withVideo("mide123.mp4")
 *       .build(fs);
 * </pre>
 */
public class SandboxTitleBuilder {

    private static final byte[] ONE_BYTE = {1};

    private Path parentDir;
    private String code = "MIDE-123";
    private String coverFilename;
    private List<String> extraCovers = new ArrayList<>();
    private String videoFilename;
    private String videoSubfolder = "video";
    private boolean videoAtBase = false;
    private Instant timestamp;

    public SandboxTitleBuilder inDir(Path parent) {
        this.parentDir = parent;
        return this;
    }

    public SandboxTitleBuilder withCode(String code) {
        this.code = code;
        return this;
    }

    /** Override default cover filename (default: {@code {code}pl.jpg} lowercased with dashes removed). */
    public SandboxTitleBuilder withCover(String filename) {
        this.coverFilename = filename;
        return this;
    }

    /** Add a second cover at the title base (triggers "multiple covers" condition). */
    public SandboxTitleBuilder withExtraCover(String filename) {
        this.extraCovers.add(filename);
        return this;
    }

    /** Override default video filename (default: {@code {code}.mp4} lowercased with dashes removed). */
    public SandboxTitleBuilder withVideo(String filename) {
        this.videoFilename = filename;
        return this;
    }

    /** Place the video in a named subfolder instead of the default {@code video/}. */
    public SandboxTitleBuilder videoInSubfolder(String subfolder) {
        this.videoSubfolder = subfolder;
        this.videoAtBase = false;
        return this;
    }

    /** Place the video directly at the title folder root (for restructure tests). */
    public SandboxTitleBuilder videoAtBase() {
        this.videoAtBase = true;
        return this;
    }

    /**
     * Set timestamps on cover + video files.
     * Use {@link #withTimestampOffset} when you need two fixtures separated by a known interval
     * to stay safely above the 2-second {@code shouldChange} tolerance.
     */
    public SandboxTitleBuilder withTimestamp(Instant t) {
        this.timestamp = t;
        return this;
    }

    /**
     * Convenience: timestamp = {@code base} minus {@code offsetSeconds}.
     * Always use at least 10s offsets in fixtures — well clear of SMB rounding.
     */
    public SandboxTitleBuilder withTimestampOffset(Instant base, long offsetSeconds) {
        this.timestamp = base.minusSeconds(offsetSeconds);
        return this;
    }

    /**
     * Creates the title folder structure on the NAS filesystem.
     *
     * @return the title folder path (e.g. {@code parentDir/MIDE-123/})
     */
    public Path build(VolumeFileSystem fs) throws IOException {
        if (parentDir == null) throw new IllegalStateException("inDir() is required");

        String folderName = code;
        Path titleDir = parentDir.resolve(folderName);
        fs.createDirectories(titleDir);

        String cover = coverFilename != null ? coverFilename : defaultCover(code);
        Path coverPath = titleDir.resolve(cover);
        fs.writeFile(coverPath, ONE_BYTE);
        if (timestamp != null) {
            fs.setTimestamps(coverPath, timestamp, timestamp);
        }

        for (String extra : extraCovers) {
            Path extraPath = titleDir.resolve(extra);
            fs.writeFile(extraPath, ONE_BYTE);
            if (timestamp != null) {
                fs.setTimestamps(extraPath, timestamp, timestamp);
            }
        }

        String video = videoFilename != null ? videoFilename : defaultVideo(code);
        Path videoPath;
        if (videoAtBase) {
            videoPath = titleDir.resolve(video);
        } else {
            Path subDir = titleDir.resolve(videoSubfolder);
            fs.createDirectories(subDir);
            videoPath = subDir.resolve(video);
        }
        fs.writeFile(videoPath, ONE_BYTE);
        if (timestamp != null) {
            fs.setTimestamps(videoPath, timestamp, timestamp);
        }

        return titleDir;
    }

    /**
     * Inserts a {@code titles} + {@code title_locations} row into the in-memory DB.
     * Call after {@link #build} so the path is known.
     *
     * @param titleDir  path returned by {@link #build}
     * @param volumeId  volume id (must already exist in the {@code volumes} table)
     * @param partitionId  partition id (e.g. {@code "library"})
     */
    public void registerInDb(Jdbi jdbi, Path titleDir, String volumeId, String partitionId) {
        jdbi.useTransaction(h -> {
            h.execute("INSERT OR IGNORE INTO titles (code, base_code) VALUES (?, ?)",
                    code, baseCode(code));
            long titleId = h.createQuery("SELECT id FROM titles WHERE code = ?")
                    .bind(0, code)
                    .mapTo(Long.class)
                    .one();
            h.execute(
                    "INSERT OR IGNORE INTO title_locations "
                            + "(title_id, volume_id, partition_id, path, last_seen_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    titleId,
                    volumeId,
                    partitionId,
                    titleDir.toString(),
                    Instant.now().toString());
        });
    }

    // -------------------------------------------------------------------------

    private static String defaultCover(String code) {
        return code.toLowerCase().replace("-", "") + "pl.jpg";
    }

    private static String defaultVideo(String code) {
        return code.toLowerCase().replace("-", "") + ".mp4";
    }

    private static String baseCode(String code) {
        int idx = code.lastIndexOf('-');
        return idx >= 0 ? code.substring(0, idx).toUpperCase() : code.toUpperCase();
    }
}
