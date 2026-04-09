package com.organizer3.command;

import com.organizer3.media.ThumbnailService;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Deletes all cached video thumbnails. Useful after changing thumbnail
 * configuration (interval, size) so thumbnails regenerate on next visit.
 */
@Slf4j
@RequiredArgsConstructor
public class ClearThumbnailsCommand implements Command {

    private final ThumbnailService thumbnailService;

    @Override
    public String name() {
        return "clear-thumbnails";
    }

    @Override
    public String description() {
        return "Delete all cached video thumbnails so they regenerate on next visit";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        Path root = thumbnailService.root();
        if (!Files.isDirectory(root)) {
            io.println("No thumbnails directory found — nothing to clear.");
            return;
        }

        int titles = 0;
        int files = 0;
        int errors = 0;

        try (Stream<Path> titleDirs = Files.list(root)) {
            for (Path titleDir : titleDirs.filter(Files::isDirectory).toList()) {
                try {
                    int deleted = deleteRecursively(titleDir);
                    files += deleted;
                    titles++;
                } catch (IOException e) {
                    log.error("Failed to delete {}: {}", titleDir, e.getMessage());
                    errors++;
                }
            }
        } catch (IOException e) {
            io.println("Error scanning thumbnails directory: " + e.getMessage());
            log.error("Error scanning thumbnails directory", e);
            return;
        }

        io.println(String.format("Cleared %d thumbnails across %d titles.%s",
                files, titles, errors > 0 ? "  Errors: " + errors : ""));
    }

    private static int deleteRecursively(Path dir) throws IOException {
        int count = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                boolean isFile = Files.isRegularFile(p);
                Files.delete(p);
                if (isFile) count++;
            }
        }
        return count;
    }
}
