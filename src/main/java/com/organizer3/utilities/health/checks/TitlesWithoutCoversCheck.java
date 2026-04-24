package com.organizer3.utilities.health.checks;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.TitleRepository;
import com.organizer3.utilities.health.LibraryHealthCheck;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Titles whose expected cover file doesn't exist under the covers directory. "Titles without
 * covers" is surface-only: the fix is typically re-running {@code sync covers} for that
 * label, which is a Volumes-screen action. Phase 1 just reports.
 *
 * <p>Checks only titles with a non-null label and baseCode — others can't have an expected
 * cover path to begin with. A title whose cover lives on an unmounted volume will show up
 * here until that volume is resynced; that's accepted Phase 1 behavior (the cover is
 * actually missing from local cache).
 *
 * <p>Cover presence is determined by walking the covers root once into an in-memory set
 * ({@code "LABEL/baseCode"} keys), then doing O(1) lookups per title — not per-title
 * filesystem probes, which would be O(5n) stat calls for a 50K library.
 */
@Slf4j
public final class TitlesWithoutCoversCheck implements LibraryHealthCheck {

    private static final int SAMPLE_LIMIT = 50;

    private final TitleRepository titles;
    private final CoverPath coverPath;

    public TitlesWithoutCoversCheck(TitleRepository titles, CoverPath coverPath) {
        this.titles = titles;
        this.coverPath = coverPath;
    }

    @Override public String id() { return "titles_without_covers"; }
    @Override public String label() { return "Titles without covers"; }
    @Override public String description() {
        return "Titles whose expected cover image file is not present in the local covers cache.";
    }
    @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }

    @Override
    public CheckResult run() {
        Set<String> presentCovers = buildPresentCoversIndex();
        int total = 0;
        List<Finding> sample = new ArrayList<>();
        // Paged walk keeps memory bounded even for large libraries.
        final int page = 500;
        int offset = 0;
        while (true) {
            List<Title> batch = titles.findLibraryPaged(null, null, List.of(), List.of(),
                    "productCode", true, page, offset);
            if (batch.isEmpty()) break;
            for (Title t : batch) {
                if (t.getLabel() == null || t.getBaseCode() == null) continue;
                String key = t.getLabel().toUpperCase() + "/" + t.getBaseCode();
                if (!presentCovers.contains(key)) {
                    total++;
                    if (sample.size() < SAMPLE_LIMIT) {
                        sample.add(new Finding(
                                String.valueOf(t.getId()),
                                t.getCode(),
                                buildDetail(t)));
                    }
                }
            }
            if (batch.size() < page) break;
            offset += page;
        }
        return new CheckResult(total, sample);
    }

    /** Walk the covers root once and return a set of {@code "LABEL/baseCode"} keys. */
    private Set<String> buildPresentCoversIndex() {
        Path root = coverPath.root();
        if (!Files.isDirectory(root)) return Set.of();
        Set<String> index = new HashSet<>();
        try (DirectoryStream<Path> labelDirs = Files.newDirectoryStream(root)) {
            for (Path labelDir : labelDirs) {
                if (!Files.isDirectory(labelDir)) continue;
                String label = labelDir.getFileName().toString().toUpperCase();
                try (DirectoryStream<Path> files = Files.newDirectoryStream(labelDir)) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        if (!CoverPath.isImageFile(name)) continue;
                        index.add(label + "/" + stripExtension(name));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to walk covers directory {}", root, e);
        }
        return index;
    }

    /**
     * Detail string for one missing-cover title. Shows the volumes where the title's videos
     * actually live (that's where {@code sync covers} would pull the image from), plus the
     * expected local cache path so the user can cross-check.
     * Uses locations already loaded by findLibraryPaged — no extra DB query needed.
     */
    private String buildDetail(Title t) {
        List<TitleLocation> locs = t.getLocations();
        String expected = "covers/" + t.getLabel().toUpperCase() + "/" + t.getBaseCode() + ".jpg";
        if (locs == null || locs.isEmpty()) {
            return "no locations — expected " + expected;
        }
        String volList = locs.stream()
                .map(l -> l.getVolumeId().toUpperCase())
                .distinct()
                .collect(Collectors.joining(", "));
        String firstPath = locs.get(0).getPath().toString();
        return "on volume(s) " + volList + " · " + firstPath + " · expected " + expected;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
