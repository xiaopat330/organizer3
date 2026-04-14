package com.organizer3.avstars.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.avstars.model.AvTagDefinition;
import com.organizer3.avstars.repository.AvTagDefinitionRepository;
import com.organizer3.avstars.repository.AvVideoTagRepository;
import com.organizer3.avstars.sync.AvTagYamlLoader;
import com.organizer3.command.Command;
import com.organizer3.shell.SessionContext;
import com.organizer3.shell.io.CommandIO;
import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tag taxonomy commands:
 * <ul>
 *   <li>{@code av tags dump} — reads raw tag tokens from all av_videos.tags_json and prints
 *       a frequency-sorted token count (tab-separated: count \\t token).</li>
 *   <li>{@code av tags apply} — loads {@code data/av_tags.yaml}, upserts tag definitions,
 *       then maps each video's raw tokens to canonical tag slugs via aliases and writes
 *       to {@code av_video_tags}.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class AvTagsCommand implements Command {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AvTagDefinitionRepository tagDefRepo;
    private final AvVideoTagRepository videoTagRepo;
    private final AvTagYamlLoader tagYamlLoader;
    private final Path tagsYamlPath;

    @Override
    public String name() { return "av tags"; }

    @Override
    public String description() {
        return "AV tag taxonomy: 'av tags dump' (token frequencies) | 'av tags apply' (load yaml, populate tags)";
    }

    @Override
    public void execute(String[] args, SessionContext ctx, CommandIO io) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "dump"  -> doDump(io);
            case "apply" -> doApply(io);
            default      -> io.println("Usage: av tags dump | av tags apply");
        }
    }

    // ── dump ──────────────────────────────────────────────────────────────────

    private void doDump(CommandIO io) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        // Pull tags_json for every video — use findByVolume across all volumes isn't great,
        // but we don't have a findAll() on the repo. Use a workaround: query via all known vids.
        // Actually, we load all videos via the jdbi repo's underlying query.
        // We need a findAll() — work around by fetching all actress videos grouped.
        // Since AvVideoRepository doesn't expose findAll, we iterate by querying all rows
        // directly via the JDBI handle embedded in the repo. As a clean fallback, we'll
        // query the tags_json column in a dedicated pass.
        //
        // To keep this clean without adding a new repo method, we re-use the existing approach:
        // aggregate from all volumes. For now we issue a direct query through our JDBI-based tag repo.
        // NOTE: The cleanest approach is to add a lightweight repo method. We'll use the video tag
        // repo as a vehicle to execute the raw query since we have JDBI access there.
        List<String> allTagsJson = videoTagRepo.getAllTagsJson();
        for (String tagsJson : allTagsJson) {
            if (tagsJson == null || tagsJson.isBlank()) continue;
            try {
                List<String> tokens = JSON.readValue(tagsJson, new TypeReference<>() {});
                for (String t : tokens) {
                    counts.merge(t.toLowerCase(), 1, Integer::sum);
                }
            } catch (Exception ignored) {}
        }
        if (counts.isEmpty()) {
            io.println("No tags found. Run 'av parse' first to populate tags_json.");
            return;
        }
        // Sort by count desc, then token asc
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> io.println(e.getValue() + "\t" + e.getKey()));
    }

    // ── apply ─────────────────────────────────────────────────────────────────

    private void doApply(CommandIO io) {
        if (!Files.exists(tagsYamlPath)) {
            io.println("Tag YAML not found: " + tagsYamlPath);
            io.println("Create it at that path with entries like:");
            io.println("  - slug: big-tits");
            io.println("    displayName: Big Tits");
            io.println("    category: body");
            io.println("    aliases: [bigtits, big_tits]");
            return;
        }

        // 1. Load YAML → upsert tag definitions
        int loaded;
        try {
            loaded = tagYamlLoader.load(tagsYamlPath);
        } catch (Exception e) {
            io.println("Failed to load " + tagsYamlPath + ": " + e.getMessage());
            return;
        }
        io.println("Loaded " + loaded + " tag definition(s).");

        // 2. Build alias → slug lookup map
        List<AvTagDefinition> defs = tagDefRepo.findAll();
        Map<String, String> aliasToSlug = new HashMap<>();
        for (AvTagDefinition def : defs) {
            // The slug itself is also a match
            aliasToSlug.put(def.getSlug().toLowerCase(), def.getSlug());
            // Also map display name (lowercased, de-spaced)
            if (def.getDisplayName() != null) {
                aliasToSlug.put(def.getDisplayName().toLowerCase(), def.getSlug());
            }
            // Aliases from JSON array
            if (def.getAliasesJson() != null) {
                try {
                    List<String> aliases = JSON.readValue(def.getAliasesJson(), new TypeReference<>() {});
                    for (String alias : aliases) {
                        aliasToSlug.put(alias.toLowerCase(), def.getSlug());
                    }
                } catch (Exception ignored) {}
            }
        }

        // 3. For every video with tags_json, resolve tokens → slugs and write av_video_tags
        List<String[]> videoTokenRows = videoTagRepo.getAllVideoIdAndTagsJson();
        int tagged = 0;
        int skipped = 0;
        for (String[] row : videoTokenRows) {
            long videoId = Long.parseLong(row[0]);
            String tagsJson = row[1];
            if (tagsJson == null || tagsJson.isBlank()) { skipped++; continue; }

            List<String> tokens;
            try {
                tokens = JSON.readValue(tagsJson, new TypeReference<>() {});
            } catch (Exception e) { skipped++; continue; }

            List<String> resolvedSlugs = new ArrayList<>();
            for (String token : tokens) {
                String slug = aliasToSlug.get(token.toLowerCase());
                if (slug != null && !resolvedSlugs.contains(slug)) {
                    resolvedSlugs.add(slug);
                }
            }
            if (resolvedSlugs.isEmpty()) { skipped++; continue; }

            // Delete existing 'apply' source tags and re-insert
            videoTagRepo.deleteByVideoIdAndSource(videoId, "apply");
            for (String slug : resolvedSlugs) {
                videoTagRepo.insertVideoTag(videoId, slug, "apply");
            }
            tagged++;
        }
        io.println("Tagged " + tagged + " video(s) (" + skipped + " had no matching tokens).");
    }
}
