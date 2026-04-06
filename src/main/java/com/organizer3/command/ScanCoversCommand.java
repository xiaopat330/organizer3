package com.organizer3.command;

import com.organizer3.config.AppConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.covers.CoverPath;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.VolumeRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import com.organizer3.shell.io.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Scans the currently mounted volume and collects cover images for titles
 * in structured (stars) partitions.
 *
 * <p>For each title, looks for a single image file in the title's folder on the
 * remote volume. If found and not already present locally, copies it into the
 * local covers hierarchy at {@code data/covers/<LABEL>/<baseCode>.<ext>}.
 *
 * <p>Requires a mounted, synced volume. Only processes titles in {@code stars/}
 * partitions (conventional and stars-flat structure types).
 */
public class ScanCoversCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(ScanCoversCommand.class);

    private final TitleRepository titleRepo;
    private final VolumeRepository volumeRepo;
    private final CoverPath coverPath;

    public ScanCoversCommand(TitleRepository titleRepo, VolumeRepository volumeRepo,
                             CoverPath coverPath) {
        this.titleRepo = titleRepo;
        this.volumeRepo = volumeRepo;
        this.coverPath = coverPath;
    }

    @Override
    public String name() {
        return "scan-covers";
    }

    @Override
    public String description() {
        return "Collect cover images from the mounted volume's stars partitions";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        VolumeConfig volume = ctx.getMountedVolume();
        if (volume == null) {
            io.println("No volume mounted. Use: mount <id>");
            return;
        }

        if (!ctx.isConnected()) {
            io.println("Volume not connected. Use: mount <id>");
            return;
        }

        // Must be synced
        var dbVolume = volumeRepo.findById(volume.id());
        if (dbVolume.isEmpty() || dbVolume.get().getLastSyncedAt() == null) {
            io.println("Volume '" + volume.id() + "' has not been synced. Run a sync command first.");
            return;
        }

        // Must have a structured partition (stars)
        VolumeStructureDef structure = AppConfig.get().volumes()
                .findStructureById(volume.structureType())
                .orElse(null);
        if (structure == null || structure.structuredPartition() == null) {
            io.println("Volume '" + volume.id() + "' has no stars partitions — nothing to scan.");
            return;
        }

        List<Title> titles = titleRepo.findByVolume(volume.id()).stream()
                .filter(t -> t.partitionId() != null && t.partitionId().startsWith("stars/"))
                .filter(t -> t.label() != null && !t.label().isBlank())
                .filter(t -> t.baseCode() != null && !t.baseCode().isBlank())
                .toList();

        if (titles.isEmpty()) {
            io.println("No eligible titles found on volume '" + volume.id() + "'.");
            return;
        }

        VolumeFileSystem fs = ctx.getActiveConnection().fileSystem();

        int collected = 0;
        int skipped = 0;
        int noImage = 0;
        int errors = 0;

        try (Progress progress = io.startProgress("Scanning covers", titles.size())) {
            for (Title title : titles) {
                try {
                    if (coverPath.exists(title)) {
                        skipped++;
                        progress.advance();
                        continue;
                    }

                    String imageFile = findCoverImage(fs, title.path());
                    if (imageFile == null) {
                        noImage++;
                        log.warn("No cover image found for {} at {}", title.code(), title.path());
                        progress.advance();
                        continue;
                    }

                    String ext = CoverPath.extensionOf(imageFile);
                    Path localPath = coverPath.resolve(title, ext);
                    Files.createDirectories(localPath.getParent());

                    Path remotePath = title.path().resolve(imageFile);
                    try (InputStream in = fs.openFile(remotePath)) {
                        Files.copy(in, localPath);
                    }
                    collected++;

                } catch (IOException e) {
                    errors++;
                    log.error("Failed to collect cover for {}: {}", title.code(), e.getMessage());
                }
                progress.advance();
            }
        }

        io.println(String.format("Covers collected: %d  |  Skipped (existing): %d  |  No image: %d  |  Errors: %d",
                collected, skipped, noImage, errors));
    }

    /**
     * Searches a title folder for a single image file. Returns the filename if
     * exactly one is found, picks the first alphabetically if multiple are found
     * (with a warning logged), or returns null if none are found.
     */
    private String findCoverImage(VolumeFileSystem fs, Path titlePath) throws IOException {
        List<Path> children = fs.listDirectory(titlePath);
        List<String> images = children.stream()
                .map(p -> p.getFileName().toString())
                .filter(name -> !fs.isDirectory(titlePath.resolve(name)))
                .filter(CoverPath::isImageFile)
                .sorted()
                .toList();

        if (images.isEmpty()) return null;
        if (images.size() > 1) {
            log.warn("Multiple cover images in {}: {}; using {}", titlePath, images, images.get(0));
        }
        return images.get(0);
    }
}
