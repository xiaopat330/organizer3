package com.organizer3.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.TitleTagRepository;
import com.organizer3.sync.TitleCodeParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Loads actress YAML files from {@code resources/actresses/} and applies them to the database.
 *
 * <p>All enrichment fields are overwritten unconditionally — YAML is authoritative.
 * Operational fields (tier, favorite, bookmark, grade, rejected) are never touched.
 *
 * <p>Portfolio entries are applied to matching titles (looked up by code). Titles that
 * do not yet exist in the DB are created with minimal metadata and associated with the actress.
 * Tags are always replaced atomically per title.
 */
@Slf4j
@RequiredArgsConstructor
public class ActressYamlLoader {

    private static final String RESOURCE_PREFIX = "actresses/";

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final TitleTagRepository tagRepo;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final TitleCodeParser codeParser = new TitleCodeParser();

    /**
     * Load a single actress YAML by romanized name slug (e.g. "nana_ogura").
     * Matches the filename {@code actresses/<slug>.yaml} on the classpath.
     *
     * @return a summary of what was applied
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if no matching resource is found
     */
    public LoadResult loadOne(String slug) throws IOException {
        String resourcePath = RESOURCE_PREFIX + slug + ".yaml";
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalArgumentException("No actress YAML found at classpath:" + resourcePath);
        }
        ActressYaml yaml = parseYaml(stream);
        return apply(slug, yaml);
    }

    /**
     * Load all actress YAML files under {@code resources/actresses/} on the classpath.
     *
     * @return one {@link LoadResult} per file processed
     */
    public List<LoadResult> loadAll() throws IOException {
        List<LoadResult> results = new ArrayList<>();
        URL dirUrl = getClass().getClassLoader().getResource(RESOURCE_PREFIX);
        if (dirUrl == null) {
            log.warn("No actresses/ directory found on classpath");
            return results;
        }

        // Read directory listing from the classpath URL
        try (var dirStream = dirUrl.openStream()) {
            byte[] bytes = dirStream.readAllBytes();
            String listing = new String(bytes);
            for (String line : listing.split("\n")) {
                line = line.trim();
                if (line.endsWith(".yaml")) {
                    String slug = line.replace(".yaml", "");
                    try {
                        results.add(loadOne(slug));
                    } catch (Exception e) {
                        log.error("Failed to load actress YAML for slug '{}': {}", slug, e.getMessage());
                    }
                }
            }
        }
        return results;
    }

    private ActressYaml parseYaml(InputStream stream) throws IOException {
        return yaml.readValue(stream, ActressYaml.class);
    }

    private LoadResult apply(String slug, ActressYaml data) {
        ActressYaml.Profile profile = data.profile();
        String canonicalName = profile.name() != null ? profile.name().toCanonicalName() : slug;

        // Resolve actress — try canonical name, then stage name
        Optional<Actress> found = actressRepo.resolveByName(canonicalName);
        if (found.isEmpty() && profile.name() != null && profile.name().stageName() != null) {
            found = actressRepo.resolveByName(profile.name().stageName());
        }

        final Actress actress;
        if (found.isPresent()) {
            actress = found.get();
            log.info("Enriching actress: {} (id={})", actress.getCanonicalName(), actress.getId());
        } else {
            // Create a minimal actress record — sync hasn't seen her yet
            actress = actressRepo.save(Actress.builder()
                    .canonicalName(canonicalName)
                    .stageName(profile.name() != null ? profile.name().stageName() : null)
                    .tier(Actress.Tier.LIBRARY)
                    .firstSeenAt(LocalDate.now())
                    .build());
            log.info("Created new actress from YAML: {} (id={})", actress.getCanonicalName(), actress.getId());
        }

        // Update profile enrichment fields
        ActressYaml.Measurements m = profile.measurements();
        actressRepo.updateProfile(
                actress.getId(),
                profile.name() != null ? profile.name().stageName() : null,
                parseDate(profile.dateOfBirth()),
                profile.birthplace(),
                profile.bloodType(),
                profile.heightCm(),
                m != null ? m.bust() : null,
                m != null ? m.waist() : null,
                m != null ? m.hip() : null,
                profile.cup(),
                parseDate(profile.activeFrom()),
                parseDate(profile.activeTo()),
                profile.biography(),
                profile.legacy()
        );

        // Update aliases from alternate_names
        if (profile.name() != null && profile.name().alternateNames() != null) {
            List<String> aliases = profile.name().alternateNames().stream()
                    .map(ActressYaml.AlternateName::name)
                    .filter(n -> n != null && !n.isBlank())
                    .toList();
            if (!aliases.isEmpty()) {
                actressRepo.replaceAllAliases(actress.getId(), aliases);
            }
        }

        // Apply portfolio entries
        int titlesCreated = 0;
        int titlesEnriched = 0;
        List<String> unresolved = new ArrayList<>();

        if (data.portfolio() != null) {
            for (ActressYaml.PortfolioEntry entry : data.portfolio()) {
                if (entry.code() == null || entry.code().isBlank()) continue;

                String code = entry.code().trim();
                Optional<Title> existing = titleRepo.findByCode(code);

                final long titleId;
                if (existing.isPresent()) {
                    titleId = existing.get().getId();
                    titlesEnriched++;
                } else {
                    // Create a minimal title stub so enrichment data is not lost
                    TitleCodeParser.ParsedCode parsed = codeParser.parse(code);
                    Title stub = titleRepo.save(Title.builder()
                            .code(code)
                            .baseCode(parsed != null ? parsed.baseCode() : code)
                            .label(entry.label())
                            .seqNum(parsed != null ? parsed.seqNum() : null)
                            .actressId(actress.getId())
                            .build());
                    titleId = stub.getId();
                    titlesCreated++;
                }

                // Enrich title metadata
                Actress.Grade grade = null;
                if (entry.grade() != null && !entry.grade().isBlank()) {
                    try {
                        grade = Actress.Grade.fromDisplay(entry.grade().trim());
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown grade '{}' for code {}, skipping", entry.grade(), code);
                    }
                }

                String titleOriginal = entry.title() != null ? entry.title().original() : null;
                String titleEnglish = entry.title() != null ? entry.title().english() : null;

                titleRepo.enrichTitle(titleId, titleOriginal, titleEnglish,
                        parseDate(entry.date()), entry.notes(), grade);

                // Replace tags
                List<String> tags = entry.tags() != null ? entry.tags() : List.of();
                tagRepo.replaceTagsForTitle(titleId, tags);
            }
        }

        return new LoadResult(actress.getCanonicalName(), actress.getId(), titlesCreated, titlesEnriched, unresolved);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Summary of what was applied for one actress YAML load.
     */
    public record LoadResult(
            String canonicalName,
            long actressId,
            int titlesCreated,
            int titlesEnriched,
            List<String> unresolvedCodes
    ) {}
}
