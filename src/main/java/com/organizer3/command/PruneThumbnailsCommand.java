package com.organizer3.command;

import com.organizer3.media.ThumbnailService;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes orphaned thumbnail directories — thumbnails whose title code no
 * longer matches any title in the database.
 *
 * <p>Walks {@code data/thumbnails/<titleCode>/} and deletes any directory
 * tree where the title code is not found in the DB. Does not require a
 * mounted volume.
 */
@Slf4j
@RequiredArgsConstructor
public class PruneThumbnailsCommand implements Command {

    private final TitleRepository titleRepo;
    private final ThumbnailService thumbnailService;

    @Override
    public String name() {
        return "prune-thumbnails";
    }

    @Override
    public String description() {
        return "Remove orphaned thumbnail directories that no longer match any title in the database";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        Path root = thumbnailService.root();
        if (!Files.isDirectory(root)) {
            io.println("No thumbnails directory found — nothing to prune.");
            return;
        }

        int pruned = 0;
        int kept = 0;
        int errors = 0;

        try (Stream<Path> titleDirs = Files.list(root)) {
            for (Path titleDir : titleDirs.filter(Files::isDirectory).toList()) {
                String titleCode = titleDir.getFileName().toString();
                if (titleRepo.findByCode(titleCode).isEmpty()) {
                    try {
                        deleteRecursively(titleDir);
                        log.info("Pruned orphaned thumbnails: {}", titleCode);
                        pruned++;
                    } catch (IOException e) {
                        log.error("Failed to delete {}: {}", titleDir, e.getMessage());
                        errors++;
                    }
                } else {
                    kept++;
                }
            }
        } catch (IOException e) {
            io.println("Error scanning thumbnails directory: " + e.getMessage());
            log.error("Error scanning thumbnails directory", e);
            return;
        }

        io.println(String.format("Pruned: %d  |  Kept: %d  |  Errors: %d", pruned, kept, errors));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            // Delete files before directories (reverse sorted by depth)
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
