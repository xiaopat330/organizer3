package com.organizer3.command;

import com.organizer3.covers.CoverPath;
import com.organizer3.repository.TitleRepository;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Removes orphaned cover images — covers whose baseCode no longer matches
 * any title in the database.
 *
 * <p>Does not require a mounted volume. Walks the local covers directory and
 * checks each file against the DB across all volumes.
 */
public class PruneCoversCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(PruneCoversCommand.class);

    private final TitleRepository titleRepo;
    private final CoverPath coverPath;

    public PruneCoversCommand(TitleRepository titleRepo, CoverPath coverPath) {
        this.titleRepo = titleRepo;
        this.coverPath = coverPath;
    }

    @Override
    public String name() {
        return "prune-covers";
    }

    @Override
    public String description() {
        return "Remove orphaned cover images that no longer match any title in the database";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        Path root = coverPath.root();
        if (!Files.isDirectory(root)) {
            io.println("No covers directory found — nothing to prune.");
            return;
        }

        int pruned = 0;
        int kept = 0;
        int errors = 0;

        try (Stream<Path> labelDirs = Files.list(root)) {
            for (Path labelDir : labelDirs.filter(Files::isDirectory).toList()) {
                try (Stream<Path> coverFiles = Files.list(labelDir)) {
                    for (Path coverFile : coverFiles.filter(Files::isRegularFile).toList()) {
                        String filename = coverFile.getFileName().toString();
                        if (!CoverPath.isImageFile(filename)) continue;

                        String baseCode = filename.substring(0, filename.lastIndexOf('.'));
                        if (titleRepo.findByBaseCode(baseCode).isEmpty()) {
                            try {
                                Files.delete(coverFile);
                                log.info("Pruned orphaned cover: {}", coverFile);
                                pruned++;
                            } catch (IOException e) {
                                log.error("Failed to delete {}: {}", coverFile, e.getMessage());
                                errors++;
                            }
                        } else {
                            kept++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            io.println("Error scanning covers directory: " + e.getMessage());
            log.error("Error scanning covers directory", e);
            return;
        }

        io.println(String.format("Pruned: %d  |  Kept: %d  |  Errors: %d", pruned, kept, errors));
    }
}
