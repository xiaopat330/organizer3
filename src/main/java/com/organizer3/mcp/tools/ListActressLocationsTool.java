package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Multi-volume view of where an actress's content lives on disk.
 *
 * <p>Returns per-volume parent folder paths (the actress folder), whether the parent
 * folder basename matches the actress's canonical name (case-insensitive), and per-title
 * paths + their individual folder-match status. Drives session planning before curation work.
 *
 * <p>Read-only — no mutation, no curation log. Works entirely from DB state.
 */
@Slf4j
public class ListActressLocationsTool implements Tool {

    private final ActressRepository actressRepo;
    private final Jdbi jdbi;

    public ListActressLocationsTool(ActressRepository actressRepo, Jdbi jdbi) {
        this.actressRepo = actressRepo;
        this.jdbi        = jdbi;
    }

    @Override public String name() { return "list_actress_locations"; }

    @Override
    public String description() {
        return "Multi-volume view of where an actress's content lives on disk. "
             + "Returns per-volume parent folder paths, canonical-match status, and per-title "
             + "locations. Drives session planning before any cleanup work.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_id", "integer", "Actress id to inspect.")
                .require("actress_id")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long actressId = Schemas.requireLong(args, "actress_id");

        Actress actress = actressRepo.findById(actressId)
                .orElseThrow(() -> new IllegalArgumentException("No actress found for id=" + actressId));

        List<String> aliases = actressRepo.findAliases(actress.getId()).stream()
                .map(ActressAlias::aliasName)
                .toList();

        String canonical = actress.getCanonicalName();
        String canonicalLower = canonical.toLowerCase(Locale.ROOT);

        log.info("list_actress_locations actressId={} name={}", actressId, canonical);

        // ── Query title_locations grouped by volume ───────────────────────────
        record Row(String code, String volumeId, String path) {}
        List<Row> rows = jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT t.code, tl.volume_id, tl.path
                        FROM titles t
                        JOIN title_locations tl ON tl.title_id = t.id
                        WHERE t.actress_id = :actressId
                          AND tl.stale_since IS NULL
                        ORDER BY tl.volume_id, tl.path
                        """)
                        .bind("actressId", actressId)
                        .map((rs, ctx) -> new Row(
                                rs.getString("code"),
                                rs.getString("volume_id"),
                                rs.getString("path")))
                        .list());

        // ── Group by volume, then by parent folder (actress folder) ───────────
        // volumeId → (parentFolderPath → List<Row>)
        LinkedHashMap<String, LinkedHashMap<Path, List<Row>>> byVolume = new LinkedHashMap<>();
        for (Row row : rows) {
            Path titleFolder = Path.of(row.path());
            Path parent = titleFolder.getParent();
            if (parent == null) parent = Path.of("/");
            byVolume
                    .computeIfAbsent(row.volumeId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(parent, k -> new ArrayList<>())
                    .add(row);
        }

        // ── Build per-volume response ─────────────────────────────────────────
        List<PerVolume> perVolume = new ArrayList<>();
        for (Map.Entry<String, LinkedHashMap<Path, List<Row>>> volEntry : byVolume.entrySet()) {
            String volumeId = volEntry.getKey();

            // All parent folders for this volume (typically one, but could be multiple if titles
            // are scattered)
            List<TitleEntry> allTitles = new ArrayList<>();
            String representativeParent = null;
            boolean representativeMatch = false;

            for (Map.Entry<Path, List<Row>> parentEntry : volEntry.getValue().entrySet()) {
                Path parentFolder = parentEntry.getKey();
                String parentBasename = parentFolder.getFileName() != null
                        ? parentFolder.getFileName().toString()
                        : parentFolder.toString();
                boolean parentMatchesCanonical = parentBasename
                        .toLowerCase(Locale.ROOT)
                        .equals(canonicalLower);

                for (Row r : parentEntry.getValue()) {
                    // Per-title: does the title's specific folder (its parent) match canonical?
                    allTitles.add(new TitleEntry(r.code(), r.path(), parentMatchesCanonical));
                }

                // Use the first parent folder as the representative for the volume-level fields
                if (representativeParent == null) {
                    representativeParent = parentFolder.toString();
                    representativeMatch = parentMatchesCanonical;
                }
            }

            perVolume.add(new PerVolume(
                    volumeId,
                    representativeParent != null ? representativeParent : "/",
                    representativeMatch,
                    allTitles.size(),
                    allTitles));
        }

        return new Result(actress.getId(), canonical, aliases, perVolume);
    }

    // ── output records ────────────────────────────────────────────────────────

    public record TitleEntry(String code, String path, boolean folderMatchesCanonical) {}

    public record PerVolume(
            String volumeId,
            String parentFolderPath,
            boolean parentFolderMatchesCanonical,
            int titleCount,
            List<TitleEntry> titles) {}

    public record Result(
            long actressId,
            String canonicalName,
            List<String> aliases,
            List<PerVolume> perVolume) {}
}
