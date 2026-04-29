package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.FilmographyBackupWriter;
import com.organizer3.javdb.enrichment.FilmographyBackupWriter.Envelope;
import com.organizer3.javdb.enrichment.JavdbActressFilmographyRepository;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.util.List;
import java.util.Map;

/**
 * Returns the most-recently-written backup JSON for one actress, or triggers a live export
 * from L2 if no backup file exists yet.
 */
public class ExportFilmographyBackupTool implements Tool {

    private final FilmographyBackupWriter backupWriter;
    private final JavdbActressFilmographyRepository filmographyRepo;

    public ExportFilmographyBackupTool(FilmographyBackupWriter backupWriter,
                                       JavdbActressFilmographyRepository filmographyRepo) {
        this.backupWriter = backupWriter;
        this.filmographyRepo = filmographyRepo;
    }

    @Override public String name()        { return "export_filmography_backup"; }
    @Override public String description() {
        return "Return the backup JSON for one actress's filmography. "
             + "Reads from the most-recently-written backup file, or exports live from L2 if no backup exists.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_slug", "string", "Javdb actress slug.")
                .require("actress_slug")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String actressSlug = Schemas.requireString(args, "actress_slug");

        try {
            Envelope env = backupWriter.read(actressSlug);
            if (env != null) {
                return new Result(actressSlug, "backup_file", env.fetchedAt(),
                        env.entries() != null ? env.entries().size() : 0, env);
            }

            // No backup file — try to export live from L2
            Map<String, String> codeToSlug = filmographyRepo.getCodeToSlug(actressSlug);
            if (codeToSlug.isEmpty()) {
                throw new IllegalArgumentException(
                        "No cached filmography for actress slug: " + actressSlug);
            }
            var meta = filmographyRepo.findMeta(actressSlug).orElseThrow(() ->
                    new IllegalArgumentException("No metadata for actress slug: " + actressSlug));
            List<com.organizer3.javdb.enrichment.FilmographyEntry> entries = codeToSlug.entrySet().stream()
                    .map(e -> new com.organizer3.javdb.enrichment.FilmographyEntry(e.getKey(), e.getValue()))
                    .sorted(java.util.Comparator.comparing(com.organizer3.javdb.enrichment.FilmographyEntry::productCode))
                    .toList();
            com.organizer3.javdb.enrichment.FetchResult result = new com.organizer3.javdb.enrichment.FetchResult(
                    meta.fetchedAt(), meta.pageCount(), meta.lastReleaseDate(), meta.source(), entries);
            backupWriter.write(actressSlug, result);
            Envelope written = backupWriter.read(actressSlug);
            return new Result(actressSlug, "live_export", meta.fetchedAt(), entries.size(), written);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to export filmography backup for " + actressSlug + ": " + e.getMessage(), e);
        }
    }

    public record Result(String actressSlug, String source, String fetchedAt, int entryCount, Envelope backup) {}
}
