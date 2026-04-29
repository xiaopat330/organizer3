package com.organizer3.javdb.enrichment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes one JSON backup file per actress after each successful HTTP filmography fetch.
 *
 * <p>File layout: {@code {dataDir}/backups/filmography/{prefix}/{slug}.json}
 * where {@code {prefix}} is the first 2 characters of the actress slug. This sharding
 * keeps directory entries manageable for actresses with short slugs.
 *
 * <p>The file is always overwritten — the backup represents the most recent successful
 * fetch for that actress. Failures are logged at WARN; they never propagate to the caller.
 */
@Slf4j
public class FilmographyBackupWriter {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final Path backupRoot;

    public FilmographyBackupWriter(Path dataDir) {
        this.backupRoot = dataDir.resolve("backups").resolve("filmography");
    }

    /**
     * Writes {@code result} for {@code actressSlug} to a JSON file in the sharded backup dir.
     * Creates parent directories if needed. Always overwrites any existing file.
     */
    public void write(String actressSlug, FetchResult result) throws IOException {
        String prefix = actressSlug.length() >= 2
                ? actressSlug.substring(0, 2)
                : actressSlug;
        Path dir = backupRoot.resolve(prefix);
        Files.createDirectories(dir);
        Path file = dir.resolve(actressSlug + ".json");
        Envelope envelope = new Envelope(actressSlug, result.fetchedAt(), result.pageCount(),
                result.lastReleaseDate(), result.source(), result.entries());
        MAPPER.writeValue(file.toFile(), envelope);
        log.debug("javdb: backup written for {} → {}", actressSlug, file);
    }

    /**
     * Reads and returns the backup for {@code actressSlug}, or {@code null} if no backup exists.
     */
    public Envelope read(String actressSlug) throws IOException {
        String prefix = actressSlug.length() >= 2
                ? actressSlug.substring(0, 2)
                : actressSlug;
        Path file = backupRoot.resolve(prefix).resolve(actressSlug + ".json");
        if (!Files.exists(file)) return null;
        return MAPPER.readValue(file.toFile(), Envelope.class);
    }

    /**
     * Lists all actress slugs that have a backup file under the backup root.
     * Returns an empty list if the backup directory does not exist.
     */
    public List<String> listBackedUpSlugs() throws IOException {
        if (!Files.exists(backupRoot)) return List.of();
        List<String> slugs = new java.util.ArrayList<>();
        try (var prefixDirs = Files.list(backupRoot)) {
            for (Path prefixDir : (Iterable<Path>) prefixDirs::iterator) {
                if (!Files.isDirectory(prefixDir)) continue;
                try (var files = Files.list(prefixDir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        String name = f.getFileName().toString();
                        if (name.endsWith(".json")) {
                            slugs.add(name.substring(0, name.length() - 5));
                        }
                    }
                }
            }
        }
        slugs.sort(null);
        return slugs;
    }

    /**
     * Archives all existing backup files into a single zip at
     * {@code {dataDir}/backups/filmography-archive-{timestamp}.zip}.
     *
     * @return path to the created zip file
     */
    public Path archive() throws IOException {
        Path archiveDir = backupRoot.getParent();
        Files.createDirectories(archiveDir);
        Path archiveFile = archiveDir
                .resolve("filmography-archive-" + java.time.Instant.now().toString().replace(':', '-') + ".zip");
        try (var zos = new java.util.zip.ZipOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(archiveFile)))) {
            if (Files.exists(backupRoot)) {
                try (var prefixDirs = Files.list(backupRoot)) {
                    for (Path prefixDir : (Iterable<Path>) prefixDirs::iterator) {
                        if (!Files.isDirectory(prefixDir)) continue;
                        try (var files = Files.list(prefixDir)) {
                            for (Path f : (Iterable<Path>) files::iterator) {
                                String entryName = prefixDir.getFileName() + "/" + f.getFileName();
                                zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                                Files.copy(f, zos);
                                zos.closeEntry();
                            }
                        }
                    }
                }
            }
        }
        log.info("javdb: filmography backup archived → {}", archiveFile);
        return archiveFile;
    }

    /**
     * Imports all JSON files from a zip archive into the backup directory, then upserts
     * each into the repository.
     *
     * @return number of actress filmographies imported
     */
    public int importArchive(Path zipFile, JavdbActressFilmographyRepository repo) throws IOException {
        Path tempDir = Files.createTempDirectory("filmography-import-");
        try {
            try (var zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(Files.newInputStream(zipFile)))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!name.endsWith(".json")) continue;
                    Path dest = tempDir.resolve(name.replace('/', java.io.File.separatorChar));
                    Files.createDirectories(dest.getParent());
                    Files.copy(zis, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    zis.closeEntry();
                }
            }

            int count = 0;
            try (var all = Files.walk(tempDir)) {
                for (Path f : (Iterable<Path>) all::iterator) {
                    if (!Files.isRegularFile(f) || !f.toString().endsWith(".json")) continue;
                    Envelope env = MAPPER.readValue(f.toFile(), Envelope.class);
                    List<FilmographyEntry> entries = env.entries() != null ? env.entries() : List.of();
                    FetchResult result = new FetchResult(
                            env.fetchedAt(), env.pageCount(), env.lastReleaseDate(),
                            "imported_backup", entries);
                    repo.upsertFilmography(env.actressSlug(), result);
                    write(env.actressSlug(), result);
                    count++;
                }
            }
            log.info("javdb: imported {} actress filmographies from {}", count, zipFile);
            return count;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        } catch (IOException e) {
            // best effort
        }
    }

    /** JSON envelope stored on disk — one per actress slug. */
    public record Envelope(
            String actressSlug,
            String fetchedAt,
            int pageCount,
            String lastReleaseDate,
            String source,
            List<FilmographyEntry> entries
    ) {}
}
