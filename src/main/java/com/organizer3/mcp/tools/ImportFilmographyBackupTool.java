package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.javdb.enrichment.FilmographyBackupWriter;
import com.organizer3.javdb.enrichment.JavdbActressFilmographyRepository;
import com.organizer3.javdb.enrichment.JavdbSlugResolver;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.nio.file.Path;

/**
 * Imports filmography data from a zip archive into the L2 cache (SQLite) and backup store.
 * Also clears L1 for each imported actress so the next resolve call re-reads from L2.
 */
public class ImportFilmographyBackupTool implements Tool {

    private final FilmographyBackupWriter backupWriter;
    private final JavdbActressFilmographyRepository filmographyRepo;
    private final JavdbSlugResolver resolver;

    public ImportFilmographyBackupTool(FilmographyBackupWriter backupWriter,
                                       JavdbActressFilmographyRepository filmographyRepo,
                                       JavdbSlugResolver resolver) {
        this.backupWriter = backupWriter;
        this.filmographyRepo = filmographyRepo;
        this.resolver = resolver;
    }

    @Override public String name()        { return "import_filmography_backup"; }
    @Override public String description() {
        return "Import filmography data from a zip archive into the L2 cache. "
             + "Each entry is upserted into SQLite and the in-process L1 is cleared for the actress.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("archive_path", "string", "Absolute path to the zip archive to import.")
                .require("archive_path")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String archivePath = Schemas.requireString(args, "archive_path");
        Path zipFile = Path.of(archivePath);
        if (!zipFile.toFile().exists()) {
            throw new IllegalArgumentException("Archive not found: " + archivePath);
        }

        try {
            // Import upserts into DB + backup dir; then clear L1 for all affected actresses.
            ClearingRepo clearingRepo = new ClearingRepo(filmographyRepo, resolver);
            int count = backupWriter.importArchive(zipFile, clearingRepo);
            return new Result(archivePath, count);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to import filmography backup: " + e.getMessage(), e);
        }
    }

    /** Delegates to the real repo but also evicts L1 on every upsert. */
    private static class ClearingRepo implements JavdbActressFilmographyRepository {
        private final JavdbActressFilmographyRepository delegate;
        private final JavdbSlugResolver resolver;

        ClearingRepo(JavdbActressFilmographyRepository delegate, JavdbSlugResolver resolver) {
            this.delegate = delegate;
            this.resolver = resolver;
        }

        @Override public void upsertFilmography(String actressSlug, com.organizer3.javdb.enrichment.FetchResult result) {
            delegate.upsertFilmography(actressSlug, result);
            resolver.evictL1(actressSlug);
        }

        @Override public java.util.Optional<com.organizer3.javdb.enrichment.FilmographyMeta> findMeta(String s) { return delegate.findMeta(s); }
        @Override public java.util.Map<String, String> getCodeToSlug(String s)                                  { return delegate.getCodeToSlug(s); }
        @Override public java.util.Optional<String> findTitleSlug(String s, String c)                           { return delegate.findTitleSlug(s, c); }
        @Override public void evict(String s)                                                                    { delegate.evict(s); }
        @Override public int markNotFound(String s, String t)                                                    { return delegate.markNotFound(s, t); }
        @Override public java.util.List<String> findAllActressSlugs()                                           { return delegate.findAllActressSlugs(); }
        @Override public boolean isStale(String s, int d, java.time.Clock c)                                    { return delegate.isStale(s, d, c); }
    }

    public record Result(String archivePath, int actressesImported) {}
}
