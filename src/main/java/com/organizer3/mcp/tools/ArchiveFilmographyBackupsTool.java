package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.FilmographyBackupWriter;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.nio.file.Path;

/**
 * Archives all current filmography backup JSON files into a single timestamped zip.
 */
public class ArchiveFilmographyBackupsTool implements Tool {

    private final FilmographyBackupWriter backupWriter;

    public ArchiveFilmographyBackupsTool(FilmographyBackupWriter backupWriter) {
        this.backupWriter = backupWriter;
    }

    @Override public String name()        { return "archive_filmography_backups"; }
    @Override public String description() {
        return "Archive all filmography backup JSON files into a timestamped zip file "
             + "under {dataDir}/backups/. Returns the path to the created archive.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.empty();
    }

    @Override
    public Object call(JsonNode args) {
        try {
            int slugCount = backupWriter.listBackedUpSlugs().size();
            Path archivePath = backupWriter.archive();
            return new Result(archivePath.toString(), slugCount);
        } catch (Exception e) {
            throw new RuntimeException("Failed to archive filmography backups: " + e.getMessage(), e);
        }
    }

    public record Result(String archivePath, int slugsArchived) {}
}
