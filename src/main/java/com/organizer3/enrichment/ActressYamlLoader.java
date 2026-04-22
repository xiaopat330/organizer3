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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;

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

        for (String slug : discoverSlugs(dirUrl)) {
            try {
                results.add(loadOne(slug));
            } catch (Exception e) {
                log.error("Failed to load actress YAML for slug '{}': {}", slug, e.getMessage());
            }
        }
        return results;
    }

    /**
     * List every actress slug discoverable on the classpath under {@code actresses/}. Returns an
     * empty list (not an exception) if the directory is missing, so callers can treat a fresh
     * repo without YAMLs as a normal state. Used by the Utilities catalog service for the UI list.
     */
    public List<String> listSlugs() throws IOException {
        URL dirUrl = getClass().getClassLoader().getResource(RESOURCE_PREFIX);
        if (dirUrl == null) return List.of();
        return discoverSlugs(dirUrl);
    }

    /**
     * Parse a YAML for inspection without applying it to the DB. Returns {@code null} if no
     * matching resource exists. Distinct from {@link #loadOne} in that it never writes.
     */
    public ActressYaml peek(String slug) throws IOException {
        String resourcePath = RESOURCE_PREFIX + slug + ".yaml";
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (stream == null) return null;
        return parseYaml(stream);
    }

    private List<String> discoverSlugs(URL dirUrl) throws IOException {
        String protocol = dirUrl.getProtocol();
        if ("file".equals(protocol)) {
            try {
                try (var paths = Files.list(Path.of(dirUrl.toURI()))) {
                    return paths
                            .map(p -> p.getFileName().toString())
                            .filter(name -> name.endsWith(".yaml") && !name.startsWith("test_"))
                            .map(name -> name.replace(".yaml", ""))
                            .sorted()
                            .toList();
                }
            } catch (Exception e) {
                throw new IOException("Failed to list actresses directory: " + e.getMessage(), e);
            }
        } else if ("jar".equals(protocol)) {
            String jarPath = dirUrl.getPath();
            String jarFilePart = jarPath.substring(0, jarPath.indexOf('!'));
            String dirPrefix = RESOURCE_PREFIX;
            List<String> slugs = new ArrayList<>();
            try (JarFile jar = new JarFile(new File(new URI(jarFilePart)))) {
                jar.entries().asIterator().forEachRemaining(entry -> {
                    String name = entry.getName();
                    if (name.startsWith(dirPrefix) && name.endsWith(".yaml") && !entry.isDirectory()) {
                        String filename = name.substring(name.lastIndexOf('/') + 1);
                        if (!filename.startsWith("test_")) {
                            slugs.add(filename.replace(".yaml", ""));
                        }
                    }
                });
            } catch (Exception e) {
                throw new IOException("Failed to enumerate JAR entries: " + e.getMessage(), e);
            }
            slugs.sort(String::compareTo);
            return slugs;
        } else {
            throw new IOException("Unsupported classpath URL protocol '" + protocol + "' for actresses directory");
        }
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

        // Apply extended profile fields (reading, retirement, alternate names, studios, awards).
        actressRepo.updateExtendedProfile(
                actress.getId(),
                profile.name() != null ? profile.name().reading() : null,
                parseDate(profile.retirementAnnounced()),
                toAlternateNames(profile.name() != null ? profile.name().alternateNames() : null),
                toStudioTenures(profile.primaryStudios()),
                toAwards(profile.awards())
        );

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

    private static List<com.organizer3.model.Actress.AlternateName> toAlternateNames(
            List<ActressYaml.AlternateName> yaml) {
        if (yaml == null || yaml.isEmpty()) return List.of();
        return yaml.stream()
                .filter(a -> a != null && a.name() != null && !a.name().isBlank())
                .map(a -> new com.organizer3.model.Actress.AlternateName(a.name(), a.note()))
                .toList();
    }

    private static List<com.organizer3.model.Actress.StudioTenure> toStudioTenures(
            List<ActressYaml.Studio> yaml) {
        if (yaml == null || yaml.isEmpty()) return List.of();
        return yaml.stream()
                .filter(s -> s != null && (s.name() != null || s.company() != null))
                .map(s -> new com.organizer3.model.Actress.StudioTenure(
                        s.name(), s.company(),
                        parseDate(s.from()), parseDate(s.to()),
                        s.role()))
                .toList();
    }

    private static List<com.organizer3.model.Actress.Award> toAwards(List<ActressYaml.Award> yaml) {
        if (yaml == null || yaml.isEmpty()) return List.of();
        return yaml.stream()
                .filter(a -> a != null && (a.event() != null || a.category() != null))
                .map(a -> new com.organizer3.model.Actress.Award(a.event(), a.year(), a.category()))
                .toList();
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
